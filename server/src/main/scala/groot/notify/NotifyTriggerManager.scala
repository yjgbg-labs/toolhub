package groot.notify

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import groot.graaljs.{GraalManager, GraalTask, JavaScript, TaskError, Validation => GraalValidation}
import groot.schedule.{JobState, RetryPolicy, ScheduleBackend, ScheduledItem, Scheduler, Validation => ScheduleValidation}
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.time.{Duration => JDuration, Instant, OffsetDateTime, ZonedDateTime}
import java.util.{HexFormat, UUID}
import org.graalvm.polyglot.Source

/** Session trigger manager backed directly by a dedicated `groot.schedule.Scheduler` instance. */
final class NotifyTriggerManager private (
    store: NotifyTriggerStore,
    val scheduler: Scheduler
):
  def loop: IO[Unit] = scheduler.loop

  def when(sessionId: String, cwd: Option[String], request: NotifyWhenRequest): IO[NotifyTrigger] =
    for
      now <- IO.realTimeInstant
      cleanSession = sessionId.trim
      cleanCwd = cwd.map(_.trim).filter(_.nonEmpty)
      everyText = request.every.map(_.trim).filter(_.nonEmpty).getOrElse(NotifyTriggerManager.defaultEvery)
      delayText = request.delay.map(_.trim).filter(_.nonEmpty).getOrElse(NotifyTriggerManager.defaultDelay)
      cpuText = request.cpu.map(_.trim).filter(_.nonEmpty).getOrElse(NotifyTriggerManager.defaultCpu)
      scriptTimeoutText = request.scriptTimeout.map(_.trim).filter(_.nonEmpty).getOrElse(NotifyTriggerManager.defaultScriptTimeout)
      timeoutText = request.timeout.map(_.trim).filter(_.nonEmpty)
      once = request.once.getOrElse(true)
      delayMs <- IO {
        NotifyService.validateSession(cleanSession)
        if request.code.trim.isEmpty then throw NotifyFailure("invalid_notify", "code must not be blank")
        if request.code.getBytes(UTF_8).length > NotifyTriggerManager.maxCodeBytes then
          throw NotifyFailure("notify_limit", "code exceeds 1 MiB")
        parsePositiveDurationMs("every", everyText)
        parsePositiveDurationMs("cpu", cpuText)
        parsePositiveDurationMs("scriptTimeout", scriptTimeoutText)
        timeoutText.foreach(parsePositiveDurationMs("timeout", _))
        NotifyTriggerManager.parseNonNegativeDurationMs("delay", delayText)
      }
      firstRunAt = Option.when(delayMs > 0L)(now.plusMillis(delayMs).toString)
      expiresAt = timeoutText.map(t => now.plusMillis(parsePositiveDurationMs("timeout", t)).toString)
      stored = StoredTrigger(
        id = NotifyTriggerManager.triggerId(),
        sessionId = cleanSession,
        cwd = cleanCwd,
        code = request.code,
        every = everyText,
        delay = delayText,
        once = once,
        cpu = cpuText,
        scriptTimeout = scriptTimeoutText,
        createdAt = now.toString,
        firstRunAt = firstRunAt,
        expiresAt = expiresAt,
        status = "active"
      )
      _ <- scheduler.mutate() {
        for
          active <- store.active
          _ <- IO.raiseWhen(active.size >= NotifyTriggerManager.maxActiveTriggers)(
            NotifyFailure("notify_busy", s"Too many active notify triggers (limit ${NotifyTriggerManager.maxActiveTriggers})")
          )
          _ <- store.insert(stored)
        yield ()
      }
    yield NotifyTriggerManager.toPublic(stored, running = false, now = now)

  /** Thin wrapper over notify.when: sleeps `delay` until target time, then returns `message` once. */
  def at(sessionId: String, request: NotifyAtRequest): IO[NotifyTrigger] =
    for
      now <- IO.realTimeInstant
      targetInstant <- IO(NotifyTriggerManager.parseAtTime(request.at, now))
      _ <- IO {
        if request.message.trim.isEmpty then throw NotifyFailure("invalid_notify", "message must not be blank")
        if request.message.getBytes(UTF_8).length > 65536 then
          throw NotifyFailure("notify_limit", "message is limited to 64 KiB UTF-8")
      }
      delayMs = math.max(0L, JDuration.between(now, targetInstant).toMillis)
      trigger <- when(
        sessionId = sessionId,
        cwd = None,
        request = NotifyWhenRequest(
          code = s"return ${Json.fromString(request.message).noSpaces};",
          delay = Some(s"${delayMs}ms"),
          once = Some(true)
        )
      )
    yield trigger

  def list(activeOnly: Boolean = false): IO[List[NotifyTrigger]] =
    for
      now <- IO.realTimeInstant
      runningIds <- scheduler.runningIds
      entries <- if activeOnly then store.active else store.listAll()
    yield entries.map(t => NotifyTriggerManager.toPublic(t, running = runningIds.contains(t.id), now = now))

  def cancel(id: String): IO[NotifyTrigger] =
    for
      now <- IO.realTimeInstant
      updated <- scheduler.mutate(List(id)) {
        store.get(id).flatMap {
          case None => IO.raiseError(NotifyFailure("notify_not_found", s"No notify trigger named $id"))
          case Some(existing) if existing.status != "active" => IO.pure(existing)
          case Some(existing) =>
            store.update(id)(_.copy(status = "cancelled")).flatMap(IO.fromOption(_)(NotifyFailure("notify_not_found", s"No notify trigger named $id")))
        }
      }
      refreshed <- store.get(id).map(_.getOrElse(updated))
    yield NotifyTriggerManager.toPublic(refreshed, running = false, now = now)

  private def parsePositiveDurationMs(name: String, text: String): Long =
    try GraalValidation.durationMs(name, text)
    catch case e: Exception => throw NotifyFailure("invalid_notify", e.getMessage)

object NotifyTriggerManager:
  val defaultEvery = "15s"
  val defaultDelay = "0ms"
  val defaultCpu = "2s"
  val defaultScriptTimeout = "30s"
  val defaultDeliveryRetry: RetryPolicy = RetryPolicy(maxRetries = 3, delay = "500ms", backoff = 2, maxDelay = "5s")
  val maxCodeBytes: Int = 1024 * 1024
  val maxActiveTriggers = 256

  private val nonNegativeDuration = "^(\\d+)(ms|s|m|h|d)$".r
  private val durationUnits = Map("ms" -> 1L, "s" -> 1000L, "m" -> 60000L, "h" -> 3600000L, "d" -> 86400000L)
  private val relativeDuration = "^\\+(\\d+)(ms|s|m|h|d)$".r

  def triggerId(): String = s"ntf_${UUID.randomUUID().toString.replace("-", "")}"

  private def markerFor(id: String): String = s"__GROOT_NOTIFY_RESULT_${id}__:"

  private def definitionHash(t: StoredTrigger): String =
    val key = s"${t.id}|${t.sessionId}|${t.every}|${t.delay}|${t.once}|${t.cpu}|${t.scriptTimeout}|${t.code}"
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(UTF_8)))

  private def toScheduledItem(t: StoredTrigger): ScheduledItem =
    val ctx = Map("sessionId" -> t.sessionId) ++ t.cwd.map("cwd" -> _)
    ScheduledItem(
      everyMs = ScheduleValidation.durationMs(t.every),
      cpu = t.cpu,
      timeout = t.scriptTimeout,
      retry = defaultDeliveryRetry,
      enabled = t.status == "active",
      firstRunAt = t.firstRunAt.map(Instant.parse),
      expiresAt = t.expiresAt.map(Instant.parse),
      context = ctx,
      definitionHash = definitionHash(t)
    )

  private def toPublic(t: StoredTrigger, running: Boolean, now: Instant): NotifyTrigger =
    val everyMs = ScheduleValidation.durationMs(t.every)
    val next =
      if t.status != "active" || running || t.state.inFlight then None
      else
        val computed = ScheduleValidation.nextRun(everyMs, t.firstRunAt.map(Instant.parse), t.state)
        Some(computed.getOrElse(now).toString)
    NotifyTrigger(
      id = t.id,
      sessionId = t.sessionId,
      cwd = t.cwd,
      code = t.code,
      every = t.every,
      delay = t.delay,
      once = t.once,
      cpu = t.cpu,
      scriptTimeout = t.scriptTimeout,
      createdAt = t.createdAt,
      expiresAt = t.expiresAt,
      nextRunAt = next,
      lastRunAt = t.state.lastRunAt,
      lastFinishedAt = t.state.lastFinishedAt,
      lastTriggeredAt = t.lastTriggeredAt,
      lastTaskId = t.state.lastTaskId,
      lastError = t.state.lastError,
      attempt = t.state.attempt,
      checkCount = t.checkCount,
      triggerCount = t.triggerCount,
      status = t.status
    )

  private final class NotifyBackend(store: NotifyTriggerStore, notifyService: NotifyService) extends ScheduleBackend:
    def snapshot: IO[(Map[String, Double], Map[String, ScheduledItem])] =
      store.active.map(list => Map.empty[String, Double] -> list.map(t => t.id -> toScheduledItem(t)).toMap)

    def state(name: String): IO[JobState] = store.state(name)
    def writeState(name: String, state: JobState): IO[Unit] = store.writeState(name, state)
    def removeState(name: String): IO[Unit] = store.clearInFlightState(name)
    def appendLog(name: String, text: String): IO[Unit] = IO.unit

    def loadSource(name: String, item: ScheduledItem): IO[Source] =
      store.get(name).flatMap {
        case None => IO.raiseError(NotifyFailure("notify_not_found", s"Trigger $name disappeared"))
        case Some(t) =>
          val wrapped = wrapPredicate(t.code, markerFor(name))
          IO(JavaScript.source(wrapped, name = s"$name.js", cached = false))
      }

    override def onSucceeded(name: String, item: ScheduledItem, task: GraalTask, output: String, finishedAt: Instant): IO[Option[TaskError]] =
      store.get(name).flatMap {
        case Some(current) if current.status == "active" =>
          extractMessage(output, markerFor(name)) match
            case None =>
              store.update(name)(t => t.copy(checkCount = t.checkCount + 1)).as(None)
            case Some(message) =>
              notifyService.send(NotifyRequest(current.sessionId, message)).attempt.flatMap {
                case Right(_) =>
                  store.update(name) { t =>
                    t.copy(
                      status = if t.once then "triggered" else "active",
                      lastTriggeredAt = Some(finishedAt.toString),
                      checkCount = t.checkCount + 1,
                      triggerCount = t.triggerCount + 1
                    )
                  }.as(None)
                case Left(err: NotifyFailure) =>
                  store.update(name)(t => t.copy(checkCount = t.checkCount + 1))
                    .as(Some(TaskError(err.code, err.getMessage)))
                case Left(err) =>
                  store.update(name)(t => t.copy(checkCount = t.checkCount + 1))
                    .as(Some(TaskError("delivery_error", Option(err.getMessage).getOrElse(err.toString))))
              }
        case _ => IO.pure(None)
      }

    override def onExpired(name: String, now: Instant): IO[Unit] =
      store.update(name)(t => if t.status == "active" then t.copy(status = "expired") else t).void

  private[notify] def parseNonNegativeDurationMs(name: String, text: String): Long = text match
    case "0" => 0L
    case nonNegativeDuration(value, unit) =>
      val millis = BigInt(value) * durationUnits(unit)
      if millis < 0 || millis > Long.MaxValue / 1000000L then
        throw NotifyFailure("invalid_notify", s"$name must be non-negative and fit in a nanosecond duration")
      millis.toLong
    case _ => throw NotifyFailure("invalid_notify", s"$name must be 0 or an integer followed by ms, s, m, h or d")

  private[notify] def wrapPredicate(code: String, marker: String): String =
    val markerLiteral = Json.fromString(marker).noSpaces
    s"""const __groot_notify_res = await (async () => {
       |$code
       |})();
       |if (typeof __groot_notify_res === "string" && __groot_notify_res.trim().length > 0) {
       |  console.log($markerLiteral + JSON.stringify(__groot_notify_res));
       |}""".stripMargin

  private[notify] def extractMessage(logText: String, marker: String): Option[String] =
    logText.linesIterator
      .filter(_.startsWith(marker))
      .toList
      .lastOption
      .flatMap { line =>
        val rawJson = line.stripPrefix(marker)
        parse(rawJson).toOption.flatMap(_.asString).filter(_.trim.nonEmpty)
      }

  private[notify] def parseAtTime(raw: String, now: Instant): Instant =
    val text = raw.trim
    if text.isEmpty then throw NotifyFailure("invalid_notify", "at must not be blank")
    text match
      case relativeDuration(amount, unit) =>
        val ms = parseNonNegativeDurationMs("at", s"$amount$unit")
        now.plusMillis(ms)
      case _ =>
        scala.util.Try(Instant.parse(text))
          .orElse(scala.util.Try(OffsetDateTime.parse(text).toInstant))
          .orElse(scala.util.Try(ZonedDateTime.parse(text).toInstant))
          .getOrElse(throw NotifyFailure("invalid_notify", s"Invalid 'at' time '$raw': use ISO-8601 timestamp (e.g. 2026-11-22T20:00:00+08:00) or relative duration (e.g. +10m)"))

  def resource(
      notifyService: NotifyService,
      graaljs: GraalManager,
      home: Path = groot.schedule.Store.defaultHome,
      startLoop: Boolean = true
  ): Resource[IO, NotifyTriggerManager] =
    for
      store <- NotifyTriggerStore.resource(home)
      backend = new NotifyBackend(store, notifyService)
      scheduler <- Scheduler.withBackend(graaljs, backend, startLoop = startLoop)
    yield new NotifyTriggerManager(store, scheduler)
