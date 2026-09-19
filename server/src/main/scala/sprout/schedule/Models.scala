package sprout.schedule

import io.circe.{Decoder, DecodingFailure}
import sprout.JsonModel
import java.time.Instant
import scala.util.Try

case class ScheduleJob(
    every: String,
    command: List[String],
    resource: Map[String, Double] = Map.empty,
    enabled: Boolean = true,
    cwd: Option[String] = None
)

object ScheduleJob:
  private val derived = JsonModel.derived[ScheduleJob]
  given JsonModel[ScheduleJob] = new JsonModel[ScheduleJob]:
    val typeId = derived.typeId
    val schema = derived.schema
    val encoder = derived.encoder
    val decoder = Decoder.instance { cursor =>
      if cursor.keys.exists(_.exists(key => key == "lastRunAt" || key == "lastExitCode")) then
        Left(DecodingFailure("Runtime state must be changed with schedule reset", cursor.history))
      else derived.decoder(cursor)
    }

case class ScheduleConfig(
    resources: Map[String, Double],
    tickMs: Double,
    jobs: Map[String, ScheduleJob]
) derives JsonModel

object ScheduleConfig:
  val empty: ScheduleConfig = ScheduleConfig(Map.empty, 1000, Map.empty)

case class JobState(lastRunAt: Option[String] = None, lastExitCode: Option[Int] = None) derives JsonModel

case class ScheduleEntry(
    name: String,
    every: String,
    resource: Map[String, Double],
    command: List[String],
    cwd: Option[String],
    enabled: Boolean,
    lastRunAt: Option[String],
    lastExitCode: Option[Int],
    nextRunAt: Option[String],
    status: String
) derives JsonModel

case class InvalidSchedule(message: String) extends RuntimeException(message)
case class MissingJob(name: String) extends RuntimeException(s"No schedule job named $name")

object Validation:
  private val duration = "^(\\d+)(ms|s|m|h|d)$".r
  private val units = Map("ms" -> 1L, "s" -> 1000L, "m" -> 60000L, "h" -> 3600000L, "d" -> 86400000L)

  def durationMs(value: String): Long = value match
    case duration(amount, unit) =>
      val millis = BigInt(amount) * units(unit)
      if millis > BigInt("9007199254740991") then throw InvalidSchedule(s"Duration is too large: $value")
      millis.toLong
    case _ => throw InvalidSchedule(s"Invalid duration: $value (expected an integer followed by ms, s, m, h or d)")

  def name(value: String): Unit =
    if value.trim.isEmpty || Store.fileName(value).length > 200 then throw InvalidSchedule("Job name must be non-blank and at most 200 encoded bytes")

  def resources(values: Map[String, Double]): Unit =
    if values.exists { case (key, value) => key.isEmpty || !value.isFinite || value < 0 } then
      throw InvalidSchedule("Resources must be an object of non-negative finite numbers with non-empty names")

  def config(value: ScheduleConfig): Unit =
    resources(value.resources)
    if !value.tickMs.isFinite || value.tickMs <= 0 || value.tickMs > Int.MaxValue then
      throw InvalidSchedule("tickMs must be positive and at most 2147483647 milliseconds")
    value.jobs.foreach { case (key, job) =>
      name(key)
      durationMs(job.every)
      resources(job.resource)
      job.resource.foreach { case (resource, requested) =>
        if !value.resources.contains(resource) then throw InvalidSchedule(s"Job $key resource $resource is not configured")
        if requested > value.resources(resource) then throw InvalidSchedule(s"Job $key resource $resource exceeds configured total")
      }
      if job.command.isEmpty || job.command.head.isEmpty || job.command.exists(_.contains('\u0000')) then
        throw InvalidSchedule(s"Job $key command must be a non-empty argument array with a non-empty executable and no NUL bytes")
      if job.cwd.exists(value => value.contains('\u0000') || !java.nio.file.Path.of(value).isAbsolute) then
        throw InvalidSchedule(s"Job $key cwd must be an absolute directory")
    }

  def nextRun(job: ScheduleJob, state: JobState): Option[Instant] =
    state.lastRunAt.flatMap(value => Try(Instant.parse(value).plusMillis(durationMs(job.every))).toOption)
