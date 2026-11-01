package groot.graaljs

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.syntax.all.*
import framework.rpc.{PlainText, SSE}
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

final class GraalManager private (
    store: GraalStore,
    runner: GraalRunner,
    mutex: Mutex[IO],
    supervisor: Supervisor[IO],
    budgetBytes: Long
):
  private case class Running(execution: GraalExecution, done: Deferred[IO, Unit])
  private var running = Map.empty[String, Running]
  private var submitted = Map.empty[String, FiniteDuration]
  private var completions = Map.empty[String, Deferred[IO, Either[Throwable, GraalTask]]]
  private var accepting = true

  private def serial[A](operation: => IO[A]): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.defer(operation)))

  private def elapsed(task: GraalTask): IO[GraalTask] =
    if task.terminal then IO.pure(task)
    else IO.monotonic.map { now =>
      task.copy(elapsedTimeMs = submitted.get(task.id).fold(
        math.max(0L, java.time.Duration.between(Instant.parse(task.createdAt), Instant.now()).toMillis).toDouble
      )(start => (now - start).toNanos.toDouble / 1000000d))
    }

  def start(source: String, cpu: String, timeout: String): IO[GraalTask] =
    IO(GraalManager.taskId()).flatMap(submit(_, source, cpu, timeout))

  /** Internal admission with a preallocated ID, so schedule can durably link an attempt before it runs. */
  private[groot] def submit(id: String, source: String, cpu: String, timeout: String): IO[GraalTask] =
    for
      _ <- IO.raiseUnless(id.matches("gjs_[0-9a-f]{32}"))(InvalidGraalTask("Invalid task ID"))
      limits <- IO((Validation.durationMs("cpu", cpu), Validation.durationMs("timeout", timeout)))
      _ <- IO(Validation.source(source))
      task <- serial {
        for
          _ <- IO.raiseUnless(accepting)(GraalStopping())
          pending <- store.active
          _ <- IO.raiseWhen(pending.count(_.state == "queued") >= Limits.maxQueued)(GraalQueueFull())
          now <- IO.realTimeInstant
          clock <- IO.monotonic
          task = GraalTask(id, "queued", limits._1, limits._2,
            Limits.taskBytes, now.toString, None, None, None, 0d, None, store.logPath(id).toAbsolutePath.toString, None)
          _ <- store.createLog(id)
          _ <- store.insert(task, source)
          completed <- Deferred[IO, Either[Throwable, GraalTask]]
          _ <- IO { submitted += id -> clock; completions += id -> completed }
          _ <- dispatch
          current <- store.get(id).flatMap(elapsed)
        yield current
      }
    yield task

  def status(id: String): IO[GraalTask] = serial(store.get(id).flatMap(elapsed))
  /** Waiting is cancellable and never owns the worker. Completion follows Context cleanup and persistence. */
  def await(id: String): IO[GraalTask] = serial {
    store.get(id).map(task => if task.terminal then IO.pure(task) else completions.get(id).fold(
      IO.raiseError[GraalTask](new IllegalStateException(s"Cannot await untracked GraalJS task $id")))(_.get.rethrow))
  }.flatten
  def list: IO[List[GraalTask]] = serial(store.active.flatMap(_.traverse(elapsed)))
  def logs(id: String): IO[PlainText] = store.logs(id).map(PlainText(_))
  def follow(id: String): IO[SSE[PlainText]] = store.get(id).as(SSE(store.follow(id).map(PlainText(_))))

  /** Called only while holding the mutex. Reservations precede worker creation. */
  private def dispatch: IO[Unit] = IO.defer {
    if !accepting || (running.size.toLong + 1) * Limits.taskBytes > budgetBytes then IO.unit
    else store.active.flatMap(_.find(_.state == "queued").traverse_ { queued =>
      for
        now <- IO.realTimeInstant
        task = queued.copy(state = "running", startedAt = Some(now.toString))
        _ <- store.save(task)
        source <- store.source(task.id)
        startClock <- IO.monotonic
        started <- runner.start(task, source, store.logPath(task.id)).attempt
        _ <- started match
          case Left(error) =>
            complete(task, RunResult("failed", None, 0d, Some(TaskError("startup_error", error.toString.take(8192))))) *>
              IO { submitted -= task.id }
          case Right(execution) =>
            for
              done <- Deferred[IO, Unit]
              _ <- IO { running += task.id -> Running(execution, done) }
              _ <- supervisor.supervise(watch(task, execution, startClock + task.timeoutMs.millis).guarantee(done.complete(()).void))
            yield ()
        _ <- dispatch
      yield ()
    })
  }

  private def watch(task: GraalTask, execution: GraalExecution, deadline: FiniteDuration): IO[Unit] =
    (IO.monotonic.flatMap(now => IO.sleep((deadline - now).max(Duration.Zero))) *>
      execution.cancel(GraalRunner.timeout)).background.use { _ =>
      execution.result.flatMap { result => serial {
        (store.get(task.id).flatMap(complete(_, result))).guarantee(IO {
          running -= task.id
          submitted -= task.id
        }) *> dispatch
      } }
    }.handleErrorWith { error =>
      serial {
        IO { accepting = false } *> completions.get(task.id).traverse_(_.complete(Left(error)).void) *>
          IO { completions -= task.id }
      } *> IO(scribe.error(s"Cannot finalize GraalJS task ${task.id}", error))
    }

  private def summary(task: GraalTask): String =
    val cpu = task.cpuTimeMs.fold("n/a")(value => f"${value}%.3fms")
    val error = task.error.fold("")(value => s"[graaljs] ${value.code}: ${value.message}\n")
    f"\n$error[graaljs] state=${task.state} cpu=$cpu elapsed=${task.elapsedTimeMs}%.3fms\n"

  private def complete(task: GraalTask, result: RunResult): IO[Unit] =
    for
      current <- elapsed(task)
      now <- IO.realTimeInstant
      finished = current.copy(state = result.state, finishedAt = Some(now.toString), cpuTimeMs = result.cpuTimeMs,
        runTimeMs = if result.state == "interrupted" && !submitted.contains(task.id) then None else Some(result.runTimeMs), error = result.error)
      // Publish terminal state only after the final log record; followers then drain to EOF.
      output <- store.appendLog(task.id, summary(finished)).attempt
      persisted = output.fold(error => finished.copy(state = "failed",
        error = Some(TaskError("log_error", error.toString.take(8192)))), _ => finished)
      _ <- store.save(persisted)
      _ <- completions.get(task.id).traverse_(_.complete(Right(persisted)).void)
      _ <- IO { completions -= task.id }
    yield ()

  def cancel(id: String): IO[GraalTask] = serial {
    store.get(id).flatMap { task =>
      if task.terminal || task.state == "cancelling" then elapsed(task)
      else if task.state == "queued" then
        complete(task, RunResult("cancelled", None, 0d, Some(GraalRunner.cancelled))) *>
          IO { submitted -= id } *> store.get(id)
      else
        val current = task.copy(state = "cancelling")
        store.save(current) *> supervisor.supervise(running(id).execution.cancel(GraalRunner.cancelled)).void *> elapsed(current)
    }
  }

  private def recover: IO[Unit] = store.active.flatMap(_.traverse_ { task =>
    complete(task, RunResult("interrupted", None, 0d, Some(TaskError("interrupted", "Server restarted before task completion"))))
  })

  private def shutdown: IO[Unit] =
    serial {
      for
        _ <- IO { accepting = false }
        tasks <- store.active
        _ <- tasks.filter(_.state == "queued").traverse_(task => complete(task, RunResult("interrupted", None, 0d, Some(GraalRunner.stopped))))
      yield running.values.toList
    }.flatMap { active =>
      active.parTraverse_(_.execution.cancel(GraalRunner.stopped)) *> active.parTraverse_(_.done.get)
    }

object GraalManager:
  private[groot] def taskId(): String = "gjs_" + UUID.randomUUID().toString.replace("-", "")
  def resource(home: Path = GraalStore.defaultHome, commands: IO[GraalCommandSdk] = IO.pure(GraalCommandSdk.empty)): Resource[IO, GraalManager] =
    managed(home, GraalRunner.resource(commands))

  private[graaljs] def managed(
      home: Path,
      runnerResource: Resource[IO, GraalRunner],
      budgetBytes: Long = Limits.engineBytes
  ): Resource[IO, GraalManager] =
    require(budgetBytes >= Limits.taskBytes)
    for
      store <- GraalStore.resource(home)
      runner <- runnerResource
      mutex <- Resource.eval(Mutex[IO])
      supervisor <- Supervisor[IO](await = true)
      manager = new GraalManager(store, runner, mutex, supervisor, budgetBytes)
      _ <- Resource.make(manager.recover)(_ => manager.shutdown)
    yield manager
