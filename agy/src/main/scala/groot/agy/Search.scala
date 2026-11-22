package groot.agy

import io.circe.Json

object Search:
  private val maxSchemaDepth = 12

  def query(catalog: Catalog, namespace: Option[String], keyword: Option[String]): String =
    val nsFilter = namespace.map(_.trim).filter(_.nonEmpty).map(_.stripPrefix("tools."))
    val kwTokens = keyword.map(_.trim).filter(_.nonEmpty).map(_.toLowerCase.split("\\s+").toVector.filter(_.nonEmpty)).getOrElse(Vector.empty)

    if nsFilter.isEmpty && kwTokens.isEmpty then
      renderIndex(catalog)
    else
      val matched = catalog.operations.filter { op =>
        val nsOk = nsFilter.forall { ns =>
          if ns == "root" then op.namespace == "root"
          else op.namespace == ns || op.namespace.startsWith(s"$ns.") || op.id == ns || op.id.startsWith(s"$ns.")
        }
        val kwOk = kwTokens.isEmpty || {
          val haystack = searchableText(catalog, op)
          kwTokens.forall(haystack.contains)
        }
        nsOk && kwOk
      }
      if matched.isEmpty then
        val hint = catalog.topLevelSummaries.map(_.name).mkString(", ")
        s"No matching Groot operations found (namespace=${nsFilter.getOrElse("*")}, keyword=${keyword.getOrElse("")}).\nAvailable namespaces: $hint"
      else
        renderOperations(catalog, matched)

  private def searchableText(catalog: Catalog, op: Operation): String =
    val sb = new StringBuilder
    sb.append(op.id).append(' ').append(op.path).append(' ').append(op.description).append(' ')
    catalog.groupDescriptions.get(op.namespace).foreach(d => sb.append(d).append(' '))
    catalog.groupDescriptions.get(op.topNamespace).foreach(d => sb.append(d).append(' '))
    op.parameters.foreach { p =>
      sb.append(p.name).append(' ').append(p.description).append(' ')
    }
    op.body.foreach { b =>
      sb.append(b.description).append(' ')
      collectSchemaTerms(catalog, b.schema, sb, Set.empty, 0)
    }
    sb.toString.toLowerCase

  private def collectSchemaTerms(catalog: Catalog, raw: Json, sb: StringBuilder, seen: Set[String], depth: Int): Unit =
    if depth > 6 then return
    val ref = Catalog.field(raw, "$ref").flatMap(_.asString)
    if ref.exists(seen.contains) then return
    val nextSeen = ref.fold(seen)(seen + _)
    val schema = scala.util.Try(catalog.resolve(raw)).getOrElse(raw)
    Catalog.field(schema, "description").flatMap(_.asString).foreach(d => sb.append(d).append(' '))
    Catalog.field(schema, "properties").flatMap(_.asObject).foreach { props =>
      props.toVector.foreach { (name, child) =>
        sb.append(name).append(' ')
        collectSchemaTerms(catalog, child, sb, nextSeen, depth + 1)
      }
    }
    Catalog.field(schema, "items").foreach(collectSchemaTerms(catalog, _, sb, nextSeen, depth + 1))
    Vector("anyOf", "oneOf", "allOf").foreach { k =>
      Catalog.field(schema, k).flatMap(_.asArray).foreach(_.foreach(collectSchemaTerms(catalog, _, sb, nextSeen, depth + 1)))
    }

  def renderIndex(catalog: Catalog): String =
    val sb = new StringBuilder
    sb.append(s"// ${catalog.title} GraalJS SDK index (${catalog.operations.size} operations)\n")
    sb.append("// Call search(namespace, keyword) to view full TypeScript signatures.\n\n")
    catalog.topLevelSummaries.foreach { summary =>
      sb.append(s"## ${summary.name} (${summary.operationCount}) — ${summary.description}\n")
      summary.operationIds.foreach { id =>
        val op = catalog.byId(id)
        val firstLine = op.description.linesIterator.nextOption().getOrElse("")
        sb.append(s"- tools.$id: $firstLine\n")
      }
      sb.append('\n')
    }
    sb.toString.trim

  def renderOperations(catalog: Catalog, ops: Vector[Operation]): String =
    val sb = new StringBuilder
    sb.append("// type SdkResponse<T> = { status: number; headers: Record<string, string>; body: T };\n")
    sb.append("// All tools.* calls return a Promise and reject with ApiError on non-2xx.\n\n")
    val grouped = ops.groupBy(_.namespace)
    val orderedNamespaces = grouped.keys.toVector.sortBy(ns => if ns == "root" then "" else ns)
    orderedNamespaces.foreach { ns =>
      catalog.groupDescriptions.get(ns).foreach { desc =>
        sb.append(s"// [$ns] $desc\n")
      }
      grouped(ns).foreach { op =>
        sb.append(renderOperation(catalog, op)).append("\n\n")
      }
    }
    sb.toString.trim

  private def renderOperation(catalog: Catalog, op: Operation): String =
    val sb = new StringBuilder
    val descLines = op.description.linesIterator.toVector.filter(_.nonEmpty)
    if descLines.nonEmpty then
      sb.append("/**\n")
      descLines.foreach(line => sb.append(" * ").append(line).append('\n'))
      sb.append(" */\n")
    val paramSig =
      if op.parameters.isEmpty && op.body.isEmpty then ""
      else if op.bodyOnly then
        val b = op.body.get
        val opt = if b.required then "" else "?"
        val ts = renderType(catalog, b.schema, Set.empty, 0)
        s"input$opt: $ts"
      else
        val allOptional = op.parameters.forall(!_.required) && op.body.forall(!_.required)
        val opt = if allOptional then "?" else ""
        val fields = Vector.newBuilder[String]
        op.parameters.foreach { p =>
          val pOpt = if p.required then "" else "?"
          val pType = renderType(catalog, p.schema, Set.empty, 1)
          val comment = formatInlineComment(p.description)
          fields += s"  ${formatPropName(p.name)}$pOpt: $pType;$comment"
        }
        op.body.foreach { b =>
          val bOpt = if b.required then "" else "?"
          val bType = renderType(catalog, b.schema, Set.empty, 1)
          val comment = formatInlineComment(b.description)
          fields += s"  body$bOpt: $bType;$comment"
        }
        s"input$opt: {\n${fields.result().mkString("\n")}\n}"
    val respType =
      if op.responseSchemas.isEmpty then "unknown"
      else op.responseSchemas.map(renderType(catalog, _, Set.empty, 0)).distinct.mkString(" | ")
    sb.append(s"tools.${op.id}($paramSig): Promise<SdkResponse<$respType>>")
    sb.toString

  def renderType(catalog: Catalog, raw: Json, seen: Set[String], indent: Int): String =
    if indent > maxSchemaDepth then "unknown"
    else
      val ref = Catalog.field(raw, "$ref").flatMap(_.asString)
      if ref.exists(seen.contains) then ref.get.split('/').lastOption.getOrElse("unknown")
      else
        val nextSeen = ref.fold(seen)(seen + _)
        val schema = scala.util.Try(catalog.resolve(raw)).getOrElse(raw)
        val special =
          Catalog.field(schema, "const").map(_.noSpaces)
            .orElse(Catalog.field(schema, "enum").flatMap(_.asArray).filter(_.nonEmpty).map(_.map(_.noSpaces).mkString(" | ")))
            .orElse(
              Vector("oneOf", "anyOf").iterator
                .flatMap(k => Catalog.field(schema, k).flatMap(_.asArray).filter(_.nonEmpty))
                .nextOption()
                .map(_.map(renderType(catalog, _, nextSeen, indent)).distinct.mkString(" | "))
            )
            .orElse(
              Catalog.field(schema, "allOf").flatMap(_.asArray).filter(_.nonEmpty)
                .map(_.map(renderType(catalog, _, nextSeen, indent)).distinct.mkString(" & "))
            )

        special.getOrElse {
          val rawTypes = Catalog.types(schema)
          val nullable = rawTypes.contains("null")
          val nonNull = rawTypes.filterNot(_ == "null")
          val base =
            if nonNull.isEmpty then
              if nullable then "null"
              else if Catalog.field(schema, "properties").nonEmpty then renderObject(catalog, schema, nextSeen, indent)
              else "unknown"
            else
              nonNull.map {
                case "string" => "string"
                case "integer" | "number" => "number"
                case "boolean" => "boolean"
                case "array" =>
                  val itemType = Catalog.field(schema, "items").map(renderType(catalog, _, nextSeen, indent)).getOrElse("unknown")
                  if itemType.contains(" | ") || itemType.contains(" & ") then s"($itemType)[]" else s"${itemType}[]"
                case "object" => renderObject(catalog, schema, nextSeen, indent)
                case other => other
              }.distinct.mkString(" | ")

          if nullable && base != "null" then s"$base | null" else base
        }

  private def renderObject(catalog: Catalog, schema: Json, seen: Set[String], indent: Int): String =
    val props = Catalog.field(schema, "properties").flatMap(_.asObject).map(_.toVector).getOrElse(Vector.empty)
    val required = Catalog.array(schema, "required").flatMap(_.asString).toSet
    val addProps = Catalog.field(schema, "additionalProperties")
    if props.isEmpty then
      addProps match
        case Some(Json.False) => "Record<string, never>"
        case Some(child) if child.isObject => s"Record<string, ${renderType(catalog, child, seen, indent)}>"
        case _ => "Record<string, unknown>"
    else
      val pad = "  " * (indent + 1)
      val closePad = "  " * indent
      val lines = props.map { (name, child) =>
        val resolved = scala.util.Try(catalog.resolve(child)).getOrElse(child)
        val desc = Catalog.optionalText(resolved, "description")
        val opt = if required(name) then "" else "?"
        val ts = renderType(catalog, child, seen, indent + 1)
        s"$pad${formatPropName(name)}$opt: $ts;${formatInlineComment(desc)}"
      }
      val extra = addProps match
        case Some(child) if child.isObject => Vector(s"$pad[key: string]: ${renderType(catalog, child, seen, indent + 1)};")
        case _ => Vector.empty
      s"{\n${(lines ++ extra).mkString("\n")}\n$closePad}"

  private def formatPropName(name: String): String =
    if name.matches("[A-Za-z_$][A-Za-z0-9_$]*") then name else Json.fromString(name).noSpaces

  private def formatInlineComment(description: String): String =
    val first = description.linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("")
    if first.isEmpty then "" else s" // $first"
