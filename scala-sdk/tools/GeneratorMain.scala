package sprout.sdk.tools

import java.nio.file.{Files, Path, StandardCopyOption}
import sprout.sdk.Catalog
import sprout.sdk.generator.Generator

/** JVM/Native file launcher. HTTP discovery is available separately through sdk-core. */
object GeneratorMain:
  def main(args: Array[String]): Unit =
    require(args.length == 3, "Usage: generator <openapi.json> <package> <output-directory>")
    val files = Generator.generate(Catalog.parse(Files.readString(Path.of(args(0)))), args(1))
    val output = Path.of(args(2))
    files.keys.foreach { name =>
      val path = output.resolve(name)
      require(!Files.exists(path) || Files.readString(path).startsWith(Generator.header), s"Refusing to overwrite handwritten file: $path")
    }
    Files.createDirectories(output)
    files.foreach { (name, source) =>
      val temporary = Files.createTempFile(output, name, ".tmp")
      try
        Files.writeString(temporary, source)
        Files.move(temporary, output.resolve(name), StandardCopyOption.REPLACE_EXISTING)
      finally Files.deleteIfExists(temporary)
    }
    println(s"Generated ${files.size} Scala files in $output")
