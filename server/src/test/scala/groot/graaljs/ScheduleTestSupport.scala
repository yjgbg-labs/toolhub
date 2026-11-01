package groot.graaljs

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.Path

/** Uses the real admission, persistence, completion and cancellation machinery with controllable guests. */
object ScheduleTestSupport:
  case class Call(task: GraalTask, source: String, done: Deferred[IO, RunResult], cancelled: Deferred[IO, TaskError], snapshot: GraalSource)

  class Control private[ScheduleTestSupport] (val calls: Ref[IO, Vector[Call]]):
    def finish(id: String, state: String = "succeeded"): IO[Unit] = calls.get.flatMap { values =>
      values.find(_.task.id == id).get.done.complete(RunResult(state, Some(1d), 2d,
        Option.when(state != "succeeded")(TaskError("script_error", "test failure")))).void
    }

  def resource(home: Path, cleanup: IO[Unit] = IO.unit, autoFinish: Boolean = false, budgetBytes: Long = Limits.engineBytes): Resource[IO, (GraalManager, Control)] =
    for
      calls <- Resource.eval(Ref.of[IO, Vector[Call]](Vector.empty))
      runner = new GraalRunner:
        def start(task: GraalTask, source: GraalSource, log: Path): IO[GraalExecution] =
          for
            done <- Deferred[IO, RunResult]
            cancelled <- Deferred[IO, TaskError]
            _ <- calls.update(_ :+ Call(task, source.text, done, cancelled, source))
            _ <- IO.whenA(autoFinish)(done.complete(RunResult("succeeded", Some(1d), 2d, None)).void)
          yield new GraalExecution:
            def result = done.get
            def cancel(reason: TaskError) = cancelled.complete(reason).void *> cleanup *>
              done.complete(RunResult(if reason.code == "timeout" then "failed" else reason.code, Some(1d), 2d, Some(reason))).void
      manager <- GraalManager.managed(home, Resource.pure(runner), budgetBytes)
    yield (manager, new Control(calls))
