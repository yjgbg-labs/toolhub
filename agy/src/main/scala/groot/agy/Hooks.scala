package groot.agy

import io.circe.{Json, JsonObject}

object Hooks:
  def sessionStart(loader: CatalogLoader, baseUrl: String): Json =
    val message = scala.util.Try(loader.load(forceRefresh = true)) match
      case scala.util.Success(catalog) =>
        val sb = new StringBuilder
        sb.append("<groot_codemode>\n")
        sb.append(s"Groot server connected ($baseUrl, ${catalog.operations.size} GraalJS operations).\n")
        sb.append("Use MCP server `groot_codemode`: call `search(namespace, keyword)` to inspect TypeScript signatures, then `execute(graalScript, timeout)` to run JS against `tools.*`.\n\n")
        sb.append("Available namespaces (`tools.<namespace>.*`):\n")
        catalog.topLevelSummaries.foreach { summary =>
          val subHint =
            if summary.name == "root" then s" (commands: ${summary.operationIds.map(id => s"tools.$id").mkString(", ")})"
            else if summary.subNamespaces.nonEmpty then s" (sub: ${summary.subNamespaces.map(_.stripPrefix(s"${summary.name}.")).mkString(", ")})"
            else ""
          sb.append(s"- ${summary.name} (${summary.operationCount} ops)$subHint: ${summary.description}\n")
        }
        sb.append("</groot_codemode>")
        sb.toString
      case scala.util.Failure(error) =>
        s"<groot_codemode>\nGroot server ($baseUrl) is currently unreachable (${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}). MCP `groot` will reconnect on demand once `groot-http.service` is running.\n</groot_codemode>"

    Json.obj(
      "injectSteps" -> Json.arr(
        Json.obj(
          "systemMessage" -> Json.obj(
            "systemMessage" -> Json.fromString(message)
          )
        )
      )
    )

  /** Record per-call session context (`conversationId`, `cwd`, `workspacePaths`) in the sideband store without rewriting tool arguments. */
  def preToolUse(inputText: String, ctxDir: java.nio.file.Path = ContextStore.defaultDir): Json =
    val allowOnly = Json.obj("decision" -> Json.fromString("allow"))
    val parsed = for
      text <- Option(inputText).map(_.trim).filter(_.nonEmpty)
      payload <- scala.util.Try(Catalog.parse(text)).toOption
      toolCall <- Catalog.field(payload, "toolCall").flatMap(_.asObject)
    yield (payload, toolCall)

    parsed.foreach { (payload, toolCall) =>
      val toolName = toolCall("name").flatMap(_.asString).getOrElse("")
      val argsObj = toolCall("args").flatMap(_.asObject).getOrElse(JsonObject.empty)

      val ctxFields = Vector(
        "conversationId" -> Catalog.field(payload, "conversationId"),
        "cwd" -> Catalog.field(payload, "cwd"),
        "workspacePaths" -> Catalog.field(payload, "workspacePaths")
      ).collect { case (k, Some(v)) if !v.isNull => k -> v }

      if ctxFields.nonEmpty then
        val ctxJson = Json.fromJsonObject(JsonObject.fromIterable(ctxFields))
        val serverName = argsObj("ServerName").flatMap(_.asString).getOrElse("")
        val isGrootServer = serverName == "groot" || serverName == "groot_groot" || serverName.startsWith("groot_")

        val scriptOpt =
          if toolName == "call_mcp_tool" && isGrootServer && argsObj("ToolName").flatMap(_.asString).contains("execute") then
            argsObj("Arguments").flatMap(_.asObject).flatMap(_("graalScript")).flatMap(_.asString)
          else if toolName != "call_mcp_tool" && toolName.toLowerCase.contains("groot") && toolName.toLowerCase.contains("execute") then
            argsObj("graalScript").flatMap(_.asString)
          else
            None

        scriptOpt.foreach(ContextStore.put(_, ctxJson, ctxDir))
    }
    allowOnly
