package consumer

import cats.effect.{IO, IOApp}
import zio.{Runtime, Unsafe, ZIO}
import sprout.generated.{Calls, Client}
import sprout.sdk.*
import sprout.sdk.cats.CatsExecutor
import sprout.sdk.zio.ZioExecutor
import sprout.sdk.sttp.{SttpExecutor, SttpTransport}
import _root_.sttp.client4.testing.BackendStub
import _root_.sttp.shared.Identity

/** Compiled in an isolated directory using only published Maven dependencies. */
object PublishedCheck extends IOApp.Simple:
  def run: IO[Unit] =
    val response = HttpResponse(200, Vector("Content-Type" -> "text/plain"), "published")
    val catsClient = new Client[IO](new CatsExecutor[IO](_ => IO.pure(response)))
    val zioClient = new Client[[A] =>> ZIO[Any, ClientError, A]](new ZioExecutor[Any](_ => ZIO.succeed(response)))
    val backend = BackendStub.synchronous.whenAnyRequest.thenRespondAdjust("")
    val sttpClient = new Client[Identity](new SttpExecutor(backend, new SttpTransport(backend, "http://unused.invalid")))
    for
      catsResult <- catsClient.health()
      zioResult <- IO.fromFuture(IO(Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.runToFuture(zioClient.health()) }))
      _ <- IO {
        assert(catsResult == "published" && zioResult == "published")
        sttpClient.schedule.delete("stub-only")
        assert(Calls.schedule.list().request.path == "/schedule/list")
      }
      _ <- IO.println("Published dependencies resolved and executed")
    yield ()
