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
import org.http4s.circe.*
import java.time.Instant
import scala.concurrent.duration.*

class NotifySuite extends CatsEffectSuite:
  private val input = NotifyRequest("ses_target", "完成 🧪\n第二行")
  private val agySessionId = "0508816e-1fae-4311-93cc-9f49f8eb4dde"

  private def reader(service: NotifyService): Resource[IO, Queue[IO, NotifyEvent]] = for
    events <- Resource.eval(Queue.unbounded[IO, NotifyEvent])
    stream <- Resource.eval(service.subscribe)
    _ <- stream.evalMap(events.offer).compile.drain.background
    ready <- Resource.eval(events.take.timeout(3.seconds))
    _ <- Resource.eval(IO(assertEquals(ready.kind, NotifyEventKind.ready)))
  yield events

  test("offline send fails; every subscriber receives all targets including UUIDs in order, with no replay after disconnect") {
    NotifyService.resource().use { service => for
      offline <- service.send(input).attempt
      _ = assertEquals(offline.swap.toOption.map(_.asInstanceOf[NotifyFailure].code), Some("notify_offline"))
      _ <- (reader(service), reader(service)).tupled.use { (a, b) => for
        first <- service.send(input)
        second <- service.send(NotifyRequest(agySessionId, "second"))
        aa <- List.fill(2)(()).traverse(_ => a.take)
        bb <- List.fill(2)(()).traverse(_ => b.take)
        _ = assertEquals(first.subscribers, 2)
        _ = assertEquals(aa, bb)
        _ = assertEquals(aa.flatMap(_.notification.map(_.id)), List(first.id, second.id))
        _ = assertEquals(aa.head.notification.map(_.message), Some(input.message))
        _ = assertEquals(aa.flatMap(_.notification.map(_.targetSessionId)), List("ses_target", agySessionId))
      yield () }
      after <- service.send(input).attempt
      _ = assert(after.isLeft)
      _ <- reader(service).use(_.tryTake.map(value => assertEquals(value, None)))
    yield () }
  }

  test("concurrent sends have one consistent order across subscribers") {
    NotifyService.resource().use { service =>
      (reader(service), reader(service)).tupled.use { (a, b) => for
        sent <- (1 to 40).toList.parTraverse(i => service.send(input.copy(targetSessionId = s"ses_$i", message = s"$i")))
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
      stream <- service.subscribe
      _ <- stream.evalMap(event => if event.kind == NotifyEventKind.ready then ready.complete(()).void *> resume.get else IO.unit)
        .compile.drain.background.use { _ =>
          ready.get *> reader(service).use { fast => for
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
        stream <- service.subscribe
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

  test("notify.send is CLI-only (hidden from GraalJS); notify.when/at/list/cancel are exposed on CLI and GraalJS") {
    temporary.use { home =>
      (for
        commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
        service <- NotifyService.resource()
        graal <- GraalManager.resource(home, commands = commands.get)
        triggers <- NotifyTriggerManager.resource(service, graal, home = home, startLoop = false)
      yield (service, triggers, commands)).use { (service, triggers, commands) =>
        val app = httpApp { NotifyRoutes.register(service, Some(triggers)) }
        val sdk = GraalCommandSdk(app)
        assertEquals(sdk.commands.toSet, Set("notify.when", "notify.at", "notify.list", "notify.cancel"))
        assert(!sdk.commands.contains("notify.send"))
        assertEquals(app.endpoints.find(_.operationId == "notify.send").get.exposedOn, Set(cli))
        assertEquals(app.endpoints.find(_.operationId == "notify.subscribe").get.exposedOn, Set(cli))
        assertEquals(app.endpoints.find(_.operationId == "notify.when").get.exposedOn, Set(cli, graaljs))
        assertEquals(app.endpoints.find(_.operationId == "notify.at").get.exposedOn, Set(cli, graaljs))
        for
          _ <- commands.complete(sdk)
          offline <- app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/notify/send"))
            .withEntity(JsonModel[NotifyRequest].encoder(input)))
          _ = assertEquals(offline.status, Status.Conflict)
          _ <- List(input.copy(message = " "), input.copy(message = ""), input.copy(targetSessionId = "bad session!"))
            .traverse_(value => app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/notify/send"))
              .withEntity(JsonModel[NotifyRequest].encoder(value))).map(reply => assertEquals(reply.status, Status.BadRequest)))
          large <- app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/notify/send"))
            .withEntity(JsonModel[NotifyRequest].encoder(input.copy(message = "界" * 22000))))
          _ = assertEquals(large.status, Status.PayloadTooLarge)
          response <- app.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/notify/subscribe")))
          lines <- Queue.unbounded[IO, String]
          _ <- response.body.through(fs2.text.utf8.decode).through(fs2.text.lines).filter(_.startsWith("data: "))
            .evalMap(lines.offer).compile.drain.background.use { _ => for
              ready <- lines.take.timeout(3.seconds)
              _ = assert(ready.contains("ready"))
              sent <- app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/notify/send"))
                .withEntity(JsonModel[NotifyRequest].encoder(NotifyRequest(agySessionId, "hello agy"))))
              _ = assertEquals(sent.status, Status.Ok)
              line <- lines.take.timeout(3.seconds)
              event = io.circe.parser.parse(line.stripPrefix("data: ")).toOption.get
              _ = assertEquals(event.hcursor.downField("notification").get[String]("targetSessionId"), Right(agySessionId))
              _ = assertEquals(event.hcursor.downField("notification").get[String]("message"), Right("hello agy"))
            yield () }
        yield ()
      }
    }
  }

  test("notify.when with once=true fires once when code returns a non-empty string") {
    temporary.use { home =>
      (for
        commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
        service <- NotifyService.resource()
        graal <- GraalManager.resource(home, commands = commands.get)
        triggers <- NotifyTriggerManager.resource(service, graal, home = home, startLoop = true)
      yield (service, triggers, commands, graal)).use { (service, triggers, commands, graal) =>
        val sdk = GraalCommandSdk(httpApp { NotifyRoutes.register(service, Some(triggers)) })
        reader(service).use { events =>
          for
            _ <- commands.complete(sdk)
            created <- triggers.when(NotifyWhenRequest(
              targetSessionId = agySessionId,
              code = """return "条件已成立：任务完成";""",
              every = Some("50ms"),
              once = Some(true)
            ))
            event <- events.take.timeout(5.seconds)
            _ = assertEquals(event.notification.map(_.targetSessionId), Some(agySessionId))
            _ = assertEquals(event.notification.map(_.message), Some("条件已成立：任务完成"))
            finalTrigger <- awaitUntil(triggers.list().map(_.find(_.id == created.id).get))(_.status == "triggered")
            _ = assertEquals(finalTrigger.triggerCount, 1)
          yield ()
        }
      }
    }
  }

  test("notify.when with once=false fires repeatedly until cancelled") {
    temporary.use { home =>
      (for
        commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
        service <- NotifyService.resource()
        graal <- GraalManager.resource(home, commands = commands.get)
        triggers <- NotifyTriggerManager.resource(service, graal, home = home, startLoop = true)
      yield (service, triggers, commands)).use { (service, triggers, commands) =>
        val sdk = GraalCommandSdk(httpApp { NotifyRoutes.register(service, Some(triggers)) })
        reader(service).use { events =>
          for
            _ <- commands.complete(sdk)
            created <- triggers.when(NotifyWhenRequest(
              targetSessionId = agySessionId,
              code = """return "持续提醒";""",
              every = Some("80ms"),
              once = Some(false)
            ))
            first <- events.take.timeout(5.seconds)
            second <- events.take.timeout(5.seconds)
            _ = assertEquals(first.notification.map(_.message), Some("持续提醒"))
            _ = assertEquals(second.notification.map(_.message), Some("持续提醒"))
            cancelled <- triggers.cancel(created.id)
            _ = assertEquals(cancelled.status, "cancelled")
            _ = assert(cancelled.triggerCount >= 2)
          yield ()
        }
      }
    }
  }

  test("notify.at schedules a one-shot reminder via delayed notify.when") {
    temporary.use { home =>
      (for
        commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
        service <- NotifyService.resource()
        graal <- GraalManager.resource(home, commands = commands.get)
        triggers <- NotifyTriggerManager.resource(service, graal, home = home, startLoop = true)
      yield (service, triggers, commands)).use { (service, triggers, commands) =>
        val sdk = GraalCommandSdk(httpApp { NotifyRoutes.register(service, Some(triggers)) })
        reader(service).use { events =>
          for
            _ <- commands.complete(sdk)
            before <- IO.realTimeInstant
            created <- triggers.at(NotifyAtRequest(
              targetSessionId = agySessionId,
              at = "+180ms",
              message = "时间到了"
            ))
            _ = assertEquals(created.once, true)
            _ = assert(created.delay.endsWith("ms") && created.delay != "0ms")
            event <- events.take.timeout(5.seconds)
            after <- IO.realTimeInstant
            _ = assert(java.time.Duration.between(before, after).toMillis >= 140L)
            _ = assertEquals(event.notification.map(_.targetSessionId), Some(agySessionId))
            _ = assertEquals(event.notification.map(_.message), Some("时间到了"))
            done <- awaitUntil(triggers.list().map(_.find(_.id == created.id).get))(_.status == "triggered")
            _ = assertEquals(done.triggerCount, 1)
          yield ()
        }
      }
    }
  }
