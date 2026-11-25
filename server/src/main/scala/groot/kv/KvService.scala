package groot.kv

import cats.effect.{IO, Resource}
import framework.model.JsonModel
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import scala.util.Using

/** Shared across tasks; every operation reads/commits a single JSONL file atomically before returning. */
final class KvService private (defaultDir: Path, config: IO[KvConfig]):
  private def withPath[A](key: String)(action: Path => A): IO[A] =
    for
      _ <- IO {
        if key.isEmpty || key.contains('\u0000') || key.codePointCount(0, key.length) > 128 then
          throw InvalidKv("Key must contain 1 to 128 characters without NUL bytes")
      }
      cfg <- config
      target = KvService.resolveFile(defaultDir, cfg.file)
      lock = KvService.lockFor(target)
      result <- IO.uncancelable(_ => IO.blocking {
        lock.lock()
        try action(target)
        finally lock.unlock()
      })
    yield result

  def set(key: String, value: String): IO[Unit] =
    if value.getBytes(UTF_8).length > KvService.maxValueBytes then
      IO.raiseError(KvLimit("Value exceeds 64 KiB of UTF-8"))
    else
      withPath(key) { target =>
        val entries = KvService.readEntries(target)
        if !Files.exists(target) || !entries.get(key).contains(value) then
          entries.put(key, value)
          KvService.writeEntries(target, entries)
      }

  def get(key: String): IO[Option[String]] =
    withPath(key) { target =>
      KvService.readEntries(target).get(key)
    }

  def clear(key: String): IO[Boolean] =
    withPath(key) { target =>
      val entries = KvService.readEntries(target)
      entries.remove(key) match
        case Some(_) =>
          KvService.writeEntries(target, entries)
          true
        case None => false
    }

object KvService:
  val maxValueBytes = 64 * 1024
  private val pathLocks = new ConcurrentHashMap[Path, ReentrantLock]()

  private def lockFor(target: Path): ReentrantLock =
    pathLocks.computeIfAbsent(target.toAbsolutePath.normalize(), _ => new ReentrantLock())

  private[kv] def resolveFile(defaultDir: Path, configured: String): Path =
    val base = defaultDir.toAbsolutePath.normalize()
    val trimmed = Option(configured).map(_.trim).getOrElse("")
    val target = if trimmed.isEmpty then base.resolve("kv.jsonl") else base.resolve(trimmed)
    target.toAbsolutePath.normalize()

  private def readEntries(target: Path): mutable.LinkedHashMap[String, String] =
    val entries = mutable.LinkedHashMap.empty[String, String]
    if Files.exists(target) then
      if Files.isDirectory(target) then
        throw KvStorageError(s"KV store path is a directory: $target")
      Using.resource(Files.newBufferedReader(target, UTF_8)) { reader =>
        var lineNumber = 0
        var line = reader.readLine()
        while line != null do
          lineNumber += 1
          if line.trim.nonEmpty then
            val entry = parse(line)
              .flatMap(JsonModel[KvWrite].decoder.decodeJson)
              .fold(err => throw KvStorageError(s"Invalid JSONL at $target:$lineNumber: ${err.getMessage}"), identity)
            entries.put(entry.key, entry.value)
          line = reader.readLine()
      }
    entries

  private def writeEntries(target: Path, entries: mutable.LinkedHashMap[String, String]): Unit =
    if Files.isDirectory(target) then
      throw KvStorageError(s"KV store path is a directory: $target")
    val dir = Option(target.toAbsolutePath.normalize().getParent)
      .getOrElse(throw KvStorageError(s"KV store path has no parent directory: $target"))
    Files.createDirectories(dir)
    val temporary = Files.createTempFile(dir, ".kv-", ".tmp")
    try
      Using.resource(Files.newBufferedWriter(temporary, UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) { writer =>
        entries.toVector.sortBy(_._1).foreach { (k, v) =>
          writer.write(JsonModel[KvWrite].encoder(KvWrite(k, v)).noSpaces)
          writer.write('\n')
        }
      }
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally
      Files.deleteIfExists(temporary)
      ()

  def resource(
      defaultDir: Path = groot.schedule.Store.defaultHome,
      config: IO[KvConfig] = IO.pure(KvConfig())
  ): Resource[IO, KvService] =
    Resource.pure(new KvService(defaultDir.toAbsolutePath.normalize(), config))
