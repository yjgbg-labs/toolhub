package sprout.cli

import _root_.sttp.client4.DefaultSyncBackend

/** The executable knows only HTTP/OpenAPI and delegates commands to the catalog. */
object NativeCli:
  def main(args: Array[String]): Unit =
    val exitCode = try
      val base = sys.env.get("SPROUT_BASE_URL").filter(_.nonEmpty)
        .getOrElse("http://127.0.0.1:" + sys.env.get("SPROUT_PORT").filter(_.nonEmpty).getOrElse("8080"))
      val backend = DefaultSyncBackend()
      try
        val transport = new HttpTransport(backend, base)
        // Scala Native preserves argv[0], including the name of an invoked symlink.
        val program = new java.io.File(scala.scalanative.runtime.filename).getName
        val result = CommandLine.run(program, args.toVector, transport, readJsonArgument)
        if result.nonEmpty then println(result)
        0
      finally backend.close()
    catch
      case error: Exception =>
        System.err.println(error.getMessage)
        1
    if exitCode != 0 then sys.exit(exitCode)

  private def readJsonArgument(value: String): io.circe.Json =
    val text = if value == "-" then scala.io.Source.fromInputStream(System.in, "UTF-8").mkString
      else if value.startsWith("@") then scala.util.Using.resource(scala.io.Source.fromFile(value.drop(1), "UTF-8"))(_.mkString)
      else value
    Catalog.parse(text)
