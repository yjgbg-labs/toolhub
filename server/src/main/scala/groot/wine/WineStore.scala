package groot.wine

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.model.JsonModel
import io.circe.parser.parse
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

private[wine] final class WineStore(val home: Path):
  def instance(name: String): Path =
    WineValidation.name(name)
    home.resolve("instances").resolve(name)
  def task(id: String): Path =
    WineValidation.name(id)
    home.resolve("tasks").resolve(id)
  def write[A: JsonModel](path: Path, value: A): IO[Unit] = IO.blocking {
    Files.createDirectories(path.getParent)
    val temp = Files.createTempFile(path.getParent, ".write-", ".json")
    try
      Files.writeString(temp, JsonModel[A].encoder(value).noSpaces)
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    finally Files.deleteIfExists(temp)
    ()
  }
  def read[A: JsonModel](path: Path): IO[A] = IO.blocking {
    if !Files.isRegularFile(path) then throw WineFailure("wine_not_found", s"No record: ${path.getFileName}")
    parse(Files.readString(path)).flatMap(JsonModel[A].decoder.decodeJson).fold(throw _, identity)
  }
  def directories(path: Path): IO[Vector[Path]] = IO.blocking {
    Using.resource(Files.list(path))(_.iterator().asScala.filter(p => Files.isDirectory(p) && !Files.isSymbolicLink(p)).toVector)
  }
  def save(state: SavedWine): IO[Unit] = write(instance(state.instanceName).resolve("instance.json"), state)
  def save(task: WineTask): IO[Unit] = write(this.task(task.id).resolve("task.json"), task)
  def remove(path: Path): IO[Unit] = IO.blocking {
    Using.resource(Files.walk(path))(_.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists))
  }

private[wine] object WineStore:
  def resource(home: Path): Resource[IO, WineStore] = Resource.make(IO.blocking {
    Files.createDirectories(home.resolve("instances"))
    Files.createDirectories(home.resolve("tasks"))
    Files.setPosixFilePermissions(home, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
    val file = FileChannel.open(home.resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try
      if file.tryLock() == null then throw WineFailure("wine_conflict", s"Another service owns $home")
      file
    catch case error: Throwable =>
      file.close()
      throw error
  })(file => IO.blocking(file.close())).as(new WineStore(home))
