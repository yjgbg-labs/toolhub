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
        annotTerm: Term,
        sourceText: String
    )

    def unwrap(term: Term): Term = term match
      case Inlined(_, Nil, inner) => unwrap(inner)
      case Typed(inner, _)        => unwrap(inner)
      case other                  => other

    def extractContracts(annots: List[Term]): List[ExtractedContract] =
      val seen = scala.collection.mutable.Set.empty[(Int, Int, String)]
      annots.flatMap { rawAnnot =>
        val annot = unwrap(rawAnnot)
        annot.tpe.baseType(contractSym) match
          case AppliedType(_, List(fTpe)) =>
            val key = (annot.pos.start, annot.pos.end, annot.show)
            if seen.contains(key) then None
            else
              seen += key
              val src = annot.pos.sourceCode
                .map(_.trim.replaceAll("\\s+", " "))
                .filter(_.nonEmpty)
                .getOrElse(annot.show)
              Some(ExtractedContract(fTpe.dealias, annot, src))
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

    val intTpe = TypeRepr.of[Int]
    val longTpe = TypeRepr.of[Long]

    val caseFields = clsSym.caseFields
    val ctorParams = clsSym.primaryConstructor.paramSymss.flatten.filterNot(_.isTypeParam)

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
                  val instExpr = c.annotTerm.changeOwner(Symbol.spliceOwner).asExprOf[contract[f]]
                  val srcExpr = Expr(c.sourceText)

                  if fieldTpe <:< c.fTpe then
                    '{
                      val inst = $instExpr
                      (v: ft, fPath: String) =>
                        Validator.evalContract[f](inst, v.asInstanceOf[f], fPath, $srcExpr).toVector
                    }
                  else if (fieldTpe =:= intTpe) && (c.fTpe =:= longTpe) then
                    '{
                      val inst = $instExpr.asInstanceOf[contract[Long]]
                      (v: ft, fPath: String) =>
                        Validator.evalContract[Long](inst, v.asInstanceOf[Int].toLong, fPath, $srcExpr).toVector
                    }
                  else
                    fieldTpe match
                      case AppliedType(_, List(elemTpe))
                          if (fieldTpe <:< TypeRepr.of[Iterable[Any]]) && (elemTpe <:< c.fTpe) =>
                        '{
                          val inst = $instExpr
                          (v: ft, fPath: String) =>
                            v.asInstanceOf[Iterable[f]].iterator.zipWithIndex.flatMap { (item, elemIdx) =>
                              Validator.evalContract[f](inst, item, s"$fPath[$elemIdx]", $srcExpr)
                            }.toVector
                        }
                      case AppliedType(_, List(elemTpe))
                          if (fieldTpe <:< TypeRepr.of[Iterable[Any]]) && (elemTpe =:= intTpe) && (c.fTpe =:= longTpe) =>
                        '{
                          val inst = $instExpr.asInstanceOf[contract[Long]]
                          (v: ft, fPath: String) =>
                            v.asInstanceOf[Iterable[Int]].iterator.zipWithIndex.flatMap { (item, elemIdx) =>
                              Validator.evalContract[Long](inst, item.toLong, s"$fPath[$elemIdx]", $srcExpr)
                            }.toVector
                        }
                      case _ =>
                        report.errorAndAbort(
                          s"@${c.annotTerm.tpe.typeSymbol.name} (contract[${c.fTpe.show}]) on '${clsSym.name}.$fieldName' cannot validate field of type ${fieldTpe.show}",
                          c.annotTerm.pos
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

    val classContracts = extractContracts(clsSym.annotations)
    val classCheckExprs: List[Expr[(A, String) => Vector[ValidationIssue]]] = classContracts.map { c =>
      if !(tpe <:< c.fTpe) then
        report.errorAndAbort(
          s"Class-level @${c.annotTerm.tpe.typeSymbol.name} (contract[${c.fTpe.show}]) on '${clsSym.name}' must accept ${tpe.show}",
          c.annotTerm.pos
        )
      c.fTpe.asType match
        case '[f] =>
          val instExpr = c.annotTerm.changeOwner(Symbol.spliceOwner).asExprOf[contract[f]]
          val srcExpr = Expr(c.sourceText)
          '{
            val inst = $instExpr
            (instance: A, basePath: String) =>
              Validator.evalContract[f](inst, instance.asInstanceOf[f], basePath, $srcExpr).toVector
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
