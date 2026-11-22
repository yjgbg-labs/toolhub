package groot.agy

import io.circe.{Json, JsonObject}
import java.io.{BufferedReader, PrintStream}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

final class McpServer(loader: CatalogLoader, executor: Executor):
  private val inFlightTasks = new ConcurrentHashMap[String, String]()
  private val cancelledRequests = new ConcurrentHashMap[String, java.lang.Boolean]()

  private val toolsDefinition: Json = Json.arr(
    Json.obj(
      "name" -> Json.fromString("search"),
      "description" -> Json.fromString(
        "Search Groot OpenAPI operations exposed to GraalJS and return compact TypeScript signatures for `tools.*`. " +
          "Omit both parameters to list the full operation index."
      ),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "namespace" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Optional namespace prefix filter, e.g. 'chrome', 'chrome.page', 'shell', 'fs', or 'root'")
          ),
          "keyword" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Optional case-insensitive space-separated keywords matching operation names, descriptions, or parameters")
          )
        ),
        "additionalProperties" -> Json.False
      )
    ),
    Json.obj(
      "name" -> Json.fromString("execute"),
      "description" -> Json.fromString(
        "Execute a GraalJS script on Groot Server with `tools.*` bound to all GraalJS-exposed commands and `ctx` bound to the active session context (`conversationId`, `cwd`, `workspacePaths`). " +
          "Supports top-level `await`, `return` (automatically printed), and `console.log`."
      ),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "code" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("JavaScript async body to run inside Groot Server's isolated GraalJS context")
          ),
          "timeout" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Optional wall-clock timeout such as '30s' or '2m' (defaults to '30s')")
          )
        ),
        "required" -> Json.arr(Json.fromString("code")),
        "additionalProperties" -> Json.False
      )
    )
  )

  def serve(in: BufferedReader, out: PrintStream): Unit =
    val writeLock = new AnyRef
    val closed = new AtomicBoolean(false)

    def emit(message: Json): Unit = writeLock.synchronized {
      if !closed.get() then
        out.println(message.noSpaces)
        out.flush()
    }

    var line = in.readLine()
    while line != null do
      val trimmed = line.trim
      if trimmed.nonEmpty then
        io.circe.jawn.parse(trimmed) match
          case Left(error) =>
            emit(jsonRpcError(Json.Null, -32700, s"Parse error: ${error.message}"))
          case Right(message) =>
            dispatch(message, emit)
      line = in.readLine()

    closed.set(true)
    // Cancel any remaining server tasks when stdin closes.
    val iter = inFlightTasks.values().iterator()
    while iter.hasNext do executor.cancel(iter.next())

  private def dispatch(message: Json, emit: Json => Unit): Unit =
    val method = Catalog.field(message, "method").flatMap(_.asString)
    val idOpt = Catalog.field(message, "id")
    val params = Catalog.field(message, "params").getOrElse(Json.obj())

    method match
      case None => () // Ignore stray responses
      case Some("notifications/initialized") => ()
      case Some("notifications/cancelled") =>
        Catalog.field(params, "requestId").foreach { reqId =>
          val key = reqId.noSpaces
          cancelledRequests.put(key, java.lang.Boolean.TRUE)
          val taskId = inFlightTasks.get(key)
          if taskId != null then
            val t = new Thread(() => executor.cancel(taskId))
            t.setDaemon(true)
            t.start()
        }
      case Some(m) =>
        idOpt match
          case None => () // Unknown notification: no response per JSON-RPC 2.0
          case Some(id) =>
            m match
              case "initialize" =>
                val protocolVersion = Catalog.field(params, "protocolVersion").flatMap(_.asString).getOrElse("2025-03-26")
                emit(jsonRpcResult(id, Json.obj(
                  "protocolVersion" -> Json.fromString(protocolVersion),
                  "capabilities" -> Json.obj("tools" -> Json.obj()),
                  "serverInfo" -> Json.obj(
                    "name" -> Json.fromString("groot"),
                    "version" -> Json.fromString("1.0.0")
                  )
                )))
              case "ping" =>
                emit(jsonRpcResult(id, Json.obj()))
              case "tools/list" =>
                emit(jsonRpcResult(id, Json.obj("tools" -> toolsDefinition)))
              case "tools/call" =>
                // Run tool calls off the stdin reader loop so parallel calls and cancellations stay responsive.
                val worker = new Thread(() => {
                  val response = handleToolCall(id, params)
                  emit(response)
                })
                worker.setDaemon(true)
                worker.start()
              case other =>
                emit(jsonRpcError(id, -32601, s"Method not found: $other"))

  def handleToolCall(id: Json, params: Json): Json =
    val reqKey = id.noSpaces
    val toolName = Catalog.field(params, "name").flatMap(_.asString).getOrElse("")
    val args = Catalog.field(params, "arguments").flatMap(_.asObject).getOrElse(JsonObject.empty)

    try
      toolName match
        case "search" =>
          val namespace = args("namespace").flatMap(_.asString)
          val keyword = args("keyword").flatMap(_.asString)
          val catalog = loader.load()
          val text = Search.query(catalog, namespace, keyword)
          jsonRpcResult(id, toolSuccess(text))

        case "execute" =>
          val script = args("code").flatMap(_.asString)
            .getOrElse(throw new IllegalArgumentException("Missing required string argument: code"))
          val timeout = args("timeout").flatMap(_.asString)
          val context = ContextStore.take(script)
          val result = executor.run(
            script = script,
            timeout = timeout,
            context = context,
            onTaskStarted = taskId => {
              inFlightTasks.put(reqKey, taskId)
              if cancelledRequests.containsKey(reqKey) then executor.cancel(taskId)
            }
          )
          val text = Executor.formatResult(result)
          jsonRpcResult(id, if result.isError then toolError(text) else toolSuccess(text))

        case other =>
          jsonRpcResult(id, toolError(s"Unknown tool: $other"))
    catch
      case error: Throwable =>
        jsonRpcResult(id, toolError(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
    finally
      inFlightTasks.remove(reqKey)
      cancelledRequests.remove(reqKey)

  private def toolSuccess(text: String): Json = Json.obj(
    "content" -> Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text)))
  )

  private def toolError(text: String): Json = Json.obj(
    "isError" -> Json.True,
    "content" -> Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text)))
  )

  private def jsonRpcResult(id: Json, result: Json): Json = Json.obj(
    "jsonrpc" -> Json.fromString("2.0"),
    "id" -> id,
    "result" -> result
  )

  private def jsonRpcError(id: Json, code: Int, message: String): Json = Json.obj(
    "jsonrpc" -> Json.fromString("2.0"),
    "id" -> id,
    "error" -> Json.obj(
      "code" -> Json.fromInt(code),
      "message" -> Json.fromString(message)
    )
  )
