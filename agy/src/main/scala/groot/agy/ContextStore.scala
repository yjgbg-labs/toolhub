package groot.agy

import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.util.Try

/**
 * Ephemeral sideband store passing session context from PreToolUse hook to MCP `execute`
 * keyed by a hash of `graalScript`, avoiding AGY `overwrite` UI/schema pollution.
 */
object ContextStore:
  private val staleTtlMs = 5L * 60L * 1000L

  def defaultDir: Path =
    val path = sys.env.get("GROOT_AGY_CTX_DIR").filter(_.nonEmpty)
      .orElse(sys.env.get("XDG_RUNTIME_DIR").filter(_.nonEmpty).map(d => s"$d/groot-agy-ctx"))
      .getOrElse(s"${System.getProperty("java.io.tmpdir", "/tmp")}/groot-agy-ctx-${sys.env.getOrElse("USER", "default")}")
    Paths.get(path)

  /** Pure-Scala 128-bit FNV-1a hex digest (zero JCA provider dependency on Scala Native). */
  def keyFor(script: String): String =
    val bytes = script.getBytes(UTF_8)
    var h1 = 0xcbf29ce484222325L
    var h2 = 0x84222325cbf29ce4L
    val prime = 0x100000001b3L
    var i = 0
    while i < bytes.length do
      val b = bytes(i) & 0xffL
      h1 = (h1 ^ b) * prime
      h2 = (h2 ^ ((b + i) & 0xffL)) * prime
      i += 1
    f"$h1%016x$h2%016x"

  def put(script: String, ctx: Json, dir: Path = defaultDir): Unit =
    Try {
      Files.createDirectories(dir)
      pruneStale(dir)
      val key = keyFor(script)
      val target = dir.resolve(s"$key.json")
      val tmp = dir.resolve(s"$key.${System.nanoTime()}.tmp")
      Files.write(tmp, ctx.noSpaces.getBytes(UTF_8))
      try Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      catch case _: Throwable => Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
    ()

  def take(script: String, dir: Path = defaultDir): Option[Json] =
    val target = dir.resolve(s"${keyFor(script)}.json")
    if !Files.exists(target) then None
    else
      val parsed = Try {
        val text = new String(Files.readAllBytes(target), UTF_8)
        Catalog.parse(text)
      }.toOption.filter(_.isObject)
      Try(Files.deleteIfExists(target))
      parsed

  private def pruneStale(dir: Path): Unit =
    val cutoff = System.currentTimeMillis() - staleTtlMs
    Try {
      val stream = Files.list(dir)
      try
        val it = stream.iterator()
        while it.hasNext do
          val entry = it.next()
          val name = entry.getFileName.toString
          if name.endsWith(".json") || name.endsWith(".tmp") then
            val mtime = Try(Files.getLastModifiedTime(entry).toMillis).getOrElse(Long.MaxValue)
            if mtime < cutoff then Try(Files.deleteIfExists(entry))
      finally stream.close()
    }
    ()
