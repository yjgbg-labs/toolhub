package groot.graaljs

import framework.model.JsonModel
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

case class TaskError(code: String, message: String) derives JsonModel

case class GraalTask(
    id: String,
    script: String,
    cwd: String,
    state: String,
    cpuLimitMs: Long,
    timeoutMs: Long,
    memoryLimitBytes: Long,
    createdAt: String,
    startedAt: Option[String],
    finishedAt: Option[String],
    cpuTimeMs: Option[Double],
    elapsedTimeMs: Double,
    runTimeMs: Option[Double],
    logPath: String,
    error: Option[TaskError]
) derives JsonModel:
  def terminal: Boolean = !GraalTask.activeStates.contains(state)

object GraalTask:
  val activeStates = Set("queued", "running", "cancelling")

private[graaljs] object Limits:
  val engineBytes: Long = 64L * 1024 * 1024
  val taskBytes: Long = 4L * 1024 * 1024
  val maxQueued = 1024
  val maxSourceBytes = 1024 * 1024
  val maxLogBytes = 8L * 1024 * 1024

case class InvalidGraalTask(message: String) extends RuntimeException(message)
case class MissingGraalTask(id: String) extends RuntimeException(s"No GraalJS task named $id")
case class GraalQueueFull() extends RuntimeException("GraalJS queue is full")
case class GraalStopping() extends RuntimeException("GraalJS is stopping")

private[graaljs] object Validation:
  private val duration = "^(\\d+)(ms|s|m|h|d)$".r
  private val units = Map("ms" -> 1L, "s" -> 1000L, "m" -> 60000L, "h" -> 3600000L, "d" -> 86400000L)

  def durationMs(name: String, text: String): Long = text match
    case duration(value, unit) =>
      val millis = BigInt(value) * units(unit)
      if millis <= 0 || millis > Long.MaxValue / 1000000L then
        throw InvalidGraalTask(s"$name must be positive and fit in a nanosecond duration")
      millis.toLong
    case _ => throw InvalidGraalTask(s"$name must be an integer followed by ms, s, m, h or d")

  def source(cwd: String, script: String): (Path, String) =
    try
      val directory = Path.of(cwd)
      if !directory.isAbsolute || !Files.isDirectory(directory) then
        throw InvalidGraalTask("cwd must be an absolute, server-accessible directory")
      if script.trim.isEmpty then throw InvalidGraalTask("script must not be blank")
      val path = directory.resolve(script).toRealPath()
      if !Files.isRegularFile(path) then throw InvalidGraalTask("script must be a regular file")
      val input = Files.newInputStream(path)
      val bytes = try input.readNBytes(Limits.maxSourceBytes + 1) finally input.close()
      if bytes.length > Limits.maxSourceBytes then throw InvalidGraalTask("script exceeds 1 MiB")
      (path, UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString)
    catch
      case error: InvalidGraalTask => throw error
      case NonFatal(error) => throw InvalidGraalTask(s"Cannot read script: ${error.getMessage}")
