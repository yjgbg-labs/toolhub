package sprout.sdk.tools

import scala.scalajs.js.annotation.JSExportTopLevel
import io.circe.Json
import sprout.sdk.Catalog
import sprout.sdk.generator.Generator

/** Host I/O stays in the launcher; this bridge also works in browser JS hosts. */
object GeneratorJs:
  @JSExportTopLevel("generate")
  def generate(schema: String, packageName: String): String =
    Json.fromFields(Generator.generate(Catalog.parse(schema), packageName).map((name, source) => name -> Json.fromString(source))).noSpaces
