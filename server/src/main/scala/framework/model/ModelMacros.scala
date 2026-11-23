package framework.model

import scala.quoted.*

private[framework] object ModelMacros:
  def typeId[A: Type](using Quotes): Expr[String] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[A].dealias
    tpe match
      case AppliedType(base, args) =>
        val names = args.map { arg =>
          arg.asType match
            case '[a] =>
              Expr.summon[JsonModel[a]] match
                case Some(model) => '{ $model.typeId }
                case None        => Expr(arg.show)
        }
        val name = Expr(base.typeSymbol.fullName)
        val arguments = Expr.ofList(names)
        '{ $name + $arguments.mkString("[", ",", "]") }
      case _ => Expr(tpe.typeSymbol.fullName)

  def defaults[A: Type](using Quotes): Expr[Vector[Option[() => Any]]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[A].dealias
    val companion = tpe.typeSymbol.companionModule
    val defaults = tpe.typeSymbol.caseFields.zipWithIndex.map { (_, index) =>
      val names = List(s"$$lessinit$$greater$$default$$${index + 1}", s"apply$$default$$${index + 1}")
      val method = if companion == Symbol.noSymbol then None
                   else names.flatMap(companion.methodMember).headOption
      method match
        case None => '{ None }
        case Some(symbol) =>
          var call: Term = Select(Ref(companion), symbol)
          call.tpe.widen match
            case poly: PolyType =>
              val arguments = tpe match
                case AppliedType(_, args) => args
                case _                   => Nil
              if arguments.size != poly.paramNames.size then
                report.errorAndAbort(s"Cannot derive constructor defaults for ${tpe.show}")
              call = TypeApply(call, arguments.map(Inferred(_)))
            case _ => ()
          call.tpe.widen match
            case method: MethodType if method.paramNames.isEmpty => call = Apply(call, Nil)
            case _: MethodType =>
              report.errorAndAbort("Defaults depending on earlier parameter lists require a custom JsonModel")
            case _ => ()
          val value = call.asExpr
          '{ Some(() => $value) }
    }
    val values = Expr.ofList(defaults)
    '{ $values.toVector }

  def fieldAliases[A: Type](using Quotes): Expr[Vector[Vector[String]]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[A].dealias
    val aliasSym = TypeRepr.of[configAlias].typeSymbol
    val caseFields = tpe.typeSymbol.caseFields
    val ctorParams = tpe.typeSymbol.primaryConstructor.paramSymss.flatten.filterNot(_.isTypeParam)
    def extractStrings(term: Term): List[String] = term match
      case Inlined(_, Nil, inner)          => extractStrings(inner)
      case Typed(inner, _)                 => extractStrings(inner)
      case Repeated(elems, _)              => elems.flatMap(extractStrings)
      case Literal(StringConstant(s))      => List(s)
      case Apply(_, args)                  => args.flatMap(extractStrings)
      case NamedArg(_, rhs)                => extractStrings(rhs)
      case _                               => Nil
    val perField = caseFields.zipWithIndex.map { (fieldSym, idx) =>
      val annots = ctorParams.lift(idx).toList.flatMap(_.annotations) ++ fieldSym.annotations
      val names = annots.filter(_.tpe.dealias.typeSymbol == aliasSym).flatMap(extractStrings).distinct
      val listExpr = Expr.ofList(names.map(Expr(_)))
      '{ $listExpr.toVector }
    }
    val allExpr = Expr.ofList(perField)
    '{ $allExpr.toVector }
