package framework.validation

import scala.quoted.*

private[validation] object ValidationMacros:

  def deriveProduct[A: Type](using Quotes): Expr[Validator[A]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[A].dealias
    val clsSym = tpe.typeSymbol
    val contractSym = TypeRepr.of[contract[Any]].typeSymbol

    final case class ExtractedContract(
        fTpe: TypeRepr,
        predTerm: Term,
        explicitMsg: Option[Expr[String]],
        sourceText: String
    )

    def unwrap(term: Term): Term = term match
      case Inlined(_, Nil, inner) => unwrap(inner)
      case Typed(inner, _)        => unwrap(inner)
      case other                  => other

    def isDefaultGetter(term: Term): Boolean = unwrap(term) match
      case Select(_, name) => name.contains("$default$")
      case Ident(name)     => name.contains("$default$")
      case _               => false

    def extractContracts(annots: List[Term]): List[ExtractedContract] =
      val seen = scala.collection.mutable.Set.empty[(Int, Int, String)]
      annots.flatMap { rawAnnot =>
        val annot = unwrap(rawAnnot)
        annot.tpe.dealias match
          case AppliedType(base, List(fTpe)) if base.typeSymbol == contractSym =>
            val key = (annot.pos.start, annot.pos.end, annot.show)
            if seen.contains(key) then None
            else
              seen += key
              val args = annot match
                case Apply(_, rawArgs) => rawArgs
                case other =>
                  report.errorAndAbort(s"Unsupported @contract syntax: ${other.show}", other.pos)
              var predOpt: Option[Term] = None
              var msgOpt: Option[Term] = None
              args.zipWithIndex.foreach {
                case (NamedArg("predicate", rhs), _) => predOpt = Some(rhs)
                case (NamedArg("message", rhs), _)   => msgOpt = Some(rhs)
                case (rhs, 0)                        => predOpt = Some(rhs)
                case (rhs, 1)                        => msgOpt = Some(rhs)
                case _                               => ()
              }
              val predTerm = predOpt.getOrElse(
                report.errorAndAbort(s"@contract requires a predicate function", annot.pos)
              )
              val cleanMsg: Option[Expr[String]] = msgOpt.filterNot(isDefaultGetter).flatMap {
                case Literal(StringConstant("")) => None
                case Literal(StringConstant(s))  => Some(Expr(s))
                case other                       => Some(other.changeOwner(Symbol.spliceOwner).asExprOf[String])
              }
              val src = predTerm.pos.sourceCode
                .map(_.trim.replaceAll("\\s+", " "))
                .filter(_.nonEmpty)
                .getOrElse(predTerm.show)
              Some(ExtractedContract(fTpe.dealias, predTerm, cleanMsg, src))
          case _ => None
      }

    val leafTypes = List(
      TypeRepr.of[String],
      TypeRepr.of[Boolean],
      TypeRepr.of[Int],
      TypeRepr.of[Long],
      TypeRepr.of[Double],
      TypeRepr.of[BigDecimal],
      TypeRepr.of[java.util.UUID],
      TypeRepr.of[io.circe.Json]
    )
    def isKnownNoopLeaf(ft: TypeRepr): Boolean =
      leafTypes.exists(ft =:= _)

    val caseFields = clsSym.caseFields
    val ctorParams = clsSym.primaryConstructor.paramSymss.flatten.filterNot(_.isTypeParam)

    // Build per-field check functions `(A, String) => Vector[ValidationIssue]` owned cleanly by spliceOwner.
    val fieldCheckExprs: List[Expr[(A, String) => Vector[ValidationIssue]]] =
      caseFields.zipWithIndex.map { (fieldSym, idx) =>
        val fieldName = fieldSym.name
        val fieldNameExpr = Expr(fieldName)
        val fieldTpe = tpe.memberType(fieldSym).dealias
        val annots = ctorParams.lift(idx).toList.flatMap(_.annotations) ++ fieldSym.annotations
        val contracts = extractContracts(annots)

        fieldTpe.asType match
          case '[ft] =>
            val nestedValidatorOpt: Option[Expr[Validator[ft]]] =
              if isKnownNoopLeaf(fieldTpe) then None
              else Expr.summon[Validator[ft]]

            val contractChecks: List[Expr[(ft, String) => Vector[ValidationIssue]]] = contracts.map { c =>
              c.fTpe.asType match
                case '[f] =>
                  val predExpr = c.predTerm.changeOwner(Symbol.spliceOwner).asExprOf[f => Boolean]
                  val msgOptExpr: Expr[Option[String]] = c.explicitMsg match
                    case Some(m) => '{ Some($m) }
                    case None    => '{ None }
                  val srcExpr = Expr(c.sourceText)

                  if fieldTpe <:< c.fTpe then
                    '{
                      val p = $predExpr
                      val m = $msgOptExpr
                      (v: ft, fPath: String) =>
                        Validator.evalCheck[f](p, v.asInstanceOf[f], fPath, m, $srcExpr).toVector
                    }
                  else
                    fieldTpe match
                      case AppliedType(optBase, List(elemTpe))
                          if optBase.typeSymbol == TypeRepr.of[Option[Any]].typeSymbol && (elemTpe <:< c.fTpe) =>
                        '{
                          val p = $predExpr
                          val m = $msgOptExpr
                          (v: ft, fPath: String) =>
                            v.asInstanceOf[Option[f]].fold(Vector.empty[ValidationIssue]) { item =>
                              Validator.evalCheck[f](p, item, fPath, m, $srcExpr).toVector
                            }
                        }
                      case AppliedType(_, List(elemTpe))
                          if (fieldTpe <:< TypeRepr.of[Iterable[Any]]) && (elemTpe <:< c.fTpe) =>
                        '{
                          val p = $predExpr
                          val m = $msgOptExpr
                          (v: ft, fPath: String) =>
                            v.asInstanceOf[Iterable[f]].iterator.zipWithIndex.flatMap { (item, elemIdx) =>
                              Validator.evalCheck[f](p, item, s"$fPath[$elemIdx]", m, $srcExpr)
                            }.toVector
                        }
                      case _ =>
                        report.errorAndAbort(
                          s"@contract[${c.fTpe.show}] on '${clsSym.name}.$fieldName' does not match field type ${fieldTpe.show}",
                          c.predTerm.pos
                        )
            }

            if contractChecks.isEmpty && nestedValidatorOpt.isEmpty then
              '{ (_: A, _: String) => Vector.empty[ValidationIssue] }
            else
              val checksListExpr = Expr.ofList(contractChecks)
              val nestedExpr: Expr[(ft, String) => Vector[ValidationIssue]] = nestedValidatorOpt match
                case Some(nv) => '{ (v: ft, fPath: String) => $nv.validateIssues(v, fPath) }
                case None     => '{ (_: ft, _: String) => Vector.empty[ValidationIssue] }

              '{
                val cList = $checksListExpr
                val nestedFn = $nestedExpr
                (instance: A, basePath: String) =>
                  val fPath = if basePath.isEmpty then $fieldNameExpr else s"$basePath.${$fieldNameExpr}"
                  val fieldVal = ${ Select('instance.asTerm, fieldSym).asExprOf[ft] }
                  val ownIssues = cList.iterator.flatMap(fn => fn(fieldVal, fPath)).toVector
                  ownIssues ++ nestedFn(fieldVal, fPath)
              }
      }

    // Extract class-level @contract[A](...) invariants
    val classContracts = extractContracts(clsSym.annotations)
    val classCheckExprs: List[Expr[(A, String) => Vector[ValidationIssue]]] = classContracts.map { c =>
      if !(tpe <:< c.fTpe) then
        report.errorAndAbort(
          s"Class-level @contract[${c.fTpe.show}] on '${clsSym.name}' must accept ${tpe.show}",
          c.predTerm.pos
        )
      c.fTpe.asType match
        case '[f] =>
          val predExpr = c.predTerm.changeOwner(Symbol.spliceOwner).asExprOf[f => Boolean]
          val msgOptExpr: Expr[Option[String]] = c.explicitMsg match
            case Some(m) => '{ Some($m) }
            case None    => '{ None }
          val srcExpr = Expr(c.sourceText)
          '{
            val p = $predExpr
            val m = $msgOptExpr
            (instance: A, basePath: String) =>
              Validator.evalCheck[f](p, instance.asInstanceOf[f], basePath, m, $srcExpr).toVector
          }
    }

    val allFieldFns = Expr.ofList(fieldCheckExprs)
    val allClassFns = Expr.ofList(classCheckExprs)

    '{
      val fieldChecks = $allFieldFns
      val classChecks = $allClassFns
      new Validator[A]:
        def validateIssues(value: A, path: String = ""): Vector[ValidationIssue] =
          val fieldIssues = fieldChecks.iterator.flatMap(fn => fn(value, path)).toVector
          if fieldIssues.nonEmpty then fieldIssues
          else classChecks.iterator.flatMap(fn => fn(value, path)).toVector
    }
