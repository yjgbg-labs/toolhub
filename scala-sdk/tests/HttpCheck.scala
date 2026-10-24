package sprout.sdk.tests

import _root_.cats.effect.{IO, IOApp, ExitCode, Resource}
import _root_.sttp.client4.DefaultFutureBackend
import scala.concurrent.{ExecutionContext, Future}
import sprout.sdk.*
import sprout.sdk.sttp.{SttpExecutor, SttpTransport}
import sprout.generated.{Client, ScheduleJob}

/** Runs unchanged against Java HttpClient (JVM) and Fetch (Scala.js/Bun). */
object HttpCheck extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    given ExecutionContext = ExecutionContext.global
    Resource.make(IO(DefaultFutureBackend()))(backend => IO.fromFuture(IO(backend.close()))).use { backend =>
      val client = new Client[Future](new SttpExecutor(backend, new SttpTransport(backend, args.head)))
      for
        health <- IO.fromFuture(IO(client.health()))
        _ <- IO(assert(health == "ok"))
        entries <- IO.fromFuture(IO(client.schedule.list()))
        _ <- IO(assert(entries.isEmpty))
        input = ScheduleJob(command = Vector("echo", "hi"), every = "1h", cwd = Optional.Present(None))
        created <- IO.fromFuture(IO(client.schedule.`new`("a /空+", input)))
        _ <- IO(assert(created == input))
        error <- IO.fromFuture(IO(client.schedule.log("a /空+"))).attempt
        _ <- IO(assert(error.left.toOption.exists {
          case ClientError.Http(response) => response.status == 404
          case _ => false
        }))
        _ <- IO.println("HTTP integration passed")
      yield ExitCode.Success
    }
