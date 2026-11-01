package groot.schedule

import io.circe.{Decoder, DecodingFailure}
import framework.model.JsonModel
import groot.graaljs.{TaskError, InvalidGraalTask, Validation as GraalValidation}
import java.time.Instant

case class RetryPolicy(maxRetries: Int = 3, delay: String = "1s", backoff: Double = 2, maxDelay: String = "30s") derives JsonModel

case class ScheduleJob(
    every: String,
    source: String,
    cpu: String = "1s",
    timeout: String = "30s",
    retry: RetryPolicy = RetryPolicy(),
    resource: Map[String, Double] = Map.empty,
    enabled: Boolean = true
)

object ScheduleJob:
  private val derived = JsonModel.derived[ScheduleJob]
  given JsonModel[ScheduleJob] = new JsonModel[ScheduleJob]:
    val typeId = derived.typeId
    val schema = derived.schema
    val encoder = derived.encoder
    val decoder = Decoder.instance { cursor =>
      val allowed = Set("every", "source", "cpu", "timeout", "retry", "resource", "enabled")
      val unsupported = cursor.keys.toList.flatten.toSet -- allowed
      if unsupported.nonEmpty then
        Left(DecodingFailure(s"Unsupported schedule fields: ${unsupported.toList.sorted.mkString(", ")}; tasks accept JavaScript source, not command/cwd or runtime state", cursor.history))
      else derived.decoder(cursor)
    }

case class ScheduleConfig(
    resources: Map[String, Double],
    tickMs: Double,
    jobs: Map[String, ScheduleJob]
) derives JsonModel

object ScheduleConfig:
  val empty: ScheduleConfig = ScheduleConfig(Map.empty, 1000, Map.empty)

case class JobState(
    lastRunAt: Option[String] = None,
    lastExitCode: Option[Int] = None,
    lastTaskId: Option[String] = None,
    lastTaskState: Option[String] = None,
    lastFinishedAt: Option[String] = None,
    lastError: Option[TaskError] = None,
    attempt: Int = 0,
    nextRetryAt: Option[String] = None,
    inFlight: Boolean = false,
    definitionHash: Option[String] = None
) derives JsonModel

case class ScheduleEntry(
    name: String,
    every: String,
    source: String,
    cpu: String,
    timeout: String,
    retry: RetryPolicy,
    resource: Map[String, Double],
    enabled: Boolean,
    lastRunAt: Option[String],
    lastExitCode: Option[Int],
    lastTaskId: Option[String],
    lastTaskState: Option[String],
    lastFinishedAt: Option[String],
    lastError: Option[TaskError],
    attempt: Int,
    nextRetryAt: Option[String],
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
      try
        GraalValidation.source(job.source)
        GraalValidation.durationMs("cpu", job.cpu)
        GraalValidation.durationMs("timeout", job.timeout)
      catch case error: InvalidGraalTask => throw InvalidSchedule(error.getMessage)
      val retry = job.retry
      if retry.maxRetries < 0 || retry.maxRetries > 100 then throw InvalidSchedule("retry.maxRetries must be between 0 and 100")
      val delay = durationMs(retry.delay)
      if durationMs(retry.maxDelay) < delay then throw InvalidSchedule("retry.maxDelay must be at least retry.delay")
      if !retry.backoff.isFinite || retry.backoff < 1 then throw InvalidSchedule("retry.backoff must be finite and at least 1")
      resources(job.resource)
      job.resource.foreach { case (resource, requested) =>
        if !value.resources.contains(resource) then throw InvalidSchedule(s"Job $key resource $resource is not configured")
        if requested > value.resources(resource) then throw InvalidSchedule(s"Job $key resource $resource exceeds configured total")
      }
    }

  def nextRun(job: ScheduleJob, state: JobState): Option[Instant] =
    state.nextRetryAt.map(Instant.parse).orElse(state.lastRunAt.map(value => Instant.parse(value).plusMillis(durationMs(job.every))))

  /** afterAttempt=1 schedules the first retry; cap before conversion to avoid exponential overflow. */
  def retryDelayMs(policy: RetryPolicy, afterAttempt: Int): Long =
    val initial = durationMs(policy.delay)
    if initial == 0 then 0L
    else math.min(durationMs(policy.maxDelay).toDouble, initial.toDouble * math.pow(policy.backoff, math.max(0, afterAttempt - 1))).toLong
