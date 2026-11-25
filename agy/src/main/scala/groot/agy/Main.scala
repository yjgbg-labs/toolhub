package groot.agy

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8

object Main:
  def main(args: Array[String]): Unit =
    val baseUrl = resolveBaseUrl()
    val headers = sys.env.get("GROOT_TOKEN").filter(_.nonEmpty).map(t => Vector("Authorization" -> s"Bearer $t")).getOrElse(Vector.empty)
    val schemaPath = sys.env.get("GROOT_OPENAPI_PATH").filter(_.nonEmpty).getOrElse("/openapi.json")

    args.toList match
      case List("mcp") =>
        val transport = new SttpHttpTransport(baseUrl, headers)
        val loader = new CatalogLoader(transport, schemaPath)
        val executor = new Executor(transport)
        val server = new McpServer(loader, executor)
        val reader = new BufferedReader(new InputStreamReader(System.in, UTF_8))
        server.serve(reader, System.out)

      case List("hook", "session-start") =>
        // Drain stdin per hook contract, then fetch OpenAPI with a short timeout so session startup never stalls.
        drainStdin()
        val transport = new SttpHttpTransport(baseUrl, headers, timeoutMs = 2500L)
        val loader = new CatalogLoader(transport, schemaPath)
        println(Hooks.sessionStart(loader, baseUrl).noSpaces)

      case List("hook", "pre-tool-use") =>
        val input = readAllStdin()
        println(Hooks.preToolUse(input).noSpaces)

      case List("--version") | List("version") =>
        println("groot-agy 1.0.0")

      case _ =>
        System.err.println("Usage: groot-agy <mcp | hook session-start | hook pre-tool-use | --version>")
        sys.exit(2)

  private def resolveBaseUrl(): String =
    sys.env.get("GROOT_BASE_URL").map(_.trim).filter(_.nonEmpty).getOrElse {
      val port = sys.env.get("GROOT_PORT").map(_.trim).filter(_.nonEmpty).getOrElse("18080")
      s"http://127.0.0.1:$port"
    }

  private def readAllStdin(): String =
    scala.io.Source.fromInputStream(System.in, "UTF-8").mkString

  private def drainStdin(): Unit =
    scala.util.Try(readAllStdin())
    ()
