package groot.schedule

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.{Mutex, Queue, Supervisor}
import cats.syntax.all.*
import framework.model.JsonModel
import groot.graaljs.{GraalManager, GraalTask, MissingGraalTask, TaskError}
import java.nio.file.Path
import java.time.Instant
import org.graalvm.polyglot.Source
import scala.concurrent.duration.*

private[groot] final case class ScheduledItem(
    everyMs: Long,
    cpu: String,
    timeout: String,
    retry: RetryPolicy,
    resource: Map[String, Double] = Map.empty,
    enabled: Boolean = true,
    firstRunAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None,
    context: Map[String, String] = Map.empty,
    scriptPath: Option[String] = None,
    definitionHash: String
)

private[groot] trait ScheduleBackend:
  def snapshot: IO[(Map[String, Double], Map[String, ScheduledItem])]
  def state(name: String): IO[JobState]
  def writeState(name: String, state: JobState): IO[Unit]
  def removeState(name: String): IO[Unit]
  def appendLog(name: String, text: String): IO[Unit]
  def loadSource(name: String, item: ScheduledItem): IO[Source]
  /** Optional post-run hook when the guest succeeds; returning Some(err) marks the attempt failed and eligible for retry. */
  def onSucceeded(name: String, item: ScheduledItem, task: GraalTask, output: String, finishedAt: Instant): IO[Option[TaskError]] =
    IO.pure(None)
  /** Called when an item's expiresAt has passed before launch. */
  def onExpired(name: String, now: Instant): IO[Unit] =
    IO.unit

final class Scheduler private (
    backend: ScheduleBackend,
    cronStore: Option[Store],
    graaljs: GraalManager,
    mutex: Mutex[IO],
    supervisor: Supervisor[IO],
    changed: Queue[IO, Unit]
):
  private case class Running(item: ScheduledItem, state: JobState, done: Deferred[IO, Unit], record: Boolean = true)
  private var running = Map.empty[String, Running]
  private var queued = Vector.empty[String]
  private var accepting = true
  // Only failed internal persistence/observation is retried here; normal job retries live in JobState.
  private var recoveryAfter = Map.empty[String, (Instant, Int)]

  private def wake: IO[Unit] = changed.tryOffer(()).void
  private def failureDelay(failures: Int): FiniteDuration = math.min(30L, 1L << math.min(5, failures)).seconds

  private def serial[A](operation: => IO[A]): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.defer(operation)))

  private def requireStore: Store =
    cronStore.getOrElse(throw new UnsupportedOperationException("Scheduler instance is not backed by a static file Store"))

  private def requireJob(config: ScheduleConfig, name: String): IO[ScheduleJob] =
    IO.fromOption(config.jobs.get(name))(MissingJob(name))

  /** Execute a backend state mutation atomically under the scheduler lock, optionally invalidating item IDs, then wake the loop. */
  private[groot] def mutate[A](invalidateIds: Iterable[String] = Nil)(operation: => IO[A]): IO[A] =
    serial {
      for
        result <- operation
        _ <- invalidateIds.toList.traverse_(invalidate)
      yield result
    }.guarantee(wake)

  private[groot] def runningIds: IO[Set[String]] = serial(IO(running.keySet))

  def config: IO[ScheduleConfig] = serial(requireStore.config)

  def writeConfig(value: ScheduleConfig): IO[ScheduleConfig] = serial {
    val store = requireStore
    for
      previous <- store.config
      _ <- store.writeConfig(value)
      _ <- (previous.jobs.keySet ++ value.jobs.keySet).toList.traverse_ { name =>
        IO.whenA(previous.jobs.get(name) != value.jobs.get(name))(invalidate(name))
      }
    yield value
  }.guarantee(wake)

  private def invalidate(name: String): IO[Unit] = backend.removeState(name) *> IO {
    running.get(name).foreach(value => running = running.updated(name, value.copy(record = false)))
    queued = queued.filterNot(_ == name)
    recoveryAfter -= name
  }

  def put(name: String, job: ScheduleJob): IO[ScheduleJob] = serial {
    val store = requireStore
    for
      config <- store.config
      _ <- store.writeConfig(config.copy(jobs = config.jobs.updated(name, job)))
      _ <- invalidate(name)
    yield job
  }.guarantee(wake)

  def delete(name: String): IO[Unit] = serial {
    val store = requireStore
    for
      config <- store.config
      _ <- store.writeConfig(config.copy(jobs = config.jobs - name))
      _ <- invalidate(name)
    yield ()
  }.guarantee(wake)

  def reset(name: String): IO[JobState] = serial {
    val store = requireStore
    for
      config <- store.config
      _ <- requireJob(config, name)
      _ <- invalidate(name)
    yield JobState()
  }.guarantee(wake)

  def log(name: String, lines: Int): IO[String] = serial {
    val store = requireStore
    for
      config <- store.config
      _ <- requireJob(config, name)
      _ <- IO.raiseWhen(lines <= 0 || lines > 10000)(InvalidSchedule("lines must be between 1 and 10000"))
      history <- store.log(name, lines)
      live <- running.get(name).flatMap(_.state.lastTaskId).traverse(id => graaljs.logs(id).map(_.toString)
        .recover { case _: MissingGraalTask => "" })
    yield (List(history) ++ live.toList).filter(_.nonEmpty).mkString("\n").linesIterator.toList.takeRight(lines).mkString("\n")
  }

  def list: IO[List[ScheduleEntry]] = serial {
    val store = requireStore
    for
      config <- store.config
      now <- IO.realTimeInstant
      entries <- config.jobs.toList.sortBy(_._1).traverse { case (name, job) =>
        for
          state <- store.state(name)
          task <- state.lastTaskId.filter(_ => state.inFlight).traverse(id => graaljs.status(id).map(Some(_))
            .recover { case _: MissingGraalTask => None }).map(_.flatten)
        yield
          val next = Validation.nextRun(job, state)
          val status = if running.contains(name) || state.inFlight then "running"
            else if !job.enabled then "disabled"
            else if queued.contains(name) then "queued"
            else if state.nextRetryAt.nonEmpty then "retrying"
            else if next.forall(!_.isAfter(now)) then "due"
            else "waiting"
          ScheduleEntry(name, job.every, job.script, job.cpu, job.timeout, job.retry, job.resource, job.enabled,
            state.lastRunAt, state.lastExitCode, state.lastTaskId, task.map(_.state).orElse(state.lastTaskState),
            state.lastFinishedAt, state.lastError, state.attempt, state.nextRetryAt,
            if job.enabled && status != "running" then next.map(_.toString) else None, status)
      }
    yield entries
  }

  private def fits(total: Map[String, Double], next: Map[String, Double]): Boolean =
    val used = running.values.toList.flatMap(_.item.resource).groupMapReduce(_._1)(_._2)(_ + _)
    (used.keySet ++ next.keySet).forall(key => used.getOrElse(key, 0d) + next.getOrElse(key, 0d) <= total.getOrElse(key, 0d))

  private def appendLog(name: String, text: String): IO[Unit] = backend.appendLog(name, text)
    .handleErrorWith(error => IO(scribe.error(s"Cannot append schedule log $name", error)))

  /** Only called with the scheduler mutex held; waiting for the guest never holds that mutex. */
  private def observe(name: String, item: ScheduledItem, state: JobState, result: IO[Option[GraalTask]], fallback: TaskError, record: Boolean = true): IO[Unit] =
    for
      done <- Deferred[IO, Unit]
      active = Running(item, state, done, record)
      _ <- IO { running += name -> active; queued = queued.filterNot(_ == name) }
      _ <- supervisor.supervise {
        result.flatMap(task => finish(name, active, task, fallback))
          .handleErrorWith { error =>
            IO(scribe.error(s"Cannot finalize schedule job $name; scheduling recovery", error)) *> serial {
              IO.whenA(running.get(name).exists(_.record)) {
                IO.realTimeInstant.flatMap { now => IO {
                  val failures = recoveryAfter.get(name).fold(0)(_._2)
                  recoveryAfter += name -> (now.plusMillis(failureDelay(failures).toMillis), math.min(5, failures + 1))
                } }
              }
            }
          }
          .guarantee(serial(IO {
            if running.get(name).exists(_.state.lastTaskId == state.lastTaskId) then running -= name
          }) *> wake *> done.complete(()).void)
      }
    yield ()

  private def result(state: JobState): IO[Option[GraalTask]] = state.lastTaskId.traverse(graaljs.await)
    .recover { case _: MissingGraalTask => None }

  private def finish(name: String, active: Running, task: Option[GraalTask], fallback: TaskError): IO[Unit] = serial {
    for
      now <- IO.realTimeInstant
      rawOutcome = task.fold(if fallback.code == "interrupted" || fallback.code == "missing_task" then "interrupted" else "failed")(_.state)
      rawError = task.fold(Some(fallback))(_.error)
      finishedAt = task.flatMap(_.finishedAt).getOrElse(now.toString)
      output <- task.traverse(value => graaljs.logs(value.id).map(_.toString)).handleError(_ => None)
      isRecorded = running.get(name).exists(_.record)
      hookError <- if isRecorded && rawOutcome == "succeeded" && task.nonEmpty then
        backend.onSucceeded(name, active.item, task.get, output.getOrElse(""), Instant.parse(finishedAt))
      else IO.pure(None)
      outcome = if hookError.nonEmpty then "failed" else rawOutcome
      error = hookError.orElse(rawError)
      retryAt = Option.when(outcome != "succeeded" && active.state.attempt <= active.item.retry.maxRetries)(
        Instant.parse(finishedAt).plusMillis(Validation.retryDelayMs(active.item.retry, active.state.attempt)).toString)
      _ <- appendLog(name, output.getOrElse("") + s"[$now] task=${active.state.lastTaskId.getOrElse("unsubmitted")} attempt=${active.state.attempt} state=$outcome" +
        error.fold("")(value => s" error=${value.code}: ${value.message}") + "\n")
      _ <- IO.whenA(isRecorded) {
        backend.writeState(name, active.state.copy(lastExitCode = Some(if outcome == "succeeded" then 0 else if outcome == "cancelled" || outcome == "interrupted" then 143 else 1),
          lastTaskId = task.map(_.id), lastTaskState = Some(outcome), lastFinishedAt = Some(finishedAt), lastError = error,
          nextRetryAt = retryAt, inFlight = false)) *>
          retryAt.traverse_(at => appendLog(name, s"[$now] retry ${active.state.attempt}/${active.item.retry.maxRetries} at $at\n"))
      }
      _ <- IO { running -= name; recoveryAfter -= name }
    yield ()
  }

  private def recover(items: Map[String, ScheduledItem], now: Instant): IO[Unit] = items.toList.traverse_ { (name, item) =>
    if running.contains(name) then IO.unit
    else backend.state(name).flatMap { state =>
      // Config and state are separate atomic files. Detect a crash between config replacement and invalidation.
      val stale = state.definitionHash.exists(_ != item.definitionHash)
      IO.whenA(stale)(invalidate(name)) *>
        IO.whenA(state.inFlight && (stale || recoveryAfter.get(name).forall(!_._1.isAfter(now))))(observe(name, item, state, result(state),
          TaskError("interrupted", "Server stopped before GraalJS admission completed"), record = !stale))
    }
  }

  private def submitItem(name: String, item: ScheduledItem, now: Instant): IO[Unit] =
    for
      previous <- backend.state(name)
      submitted <- backend.loadSource(name, item).flatMap(source => graaljs.submit(source, item.cpu, item.timeout, item.context)).attempt
      task = submitted.toOption
      state = JobState(lastRunAt = Some(now.toString), lastTaskId = task.map(_.id), lastTaskState = task.map(_.state),
        attempt = if previous.nextRetryAt.nonEmpty then previous.attempt + 1 else 1, inFlight = true, definitionHash = Some(item.definitionHash))
      fallback = submitted.fold(error => TaskError(if error.isInstanceOf[ScriptReadError] then "source_error" else "submission_error", error.toString.take(8192)),
        _ => TaskError("missing_task", "GraalJS task disappeared"))
      _ <- appendLog(name, s"\n[$now] start task=${state.lastTaskId.getOrElse("unsubmitted")} attempt=${state.attempt}\n")
      // Admission returns the ID. Keep live ownership even if persisting it fails; process-loss recovery
      // intentionally accepts the window between admission and this state write.
      _ <- backend.writeState(name, state).guarantee(observe(name, item, state, result(state), fallback))
    yield ()

  /** One scheduling decision, called only at startup, after an event, or when the earliest deadline expires. */
  private[schedule] def dispatch: IO[Option[Instant]] = serial {
    for
      snap <- backend.snapshot
      (resources, rawItems) = snap
      now <- IO.realTimeInstant
      items <- if !accepting then IO.pure(rawItems) else
        recover(rawItems, now) *> rawItems.toList.sortBy(_._1).foldLeftM(rawItems) { case (acc, (name, item)) =>
          if !item.enabled || running.contains(name) || queued.contains(name) then IO.pure(acc)
          else if item.expiresAt.exists(!now.isBefore(_)) then
            backend.onExpired(name, now) *> invalidate(name) *> IO.pure(acc - name)
          else backend.state(name).flatMap { state =>
            IO.whenA(!state.inFlight && Validation.nextRun(item.everyMs, item.firstRunAt, state).forall(!_.isAfter(now)))(IO { queued :+= name }).as(acc)
          }
        }
      _ <- IO.whenA(accepting) {
        queued.toList.traverse_ { name => IO.defer {
          items.get(name) match
            case None => IO { queued = queued.filterNot(_ == name) }
            case Some(item) if !item.enabled => IO { queued = queued.filterNot(_ == name) }
            case Some(item) if !fits(resources, item.resource) => IO.unit
            case Some(item) => submitItem(name, item, now)
        } }
      }
      deadlines <- items.toList.traverse { (name, item) =>
        // A resource-blocked job waits for completion/config events, never for an already-expired timer.
        if !item.enabled then IO.pure(None)
        else if running.contains(name) || queued.contains(name) then IO.pure(item.expiresAt)
        else backend.state(name).map { state =>
          if state.inFlight then None
          else List(Validation.nextRun(item.everyMs, item.firstRunAt, state), item.expiresAt).flatten.minOption
        }
      }
      recoveries = recoveryAfter.toList.collect { case (name, (at, _)) if items.contains(name) && !running.contains(name) => at }
    yield (deadlines.flatten ++ recoveries).minOption
  }

  private def awaitChange(deadline: Option[Instant]): IO[Unit] = deadline match
    case None => changed.take
    case Some(at) => IO.realTimeInstant.flatMap { now =>
      val remaining = java.time.Duration.between(now, at)
      val millis = remaining.toMillis + (if remaining.getNano % 1000000 == 0 then 0L else 1L)
      if !at.isAfter(now) then IO.cede
      // FiniteDuration/IO.sleep use nanoseconds; very distant persisted dates must not overflow.
      else IO.race(changed.take, IO.sleep(math.min(millis, Long.MaxValue / 1000000L).millis)).void
    }

  private[groot] def loop: IO[Unit] =
    def cycle(failures: Int): IO[Unit] =
      // Drain BEFORE reading state. An event arriving during dispatch or timer installation remains queued.
      changed.tryTake *> dispatch.attempt.flatMap {
        case Right(deadline) => awaitChange(deadline) *> IO.defer(cycle(0))
        case Left(error) =>
          IO(scribe.error("Schedule dispatch failed", error)) *>
            IO.realTimeInstant.flatMap(now => awaitChange(Some(now.plusMillis(failureDelay(failures).toMillis)))) *>
            IO.defer(cycle(math.min(5, failures + 1)))
      }
    cycle(0)

  private def shutdown: IO[Unit] = serial {
    IO { accepting = false; running.values.toList }
  }.flatMap { active =>
    // Keep ownership and reservations until all guest contexts and result observers have finished.
    active.parTraverse_(_.state.lastTaskId.traverse_(id => graaljs.cancel(id).void.recover { case _: MissingGraalTask => () })) *>
      active.parTraverse_(_.done.get)
  }

object Scheduler:
  private[schedule] def jobHash(job: ScheduleJob): String =
    val json = JsonModel[ScheduleJob].encoder(job).printWith(io.circe.Printer.noSpaces.copy(sortKeys = true))
    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
      .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  private final class CronBackend(store: Store, sources: ScriptSources) extends ScheduleBackend:
    def snapshot: IO[(Map[String, Double], Map[String, ScheduledItem])] =
      store.config.map { cfg =>
        val items = cfg.jobs.map { (name, job) =>
          name -> ScheduledItem(
            everyMs = Validation.durationMs(job.every),
            cpu = job.cpu,
            timeout = job.timeout,
            retry = job.retry,
            resource = job.resource,
            enabled = job.enabled,
            scriptPath = Some(job.script),
            definitionHash = jobHash(job)
          )
        }
        cfg.resources -> items
      }
    def state(name: String): IO[JobState] = store.state(name)
    def writeState(name: String, state: JobState): IO[Unit] = store.writeState(name, state)
    def removeState(name: String): IO[Unit] = store.removeState(name)
    def appendLog(name: String, text: String): IO[Unit] = store.appendLog(name, text)
    def loadSource(name: String, item: ScheduledItem): IO[Source] =
      IO.fromOption(item.scriptPath)(MissingJob(name)).flatMap(path => sources.snapshot(Path.of(path)))

  def resource(graaljs: GraalManager, home: Path = Store.defaultHome, startLoop: Boolean = true): Resource[IO, Scheduler] =
    managed(graaljs, new Store(home), startLoop)

  private[schedule] def managed(graaljs: GraalManager, store: Store, startLoop: Boolean = true): Resource[IO, Scheduler] =
    for
      _ <- store.ownership
      _ <- Resource.eval(store.initialize)
      sources <- ScriptSources.resource()
      scheduler <- build(graaljs, new CronBackend(store, sources), Some(store), startLoop)
    yield scheduler

  private[groot] def withBackend(graaljs: GraalManager, backend: ScheduleBackend, startLoop: Boolean = true): Resource[IO, Scheduler] =
    build(graaljs, backend, None, startLoop)

  private def build(graaljs: GraalManager, backend: ScheduleBackend, cronStore: Option[Store], startLoop: Boolean): Resource[IO, Scheduler] =
    for
      mutex <- Resource.eval(Mutex[IO])
      supervisor <- Supervisor[IO](await = true)
      changed <- Resource.eval(Queue.bounded[IO, Unit](1))
      scheduler = new Scheduler(backend, cronStore, graaljs, mutex, supervisor, changed)
      _ <- Resource.make(scheduler.serial((backend.snapshot, IO.realTimeInstant).tupled.flatMap {
        case ((_, items), now) => scheduler.recover(items, now)
      }))(_ => scheduler.shutdown)
      _ <- if startLoop then scheduler.loop.background.void else Resource.unit[IO]
    yield scheduler
