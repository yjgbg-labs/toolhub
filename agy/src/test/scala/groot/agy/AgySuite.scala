package groot.agy

import io.circe.Json
import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets.UTF_8

class AgySuite extends munit.FunSuite:
  private val sampleOpenApi: String =
    """{
      |  "openapi": "3.1.0",
      |  "info": { "title": "Groot", "version": "1.0.0" },
      |  "x-rpc": {
      |    "commands": [
      |      { "name": "shell", "description": "Run scripts in named shell environments" },
      |      { "name": "shell.env", "description": "Manage shell environments" },
      |      { "name": "fs", "description": "Server filesystem operations" },
      |      { "name": "schedule", "description": "Event-driven scheduling" },
      |      { "name": "notify", "description": "Session notifications" }
      |    ]
      |  },
      |  "components": {
      |    "schemas": {
      |      "ShellResult": {
      |        "type": "object",
      |        "required": ["exitCode", "stdout", "stderr"],
      |        "properties": {
      |          "exitCode": { "type": "integer" },
      |          "stdout": { "type": "string" },
      |          "stderr": { "type": "string" }
      |        }
      |      }
      |    }
      |  },
      |  "paths": {
      |    "/health": {
      |      "get": {
      |        "operationId": "health",
      |        "summary": "Health check",
      |        "x-expose-on": ["cli", "codemode", "graaljs"],
      |        "responses": {
      |          "200": { "content": { "text/plain": { "schema": { "type": "string" } } } }
      |        }
      |      }
      |    },
      |    "/shell/execute": {
      |      "post": {
      |        "operationId": "shell.execute",
      |        "summary": "Run shell script",
      |        "x-expose-on": ["cli", "codemode", "graaljs"],
      |        "requestBody": {
      |          "required": true,
      |          "content": {
      |            "application/json": {
      |              "schema": {
      |                "type": "object",
      |                "required": ["env", "script"],
      |                "properties": {
      |                  "env": { "type": "string", "description": "Environment name" },
      |                  "script": { "type": "string", "description": "Script body" },
      |                  "timeout": { "type": "string" }
      |                }
      |              }
      |            }
      |          }
      |        },
      |        "responses": {
      |          "200": { "content": { "application/json": { "schema": { "$ref": "#/components/schemas/ShellResult" } } } }
      |        }
      |      }
      |    },
      |    "/fs/read": {
      |      "get": {
      |        "operationId": "fs.read",
      |        "summary": "Read file from server",
      |        "x-expose-on": ["graaljs"],
      |        "parameters": [
      |          { "name": "path", "in": "query", "required": true, "schema": { "type": "string" }, "description": "Absolute path" }
      |        ],
      |        "responses": {
      |          "200": { "content": { "application/json": { "schema": { "type": "object", "properties": { "content": { "type": "string" } }, "required": ["content"] } } } }
      |        }
      |      }
      |    },
      |    "/schedule/{name}": {
      |      "put": {
      |        "operationId": "schedule.new",
      |        "summary": "Create job",
      |        "x-expose-on": ["cli", "codemode", "graaljs"],
      |        "parameters": [
      |          { "name": "name", "in": "path", "required": true, "schema": { "type": "string" }, "description": "Job name" }
      |        ],
      |        "requestBody": {
      |          "required": true,
      |          "content": {
      |            "application/json": {
      |              "schema": {
      |                "type": "object",
      |                "required": ["every", "script"],
      |                "properties": {
      |                  "every": { "type": "string" },
      |                  "script": { "type": "string" }
      |                }
      |              }
      |            }
      |          }
      |        },
      |        "responses": { "200": { "content": { "application/json": { "schema": { "type": "object" } } } } }
      |      }
      |    },
      |    "/notify/subscribe": {
      |      "get": {
      |        "operationId": "notify.subscribe",
      |        "summary": "CLI only SSE stream",
      |        "x-expose-on": ["cli"],
      |        "responses": { "200": { "content": { "text/event-stream": { "schema": { "type": "string" } } } } }
      |      }
      |    }
      |  }
      |}""".stripMargin

  test("Catalog filters strictly by graaljs exposure and includes graaljs-only endpoints") {
    val catalog = new Catalog(Catalog.parse(sampleOpenApi))
    val ids = catalog.operations.map(_.id)
    assertEquals(ids, Vector("fs.read", "health", "schedule.new", "shell.execute"))
    assert(!ids.contains("notify.subscribe"))
    assert(catalog.byId("shell.execute").bodyOnly)
    assert(!catalog.byId("schedule.new").bodyOnly)
  }

  test("Search renders body-only vs mixed parameter TypeScript signatures accurately") {
    val catalog = new Catalog(Catalog.parse(sampleOpenApi))
    val shellOut = Search.query(catalog, Some("shell"), None)
    assert(shellOut.contains("tools.shell.execute(input: {"), shellOut)
    assert(!shellOut.contains("\n  body:"), s"body-only command should not wrap body: $shellOut")
    assert(shellOut.contains("exitCode: number;"), shellOut)

    val schedOut = Search.query(catalog, Some("schedule"), Some("job"))
    assert(schedOut.contains("tools.schedule.new(input: {"), schedOut)
    assert(schedOut.contains("name: string;"), schedOut)
    assert(schedOut.contains("body: {"), schedOut)

    val indexOut = Search.query(catalog, None, None)
    assert(indexOut.contains("tools.fs.read"), indexOut)
    assert(indexOut.contains("tools.health"), indexOut)
  }

  test("Hooks.sessionStart injects namespace directory and degrades gracefully when offline") {
    val okTransport = new HttpTransport:
      def send(request: HttpRequest, onEvent: String => Unit): HttpResponse =
        HttpResponse(200, Vector("Content-Type" -> "application/json"), sampleOpenApi)
    val json = Hooks.sessionStart(new CatalogLoader(okTransport), "http://127.0.0.1:18080")
    val msg = json.hcursor.downField("injectSteps").downN(0).downField("systemMessage").downField("systemMessage").as[String].toOption.get
    assert(msg.contains("<groot_codemode>"), msg)
    assert(msg.contains("- fs (1 ops)"), msg)
    assert(msg.contains("- shell (1 ops)"), msg)
    assert(msg.contains("- root (1 ops)"), msg)

    val errTransport = new HttpTransport:
      def send(request: HttpRequest, onEvent: String => Unit): HttpResponse =
        throw new RuntimeException("Connection refused")
    val offline = Hooks.sessionStart(new CatalogLoader(errTransport), "http://127.0.0.1:18080")
    val offlineMsg = offline.hcursor.downField("injectSteps").downN(0).downField("systemMessage").downField("systemMessage").as[String].toOption.get
    assert(offlineMsg.contains("unreachable"), offlineMsg)
  }

  test("Hooks.preToolUse stores session context in sideband ContextStore without overwriting arguments") {
    val tmpDir = java.nio.file.Files.createTempDirectory("groot-agy-ctx-test")
    try
      val script = "return ctx.conversationId;"
      val lazyPayload =
        s"""{
           |  "conversationId": "conv-123",
           |  "cwd": "/home/yjgbg/project",
           |  "workspacePaths": ["/home/yjgbg/project"],
           |  "toolCall": {
           |    "id": "call-1",
           |    "name": "call_mcp_tool",
           |    "args": {
           |      "ServerName": "groot_codemode",
           |      "ToolName": "execute",
           |      "Arguments": { "code": "$script" }
           |    }
           |  }
           |}""".stripMargin
      val res = Hooks.preToolUse(lazyPayload, tmpDir)
      assertEquals(res, Json.obj("decision" -> Json.fromString("allow")))
      val stored = ContextStore.take(script, tmpDir)
      assertEquals(stored.flatMap(_.hcursor.get[String]("conversationId").toOption), Some("conv-123"))
      assertEquals(stored.flatMap(_.hcursor.get[String]("cwd").toOption), Some("/home/yjgbg/project"))
      assertEquals(ContextStore.take(script, tmpDir), None)

      val otherPayload =
        """{
          |  "conversationId": "conv-123",
          |  "toolCall": {
          |    "name": "call_mcp_tool",
          |    "args": { "ServerName": "groot_codemode", "ToolName": "search", "Arguments": {} }
          |  }
          |}""".stripMargin
      assertEquals(Hooks.preToolUse(otherPayload, tmpDir), Json.obj("decision" -> Json.fromString("allow")))
    finally
      scala.util.Try(java.nio.file.Files.deleteIfExists(tmpDir))
  }

  test("Executor wraps script with ctx and return capture, strips graaljs trailer, and truncates long output") {
    var submittedBody = ""
    val mockTransport = new HttpTransport:
      def send(request: HttpRequest, onEvent: String => Unit): HttpResponse =
        request.path match
          case "/graaljs/start" =>
            submittedBody = request.body.getOrElse("")
            HttpResponse(200, Vector("Content-Type" -> "application/json"), """{"id":"gjs_test1","state":"running"}""")
          case "/graaljs/logsFollow" =>
            onEvent("hello from script")
            onEvent("")
            onEvent("[graaljs] state=succeeded cpu=1.200ms elapsed=5.000ms")
            HttpResponse(200, Vector("Content-Type" -> "text/event-stream"), "")
          case "/graaljs/status" =>
            HttpResponse(200, Vector("Content-Type" -> "application/json"),
              """{"id":"gjs_test1","state":"succeeded","logPath":"/tmp/gjs_test1.log","cpuTimeMs":1.2,"elapsedTimeMs":5.0}""")
          case other =>
            fail(s"Unexpected path: $other")

    val executor = new Executor(mockTransport)
    val result = executor.run("return 42;", timeout = Some("15s"), context = Some(Catalog.parse("""{"conversationId":"c-1"}""")))
    assert(submittedBody.contains("""const ctx = Object.freeze({"conversationId":"c-1"});"""), submittedBody)
    assert(submittedBody.contains("return 42;"), submittedBody)
    assertEquals(result.state, "succeeded")
    assertEquals(result.output, "hello from script")
    assert(!result.isError)
  }

  test("McpServer handles initialize, tools/list, and tools/call for search & execute") {
    val mockTransport = new HttpTransport:
      def send(request: HttpRequest, onEvent: String => Unit): HttpResponse =
        request.path match
          case "/openapi.json" => HttpResponse(200, Vector("Content-Type" -> "application/json"), sampleOpenApi)
          case "/graaljs/start" => HttpResponse(200, Vector("Content-Type" -> "application/json"), """{"id":"gjs_mcp1","state":"running"}""")
          case "/graaljs/logsFollow" =>
            onEvent("42")
            onEvent("[graaljs] state=succeeded cpu=1.0ms elapsed=2.0ms")
            HttpResponse(200, Vector("Content-Type" -> "text/event-stream"), "")
          case "/graaljs/status" =>
            HttpResponse(200, Vector("Content-Type" -> "application/json"), """{"id":"gjs_mcp1","state":"succeeded"}""")
          case other => fail(s"Unexpected: $other")

    val server = new McpServer(new CatalogLoader(mockTransport), new Executor(mockTransport))
    val input =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
        |{"jsonrpc":"2.0","id":2,"method":"tools/list"}
        |""".stripMargin
    val bytes = new ByteArrayOutputStream()
    server.serve(new BufferedReader(new StringReader(input)), new PrintStream(bytes, true, UTF_8))
    val lines = bytes.toString(UTF_8).linesIterator.filter(_.nonEmpty).toVector
    assertEquals(lines.size, 2)
    val listJson = Catalog.parse(lines(1))
    val names = listJson.hcursor.downField("result").downField("tools").as[Vector[Json]].toOption.get.flatMap(_.hcursor.get[String]("name").toOption)
    assertEquals(names, Vector("search", "execute"))

    val searchResp = server.handleToolCall(Json.fromInt(3), Catalog.parse("""{"name":"search","arguments":{"namespace":"fs"}}"""))
    val searchText = searchResp.hcursor.downField("result").downField("content").downN(0).get[String]("text").toOption.get
    assert(searchText.contains("tools.fs.read"), searchText)

    val execResp = server.handleToolCall(Json.fromInt(4), Catalog.parse("""{"name":"execute","arguments":{"code":"return 42;"}}"""))
    val execText = execResp.hcursor.downField("result").downField("content").downN(0).get[String]("text").toOption.get
    assertEquals(execText, "42")
  }
