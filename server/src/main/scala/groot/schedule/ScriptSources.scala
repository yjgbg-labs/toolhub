package groot.schedule

import cats.effect.{IO, Resource}
import groot.graaljs.JavaScript
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.{HexFormat, LinkedHashMap}
import org.graalvm.polyglot.Source
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[schedule] case class ScriptReadError(path: Path, cause: Throwable)
    extends RuntimeException(s"$path: ${cause.getMessage}", cause)

/** Scheduler-owned, access-ordered SHA-256 -> native Source cache, shared across file paths. */
private[schedule] final class ScriptSources private (maxEntries: Int):
  private val cache = new LinkedHashMap[String, Source](16, 0.75f, true)

  def snapshot(path: Path): IO[Source] = IO.blocking { synchronized {
    val normalized = path.toAbsolutePath.normalize()
    try
      if !Files.isRegularFile(normalized) then throw new java.io.IOException("Not a regular script file")
      val input = Files.newInputStream(normalized)
      val bytes = try input.readNBytes(JavaScript.maxSourceBytes + 1) finally input.close()
      if bytes.length > JavaScript.maxSourceBytes then throw new java.io.IOException("JavaScript source exceeds 1 MiB")
      val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
      val existing = cache.get(digest)
      if existing != null then existing
      else
        // Construct from exactly the bytes hashed above, never from a second file read.
        val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString
        val source = JavaScript.source(text, s"sha256-$digest.graal.js", cached = true)
        cache.put(digest, source)
        if cache.size() > maxEntries then
          val oldest = cache.keySet().iterator()
          oldest.next()
          oldest.remove()
        source
    catch case NonFatal(error) => throw ScriptReadError(normalized, error)
  } }

  private[schedule] def cachedHashes: IO[List[String]] = IO.blocking { synchronized { cache.keySet().asScala.toList } }
  private def clear: IO[Unit] = IO.blocking { synchronized { cache.clear() } }

private[schedule] object ScriptSources:
  def resource(maxEntries: Int = 128): Resource[IO, ScriptSources] =
    require(maxEntries > 0)
    Resource.make(IO(new ScriptSources(maxEntries)))(_.clear)
