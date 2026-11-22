package groot.notify

import cats.effect.{IO, Resource}
import cats.effect.std.{Mutex, Queue, Supervisor}
import cats.syntax.all.*
import groot.graaljs.{GraalManager, JavaScript, Validation => GraalValidation}
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.{Duration => JDuration, Instant, OffsetDateTime, ZonedDateTime}
import java.util.UUID
import scala.concurrent.duration.*

/** Event-driven trigger engine for notify.when (with notify.at as a thin wrapper), backed by GraalManager and NotifyService. */
final class NotifyTriggerManager private (
    store: NotifyTriggerStore,
    notifyService: NotifyService,
    graaljs: GraalManager,
    mutex: Mutex[IO],
    supervisor: Supervisor[IO],
    changed: Queue[IO, Unit]
):
  private var inFlight = Set.empty[String]
  private var accepting = true

  private def wake: IO[Unit] = changed.tryOffer(()).void
  private def serial[A](action: => IO[A]): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.defer(action)))

  def when(request: NotifyWhenRequest): IO[NotifyTrigger] =
    for
      now <- IO.realTimeInstant
      everyText = request.every.map(_.trim).filter(_.nonEmpty).getOrElse(NotifyTriggerManager.defaultEvery)
      delayText = request.delay.map(_.trim).filter(_.nonEmpty).getOrElse(NotifyTriggerManager.defaultDelay)
      cpuText = request.cpu.map(_.trim).filter(_.nonEmpty).getOrElse(NotifyTriggerManager.defaultCpu)
      scriptTimeoutText = request.scriptTimeout.map(_.trim).filter(_.nonEmpty).getOrElse(NotifyTriggerManager.defaultScriptTimeout)
      timeoutText = request.timeout.map(_.trim).filter(_.nonEmpty)
      once = request.once.getOrElse(true)
      delayMs <- IO {
        NotifyService.validateSession(request.targetSessionId)
        if request.code.trim.isEmpty then throw NotifyFailure("invalid_notify", "code must not be blank")
        if request.code.getBytes(UTF_8).length > NotifyTriggerManager.maxCodeBytes then
          throw NotifyFailure("notify_limit", "code exceeds 1 MiB")
        parsePositiveDurationMs("every", everyText)
        parsePositiveDurationMs("cpu", cpuText)
        parsePositiveDurationMs("scriptTimeout", scriptTimeoutText)
        timeoutText.foreach(parsePositiveDurationMs("timeout", _))
        NotifyTriggerManager.parseNonNegativeDurationMs("delay", delayText)
      }
      firstCheckAt = now.plusMillis(delayMs)
      expiresAt = timeoutText.map(t => now.plusMillis(parsePositiveDurationMs("timeout", t)).toString)
      trigger = NotifyTrigger(
        id = NotifyTriggerManager.triggerId(),
        targetSessionId = request.targetSessionId.trim,
        code = request.code,
        every = everyText,
        delay = delayText,
        once = once,
        cpu = cpuText,
        scriptTimeout = scriptTimeoutText,
        createdAt = now.toString,
        expiresAt = expiresAt,
        nextCheckAt = Some(firstCheckAt.toString),
        status = "active"
      )
      _ <- register(trigger)
    yield trigger

  /** Thin wrapper over notify.when: sleeps `delay` until target time, then returns `message` once. */
  def at(request: NotifyAtRequest): IO[NotifyTrigger] =
    for
      now <- IO.realTimeInstant
      targetInstant <- IO(NotifyTriggerManager.parseAtTime(request.at, now))
      _ <- IO {
        if request.message.trim.isEmpty then throw NotifyFailure("invalid_notify", "message must not be blank")
        if request.message.getBytes(UTF_8).length > 65536 then
          throw NotifyFailure("notify_limit", "message is limited to 64 KiB UTF-8")
      }
      delayMs = math.max(0L, JDuration.between(now, targetInstant).toMillis)
      trigger <- when(NotifyWhenRequest(
        targetSessionId = request.targetSessionId,
        code = s"return ${Json.fromString(request.message).noSpaces};",
        delay = Some(s"${delayMs}ms"),
        once = Some(true)
      ))
    yield trigger

  private def register(trigger: NotifyTrigger): IO[Unit] =
    serial {
      for
        _ <- IO.raiseUnless(accepting)(NotifyFailure("notify_closed", "Notification trigger service is shutting down"))
        active <- store.active
        _ <- IO.raiseWhen(active.size >= NotifyTriggerManager.maxActiveTriggers)(
          NotifyFailure("notify_busy", s"Too many active notify triggers (limit ${NotifyTriggerManager.maxActiveTriggers})")
        )
        _ <- store.insert(trigger)
      yield ()
    }.guarantee(wake)

  def list(activeOnly: Boolean = false): IO[List[NotifyTrigger]] =
    if activeOnly then store.active else store.listAll()

  def cancel(id: String): IO[NotifyTrigger] =
    serial {
      store.get(id).flatMap {
        case None => IO.raiseError(NotifyFailure("notify_not_found", s"No notify trigger named $id"))
        case Some(existing) if existing.status != "active" => IO.pure(existing)
        case Some(existing) =>
          val updated = existing.copy(status = "cancelled", nextCheckAt = None)
          store.save(updated).as(updated)
      }
    }.guarantee(wake)

  def loop: IO[Unit] =
    def step: IO[Unit] =
      tick.flatMap {
        case None => IO.unit
        case Some(None) => changed.take *> step
        case Some(Some(delay)) =>
          IO.race(changed.take, IO.sleep(delay)).void *> step
      }
    step

  /** Returns None when shutting down; Some(None) when idle; Some(Some(delay)) for next due trigger. */
  private def tick: IO[Option[Option[FiniteDuration]]] =
    serial {
      if !accepting then IO.pure(None)
      else
        for
          now <- IO.realTimeInstant
          active <- store.active
          nextWait <- active.foldLeftM(Option.empty[FiniteDuration]) { (soonest, trigger) =>
            val expired = trigger.expiresAt.exists(e => !now.isBefore(Instant.parse(e)))
            if expired && !inFlight.contains(trigger.id) then
              store.save(trigger.copy(status = "expired", nextCheckAt = None)).as(soonest)
            else if inFlight.contains(trigger.id) then
              IO.pure(soonest)
            else
              val dueAt = trigger.nextCheckAt.map(Instant.parse).getOrElse(now)
              if !now.isBefore(dueAt) then
                launch(trigger).as(soonest)
              else
                val waitMs = math.max(1L, JDuration.between(now, dueAt).toMillis)
                val wait = waitMs.millis
                IO.pure(Some(soonest.fold(wait)(_.min(wait))))
          }
        yield Some(nextWait)
    }

  private def launch(trigger: NotifyTrigger): IO[Unit] =
    IO { inFlight += trigger.id } *>
      supervisor.supervise(evaluate(trigger).guarantee(serial(IO { inFlight -= trigger.id }) *> wake)).void

  private def evaluate(trigger: NotifyTrigger): IO[Unit] =
    val marker = s"__GROOT_NOTIFY_RESULT_${UUID.randomUUID().toString.replace("-", "")}__:"
    val wrapped = NotifyTriggerManager.wrapPredicate(trigger.code, marker)
    val run: IO[Unit] =
      for
        source <- IO(JavaScript.source(wrapped, name = s"${trigger.id}.js", cached = false))
        task <- graaljs.start(source, trigger.cpu, trigger.scriptTimeout)
        completed <- graaljs.await(task.id)
        checkedAt <- IO.realTimeInstant
        outcome <- if completed.state == "succeeded" then
          graaljs.logs(task.id).map(raw => Right(NotifyTriggerManager.extractMessage(raw.toString, marker)))
        else
          val errMsg = completed.error.map(e => s"${e.code}: ${e.message}").getOrElse(s"Task ended in state ${completed.state}")
          IO.pure(Left(errMsg))
        _ <- outcome match
          case Right(Some(message)) =>
            deliverWithRetry(trigger.targetSessionId, message).attempt.flatMap {
              case Right(_) =>
                serial {
                  store.get(trigger.id).flatMap {
                    case Some(current) if current.status == "active" =>
                      val nextStatus = if current.once then "triggered" else "active"
                      val nextCheck = if current.once then None else Some(checkedAt.plusMillis(parsePositiveDurationMs("every", current.every)).toString)
                      store.save(current.copy(
                        status = nextStatus,
                        nextCheckAt = nextCheck,
                        lastCheckedAt = Some(checkedAt.toString),
                        lastTriggeredAt = Some(checkedAt.toString),
                        lastTaskId = Some(task.id),
                        lastError = None,
                        checkCount = current.checkCount + 1,
                        triggerCount = current.triggerCount + 1
                      ))
                    case _ => IO.unit
                  }
                }
              case Left(sendErr) =>
                recordCheckFailure(trigger.id, checkedAt, Some(task.id), s"delivery_failed: ${sendErr.getMessage}")
            }
          case Right(None) =>
            serial {
              store.get(trigger.id).flatMap {
                case Some(current) if current.status == "active" =>
                  val nextCheck = checkedAt.plusMillis(parsePositiveDurationMs("every", current.every)).toString
                  store.save(current.copy(
                    nextCheckAt = Some(nextCheck),
                    lastCheckedAt = Some(checkedAt.toString),
                    lastTaskId = Some(task.id),
                    lastError = None,
                    checkCount = current.checkCount + 1
                  ))
                case _ => IO.unit
              }
            }
          case Left(errMsg) =>
            recordCheckFailure(trigger.id, checkedAt, Some(task.id), errMsg)
      yield ()

    run.handleErrorWith { err =>
      IO.realTimeInstant.flatMap(now => recordCheckFailure(trigger.id, now, None, Option(err.getMessage).getOrElse(err.toString)))
    }

  private def deliverWithRetry(targetSessionId: String, message: String): IO[NotifyReceipt] =
    def attempt(remaining: Int, delay: FiniteDuration): IO[NotifyReceipt] =
      notifyService.send(NotifyRequest(targetSessionId, message)).handleErrorWith {
        case err: NotifyFailure if (err.code == "notify_offline" || err.code == "notify_busy") && remaining > 0 =>
          IO.sleep(delay) *> attempt(remaining - 1, delay * 2)
        case err => IO.raiseError(err)
      }
    attempt(remaining = 3, delay = 500.millis)

  private def recordCheckFailure(id: String, checkedAt: Instant, taskId: Option[String], error: String): IO[Unit] =
    serial {
      store.get(id).flatMap {
        case Some(current) if current.status == "active" =>
          val nextCheck = checkedAt.plusMillis(parsePositiveDurationMs("every", current.every)).toString
          store.save(current.copy(
            nextCheckAt = Some(nextCheck),
            lastCheckedAt = Some(checkedAt.toString),
            lastTaskId = taskId.orElse(current.lastTaskId),
            lastError = Some(error.take(2048)),
            checkCount = current.checkCount + 1
          ))
        case _ => IO.unit
      }
    }

  private def parsePositiveDurationMs(name: String, text: String): Long =
    try GraalValidation.durationMs(name, text)
    catch case e: Exception => throw NotifyFailure("invalid_notify", e.getMessage)

  private def shutdown: IO[Unit] =
    serial(IO { accepting = false }) *> wake

object NotifyTriggerManager:
  val defaultEvery = "15s"
  val defaultDelay = "0ms"
  val defaultCpu = "2s"
  val defaultScriptTimeout = "30s"
  val maxCodeBytes: Int = 1024 * 1024
  val maxActiveTriggers = 256

  private val nonNegativeDuration = "^(\\d+)(ms|s|m|h|d)$".r
  private val durationUnits = Map("ms" -> 1L, "s" -> 1000L, "m" -> 60000L, "h" -> 3600000L, "d" -> 86400000L)
  private val relativeDuration = "^\\+(\\d+)(ms|s|m|h|d)$".r

  def triggerId(): String = s"ntf_${UUID.randomUUID().toString.replace("-", "")}"

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
      mutex <- Resource.eval(Mutex[IO])
      supervisor <- Supervisor[IO](await = true)
      changed <- Resource.eval(Queue.dropping[IO, Unit](1))
      manager <- Resource.make(IO(new NotifyTriggerManager(store, notifyService, graaljs, mutex, supervisor, changed)))(_.shutdown)
      _ <- if startLoop then manager.loop.background.void else Resource.unit
    yield manager
