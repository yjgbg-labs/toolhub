package sprout.schedule

import cats.effect.{IO, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.syntax.all.*
import java.nio.file.Path
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final class Scheduler private (
    store: Store,
    mutex: Mutex[IO],
    supervisor: Supervisor[IO],
    run: Scheduler.Runner
):
  private case class Running(job: ScheduleJob, record: Boolean = true)
  private var running = Map.empty[String, Running]
  private var queued = Vector.empty[String]

  private def serial[A](operation: => IO[A]): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.defer(operation)))
  private def requireJob(config: ScheduleConfig, name: String): IO[ScheduleJob] =
    IO.fromOption(config.jobs.get(name))(MissingJob(name))

  def config: IO[ScheduleConfig] = serial(store.config)

  def writeConfig(value: ScheduleConfig): IO[ScheduleConfig] = serial {
    for
      previous <- store.config
      _ <- store.writeConfig(value)
      _ <- (previous.jobs.keySet -- value.jobs.keySet).toList.traverse_(invalidate)
    yield value
  }

  private def invalidate(name: String): IO[Unit] = store.removeState(name) *> IO {
    running.get(name).foreach(value => running = running.updated(name, value.copy(record = false)))
    queued = queued.filterNot(_ == name)
  }

  def put(name: String, job: ScheduleJob): IO[ScheduleJob] = serial {
    for
      config <- store.config
      _ <- store.writeConfig(config.copy(jobs = config.jobs.updated(name, job)))
      _ <- invalidate(name)
    yield job
  }

  def delete(name: String): IO[Unit] = serial {
    for
      config <- store.config
      _ <- store.writeConfig(config.copy(jobs = config.jobs - name))
      _ <- invalidate(name)
    yield ()
  }

  def reset(name: String): IO[JobState] = serial {
    for
      config <- store.config
      _ <- requireJob(config, name)
      _ <- invalidate(name)
      state = JobState()
      _ <- store.writeState(name, state)
    yield state
  }

  def log(name: String, lines: Int): IO[String] = serial {
    for
      config <- store.config
      _ <- requireJob(config, name)
      _ <- IO.raiseWhen(lines <= 0 || lines > 10000)(InvalidSchedule("lines must be between 1 and 10000"))
      text <- store.log(name, lines)
    yield text
  }

  def list: IO[List[ScheduleEntry]] = serial {
    for
      config <- store.config
      now <- IO.realTimeInstant
      entries <- config.jobs.toList.sortBy(_._1).traverse { case (name, job) =>
        store.state(name).map { state =>
          val next = Validation.nextRun(job, state)
          val status = if running.contains(name) then "running"
            else if !job.enabled then "disabled"
            else if queued.contains(name) then "queued"
            else if next.forall(!_.isAfter(now)) then "due"
            else "waiting"
          ScheduleEntry(name, job.every, job.resource, job.command, job.cwd, job.enabled, state.lastRunAt,
            state.lastExitCode, if job.enabled then next.map(_.toString) else None, status)
        }
      }
    yield entries
  }

  private def fits(total: Map[String, Double], next: Map[String, Double]): Boolean =
    val used = running.values.toList.flatMap(_.job.resource).groupMapReduce(_._1)(_._2)(_ + _)
    // Also honour a hot-reloaded capacity reduction while old jobs finish.
    (used.keySet ++ next.keySet).forall(key => used.getOrElse(key, 0d) + next.getOrElse(key, 0d) <= total.getOrElse(key, 0d))

  private def execute(name: String, job: ScheduleJob): IO[Unit] =
    val action = for
      now <- IO.realTimeInstant
      _ <- store.appendLog(name, s"\n[$now] start: ${job.command.mkString(" ")}\n")
      _ <- IO(scribe.info(s"Starting schedule job $name"))
      code <- run(job, store.logPath(name))
      _ <- finish(name, code, None)
    yield ()
    action.handleErrorWith(error => finish(name, 1, Some(error.getMessage)))
      .onCancel(finish(name, 143, Some("Scheduler stopped")))

  private def finish(name: String, code: Int, error: Option[String]): IO[Unit] = serial {
    val record = for
      now <- IO.realTimeInstant
      _ <- store.appendLog(name, error.fold("")(message => s"[$now] error: $message\n") + s"[$now] exit: $code\n")
      _ <- if running.get(name).exists(_.record) then
        store.state(name).flatMap(state => store.writeState(name, state.copy(lastExitCode = Some(code))))
      else IO.unit
    yield ()
    record.handleErrorWith(error => IO(scribe.error(s"Cannot record schedule job $name", error)))
      .guarantee(IO { running -= name }) *> IO(scribe.info(s"Finished schedule job $name with exit code $code"))
  }

  private[schedule] def tick: IO[FiniteDuration] = serial {
    for
      config <- store.config
      now <- IO.realTimeInstant
      _ <- config.jobs.toList.sortBy(_._1).traverse_ { case (name, job) =>
        if !job.enabled || running.contains(name) || queued.contains(name) then IO.unit
        else store.state(name).flatMap { state =>
          IO.whenA(Validation.nextRun(job, state).forall(!_.isAfter(now)))(IO { queued :+= name })
        }
      }
      _ <- queued.toList.traverse_ { name => IO.defer {
        config.jobs.get(name) match
          case None => IO { queued = queued.filterNot(_ == name) }
          case Some(job) if !job.enabled => IO { queued = queued.filterNot(_ == name) }
          case Some(job) if !fits(config.resources, job.resource) => IO.unit
          case Some(job) =>
            for
              state <- store.state(name)
              _ <- store.writeState(name, state.copy(lastRunAt = Some(now.toString)))
              _ <- IO { queued = queued.filterNot(_ == name); running = running.updated(name, Running(job)) }
              _ <- supervisor.supervise(execute(name, job)).void
            yield ()
      } }
    yield math.max(1L, math.ceil(config.tickMs).toLong).millis
  }

  private def loop: IO[Unit] =
    (tick.handleErrorWith(error => IO(scribe.error("Schedule tick failed", error)).as(1.second)).flatMap(IO.sleep)).foreverM

object Scheduler:
  type Runner = (ScheduleJob, Path) => IO[Int]

  private val process: Runner = (job, log) =>
    Resource.make(IO.blocking {
      val child = new ProcessBuilder(job.command.asJava)
        .directory(job.cwd.map(value => Path.of(value).toFile).orNull)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile))
        .redirectErrorStream(true).start()
      child.getOutputStream.close()
      child
    })(child => IO.blocking {
      if child.isAlive then
        val descendants = child.descendants()
        val handles = try descendants.iterator().asScala.toList.reverse finally descendants.close()
        handles.foreach(_.destroy())
        child.destroy()
        if !child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) then child.destroyForcibly()
        handles.filter(_.isAlive).foreach(_.destroyForcibly())
        child.waitFor()
      ()
    }).use(child => IO.interruptibleMany(child.waitFor()))

  def resource(home: Path = Store.defaultHome, startLoop: Boolean = true, runner: Runner = process): Resource[IO, Scheduler] =
    val store = new Store(home)
    for
      _ <- store.ownership
      _ <- Resource.eval(store.initialize)
      mutex <- Resource.eval(Mutex[IO])
      supervisor <- Supervisor[IO]
      scheduler = new Scheduler(store, mutex, supervisor, runner)
      _ <- if startLoop then scheduler.loop.background.void else Resource.unit[IO]
    yield scheduler
