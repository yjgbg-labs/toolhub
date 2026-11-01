package groot.graaljs

import cats.effect.{IO, Resource}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat
import scala.util.control.NonFatal

/** Read and hash once at admission. Unchanged content shares its actual Source across attempts. */
private[groot] final class ScriptSources private:
  private case class Entry(digest: String, source: GraalSource)
  private var retained = Set.empty[Path]
  private var entries = Map.empty[Path, Entry]

  /** Configuration changes only evict unused cache entries; file I/O happens at submission. */
  def retain(paths: Set[Path]): IO[Unit] = IO.blocking { synchronized {
    retained = paths.map(_.toAbsolutePath.normalize())
    entries = entries.filter((path, _) => retained(path))
  } }

  def snapshot(path: Path): IO[Either[TaskError, GraalSource]] = IO.blocking { synchronized {
    val normalized = path.toAbsolutePath.normalize()
    try
      if !retained(normalized) then throw new IllegalStateException(s"Script is not configured: $normalized")
      if !Files.isRegularFile(normalized) then throw new java.io.IOException(s"Not a regular script file: $normalized")
      val input = Files.newInputStream(normalized)
      val bytes = try input.readNBytes(Limits.maxSourceBytes + 1) finally input.close()
      if bytes.length > Limits.maxSourceBytes then throw InvalidGraalTask("JavaScript source exceeds 1 MiB")
      val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
      entries.get(normalized).filter(_.digest == digest) match
        case Some(previous) => Right(previous.source)
        case None =>
          // Decode and build from exactly the bytes hashed above, never from a second file read.
          val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString
          val source = GraalSource.file(text, normalized)
          entries += normalized -> Entry(digest, source)
          Right(source)
    catch case NonFatal(error) =>
      entries -= normalized
      Left(TaskError("source_error", s"$normalized: ${error.getMessage}".take(8192)))
  } }

  private[graaljs] def cachedPaths: IO[Set[Path]] = IO.blocking { synchronized { entries.keySet } }

private[groot] object ScriptSources:
  def resource: Resource[IO, ScriptSources] =
    Resource.make(IO(new ScriptSources))(_.retain(Set.empty))
