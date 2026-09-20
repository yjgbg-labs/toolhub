package groot.schedule

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.parser.parse
import sprout.JsonModel
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.io.RandomAccessFile

final class Store(val home: Path):
  val configPath: Path = home.resolve("schedule.json")
  private val directory = home.resolve("schedule")
  private val stateDir = directory.resolve("state")
  private val logDir = directory.resolve("logs")

  private def read[A: JsonModel](path: Path): A =
    val model = JsonModel[A]
    parse(Files.readString(path)).flatMap(model.decoder.decodeJson).fold(
      error => throw InvalidSchedule(s"Cannot read $path: ${error.getMessage}"), identity)

  private def write[A: JsonModel](path: Path, value: A): Unit =
    Files.createDirectories(path.getParent)
    val temporary = Files.createTempFile(path.getParent, ".schedule-", ".tmp")
    try
      Files.writeString(temporary, JsonModel[A].encoder(value).spaces2 + "\n")
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temporary)

  def initialize: IO[Unit] = IO.blocking {
    if !Files.exists(configPath) then write(configPath, ScheduleConfig.empty)
    Validation.config(read[ScheduleConfig](configPath))
  }

  def config: IO[ScheduleConfig] = IO.blocking {
    val value = read[ScheduleConfig](configPath)
    Validation.config(value)
    value
  }

  def writeConfig(value: ScheduleConfig): IO[Unit] = IO.blocking {
    Validation.config(value)
    write(configPath, value)
  }

  private def statePath(name: String): Path = stateDir.resolve(Store.fileName(name) + ".json")
  def logPath(name: String): Path = logDir.resolve(Store.fileName(name) + ".log")

  def state(name: String): IO[JobState] = IO.blocking {
    val path = statePath(name)
    if !Files.exists(path) then JobState()
    else
      val value = read[JobState](path)
      if value.lastExitCode.exists(_ < 0) then throw InvalidSchedule(s"Invalid exit code in $path")
      value
  }

  def writeState(name: String, value: JobState): IO[Unit] = IO.blocking(write(statePath(name), value))
  def removeState(name: String): IO[Unit] = IO.blocking { Files.deleteIfExists(statePath(name)); () }

  def appendLog(name: String, text: String): IO[Unit] = IO.blocking {
    Files.createDirectories(logDir)
    Files.writeString(logPath(name), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    ()
  }

  def log(name: String, lines: Int): IO[String] = IO.blocking {
    val path = logPath(name)
    if !Files.exists(path) then ""
    else
      val file = new RandomAccessFile(path.toFile, "r")
      try
        val end = file.length()
        var position = end
        var count = 0
        val buffer = new Array[Byte](8192)
        var start = 0L
        var found = false
        // Scan backwards in blocks, without loading the full job history.
        while position > 0 && !found do
          val size = math.min(position, buffer.length.toLong).toInt
          position -= size
          file.seek(position)
          file.readFully(buffer, 0, size)
          var index = size - 1
          while index >= 0 && !found do
            if buffer(index) == '\n'.toByte && position + index != end - 1 then
              count += 1
              if count == lines then
                start = position + index + 1
                found = true
            index -= 1
        if end - start > 8 * 1024 * 1024 then throw InvalidSchedule("Requested log tail exceeds 8 MiB; request fewer lines")
        val bytes = new Array[Byte]((end - start).toInt)
        file.seek(start)
        file.readFully(bytes)
        new String(bytes, UTF_8).stripSuffix("\n")
      finally file.close()
  }

  def ownership: Resource[IO, Unit] =
    Resource.make(IO.blocking {
      Files.createDirectories(directory)
      val channel = FileChannel.open(directory.resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val lock = channel.tryLock()
        if lock == null then throw InvalidSchedule(s"A scheduler already owns $home")
        channel
      catch
        case error: Throwable => channel.close(); throw error
    })(channel => IO.blocking(channel.close())).void

object Store:
  def defaultHome: Path = Path.of(sys.env.getOrElse("SPROUT_HOME",
    Path.of(sys.env.getOrElse("XDG_CONFIG_HOME", Path.of(sys.props("user.home"), ".config").toString), "sprout").toString))

  // Match encodeURIComponent used by Groot, including UTF-8 and escaped slash names.
  def fileName(name: String): String = name.getBytes(UTF_8).map { byte =>
    val value = byte & 0xff
    if (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9') || "-_.!~*'()".contains(value.toChar) then
      value.toChar.toString
    else f"%%$value%02X"
  }.mkString
