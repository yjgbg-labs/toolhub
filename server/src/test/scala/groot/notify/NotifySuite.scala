package groot.notify

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import scala.concurrent.duration.*

class NotifySuite extends CatsEffectSuite:
  private val input = NotifyRequest("ses_target", "完成 🧪\n第二行", "任务完成")
  private def reader(service: NotifyService, session: String): Resource[IO, Queue[IO, NotifyEvent]] = for
    events <- Resource.eval(Queue.unbounded[IO, NotifyEvent])
    stream <- Resource.eval(service.subscribe(session))
    _ <- stream.evalMap(events.offer).compile.drain.background
    ready <- Resource.eval(events.take.timeout(3.seconds))
    _ <- Resource.eval(IO(assertEquals(ready.kind, NotifyEventKind.ready)))
  yield events

  test("offline send fails; live fanout is ordered and isolated, with no replay after disconnect") {
    NotifyService.resource().use { service => for
      offline <- service.send(input).attempt
      _ = assertEquals(offline.swap.toOption.map(_.asInstanceOf[NotifyFailure].code), Some("notify_offline"))
      _ <- (reader(service, "ses_target"), reader(service, "ses_target"), reader(service, "ses_other")).tupled.use { (a, b, other) => for
        first <- service.send(input)
        second <- service.send(input.copy(message = "second"))
        aa <- List.fill(2)(()).traverse(_ => a.take)
        bb <- List.fill(2)(()).traverse(_ => b.take)
        untouched <- other.tryTake
        _ = assertEquals(first.subscribers, 2)
        _ = assertEquals(aa, bb)
        _ = assertEquals(aa.flatMap(_.notification.map(_.id)), List(first.id, second.id))
        _ = assertEquals(aa.head.notification.map(_.message), Some(input.message))
        _ = assertEquals(untouched, None)
      yield () }
      after <- service.send(input).attempt
      _ = assert(after.isLeft)
      _ <- reader(service, "ses_target").use(_.tryTake.map(value => assertEquals(value, None)))
    yield () }
  }

  test("concurrent sends have one consistent order across subscribers") {
    NotifyService.resource().use { service =>
      (reader(service, "ses_target"), reader(service, "ses_target")).tupled.use { (a, b) => for
        sent <- (1 to 40).toList.parTraverse(i => service.send(input.copy(message = s"$i")))
        aa <- List.fill(40)(()).traverse(_ => a.take).timeout(3.seconds)
        bb <- List.fill(40)(()).traverse(_ => b.take).timeout(3.seconds)
      yield {
        assertEquals(aa, bb)
        assertEquals(aa.flatMap(_.notification.map(_.id)).toSet, sent.map(_.id).toSet)
      } }
    }
  }

  test("a full subscriber queue rejects before partially publishing to other subscribers") {
    NotifyService.resource(capacity = 2).use { service => for
      ready <- Deferred[IO, Unit]
      resume <- Deferred[IO, Unit]
      stream <- service.subscribe("ses_target")
      _ <- stream.evalMap(event => if event.kind == NotifyEventKind.ready then ready.complete(()).void *> resume.get else IO.unit)
        .compile.drain.background.use { _ =>
          ready.get *> reader(service, "ses_target").use { fast => for
            _ <- service.send(input)
            _ <- fast.take
            _ <- service.send(input)
            _ <- fast.take
            rejected <- service.send(input).attempt
            _ = assertEquals(rejected.swap.toOption.map(_.asInstanceOf[NotifyFailure].code), Some("notify_busy"))
            empty <- fast.tryTake
            _ = assertEquals(empty, None)
          yield () }
        }
    yield () }
  }

  test("heartbeats keep idle subscriptions alive; shutdown ends subscribers and rejects sends") {
    for
      allocated <- NotifyService.resource(heartbeat = 20.millis).allocated
      (service, close) = allocated
      _ <- (for
        stream <- service.subscribe("ses_target")
        events <- stream.take(2).compile.toVector.timeout(3.seconds)
        _ = assertEquals(events.map(_.kind), Vector(NotifyEventKind.ready, NotifyEventKind.heartbeat))
        ready <- Deferred[IO, Unit]
        fiber <- stream.evalMap(_ => ready.complete(()).void).compile.drain.start
        _ <- (ready.get *> close *> fiber.joinWithNever.timeout(3.seconds)).guarantee(fiber.cancel)
        rejected <- service.send(input).attempt
        _ = assertEquals(rejected.swap.toOption.map(_.asInstanceOf[NotifyFailure].code), Some("notify_closed"))
      yield ()).guarantee(close)
    yield ()
  }

  test("HTTP errors, SSE framing and GraalJS-only send exposure share the ordinary command contract") {
    NotifyService.resource().use { service =>
      val app = httpApp { NotifyRoutes.register(service) }
      val sdk = GraalCommandSdk(app)
      assertEquals(sdk.commands, Vector("notify.send"))
      assertEquals(app.endpoints.find(_.operationId == "notify.send").get.exposedOn, Set(graaljs))
      assertEquals(app.endpoints.find(_.operationId == "notify.subscribe").get.exposedOn, Set(cli))
      assertEquals(io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths")
        .downField("/notify/send").downField("post").get[List[String]]("x-expose-on"), Right(List("graaljs")))
      for
        hidden <- app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/notify/send")))
        _ = assertEquals(hidden.status, Status.NotFound)
        offline <- sdk.call("notify.send", JsonModel[NotifyRequest].encoder(input))
        _ = assertEquals(offline.hcursor.get[Int]("status"), Right(409))
        invalid <- app.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/notify/subscribe?sessionId=wrong")))
        _ = assertEquals(invalid.status, Status.BadRequest)
        _ <- List(input.copy(message = " "), input.copy(display = ""), input.copy(targetSessionId = "wrong"))
          .traverse_(value => sdk.call("notify.send", JsonModel[NotifyRequest].encoder(value)).map(reply => assertEquals(reply.hcursor.get[Int]("status"), Right(400))))
        large <- sdk.call("notify.send", JsonModel[NotifyRequest].encoder(input.copy(message = "界" * 22000)))
        _ = assertEquals(large.hcursor.get[Int]("status"), Right(413))
        response <- app.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/notify/subscribe?sessionId=ses_target")))
        lines <- Queue.unbounded[IO, String]
        _ <- response.body.through(fs2.text.utf8.decode).through(fs2.text.lines).filter(_.startsWith("data: "))
          .evalMap(lines.offer).compile.drain.background.use { _ => for
            ready <- lines.take.timeout(3.seconds)
            _ = assert(ready.contains("ready"))
            sent <- sdk.call("notify.send", JsonModel[NotifyRequest].encoder(input))
            _ = assertEquals(sent.hcursor.get[Int]("status"), Right(200))
            line <- lines.take.timeout(3.seconds)
            event = io.circe.parser.parse(line.stripPrefix("data: ")).toOption.get
            _ = assertEquals(event.hcursor.downField("notification").get[String]("message"), Right(input.message))
          yield () }
      yield ()
    }
  }

  test("an actual GraalJS task sends a notification to a live session") {
    temporary.use { home => NotifyService.resource().use { service =>
      val sdk = GraalCommandSdk(httpApp { NotifyRoutes.register(service) })
      reader(service, "ses_target").use { events => GraalManager.resource(home, commands = IO.pure(sdk)).use { manager => for
        task <- manager.start(JavaScript.source("await tools.notify.send({targetSessionId:'ses_target',message:'任务完成',display:'Done'});"), "1s", "10s")
        completed <- awaitUntil(manager.status(task.id))(_.finishedAt.nonEmpty)
        _ = assertEquals(completed.state, "succeeded", completed.error.toString)
        event <- events.take.timeout(3.seconds)
        _ = assertEquals(event.notification.map(_.display), Some("Done"))
      yield () } }
    } }
  }
