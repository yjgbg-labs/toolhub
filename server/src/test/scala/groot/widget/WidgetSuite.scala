package groot.widget

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.GraalCommandSdk
import io.circe.Json
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class WidgetSuite extends CatsEffectSuite:
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "widget-test-"))
  )(path => IO.blocking {
    Using.resource(Files.walk(path))(_.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists))
  })
  private def snapshot(service: WidgetService): IO[WidgetSnapshot] = service.subscribe.take(1).compile.lastOrError.timeout(3.seconds)

  test("items and complete settings survive reopening; order, normalization and delete are deterministic") {
    temporary.use { home =>
      val settings = WidgetSettings(Some(-100), Some(80), width = 320, topMost = false)
      for
        before <- WidgetService.resource(home).use { service => for
          _ <- service.set(WidgetUpdate("z", " 状态 ", Some("  "), WidgetColor.yellow, 10))
          _ <- service.set(WidgetUpdate("a", "下载", Some("完成"), order = -1))
          _ <- service.writeConfig(settings)
          absent <- service.delete("absent")
          _ = assertEquals(absent, false)
          current <- snapshot(service)
        yield current }
        after <- WidgetService.resource(home).use { service => for
          loaded <- snapshot(service)
          _ = assertEquals(loaded.copy(serverTime = before.serverTime), before)
          _ = assertEquals(loaded.revision, "3")
          _ = assertEquals(loaded.items.map(_.key), Vector("a", "z"))
          _ = assertEquals(loaded.items.last.value, None)
          removed <- service.delete("a")
          _ = assert(removed)
          current <- snapshot(service)
        yield current }
        _ = assertEquals(after.revision, "4")
        _ = assertEquals(after.items.map(_.key), Vector("z"))
      yield ()
    }
  }

  test("concurrent writers commit all items and revisions without lost updates") {
    temporary.use(home => WidgetService.resource(home).use { service => for
      _ <- (1 to 50).toList.parTraverse_(i => service.set(WidgetUpdate(s"key-$i", s"$i")))
      result <- snapshot(service)
    yield {
      assertEquals(result.revision, "50")
      assertEquals(result.items.size, 50)
      assertEquals(result.items.map(_.key).distinct.size, 50)
    }
    })
  }

  test("validation and item limits reject mutations without changing state") {
    temporary.use(home => WidgetService.resource(home).use { service => for
      _ <- service.set(WidgetUpdate("unicode", "🙂" * 24, Some("🙂" * 16)))
      initial <- snapshot(service)
      invalid <- List(WidgetUpdate("bad key", "x"), WidgetUpdate("valid", " "),
        WidgetUpdate("valid", "🙂" * 25), WidgetUpdate("valid", "a", Some("x" * 40)),
        WidgetUpdate("valid", "a\nb")).traverse(service.set(_).attempt)
      _ = assert(invalid.forall(_.swap.toOption.exists(_.isInstanceOf[InvalidWidget])))
      configErrors <- List(WidgetSettings(x = Some(0)), WidgetSettings(width = 1), WidgetSettings(opacity = Double.NaN),
        WidgetSettings(fontSize = 32, rowHeight = 24)).traverse(service.writeConfig(_).attempt)
      _ = assert(configErrors.forall(_.isLeft))
      unchanged <- snapshot(service)
      _ = assertEquals(unchanged.revision, initial.revision)
      _ <- (1 until WidgetService.maxItems).toList.traverse_(i => service.set(WidgetUpdate(s"key-$i", "x")))
      overflow <- service.set(WidgetUpdate("overflow", "x")).attempt
      _ = assert(overflow.swap.toOption.exists(_.isInstanceOf[WidgetLimit]))
      _ <- service.set(WidgetUpdate("unicode", "replacement"))
      size <- service.show.map(_.size)
    yield assertEquals(size, WidgetService.maxItems) })
  }

  test("SQLite failure never publishes uncommitted state; the ownership lock is released on close") {
    temporary.use { home =>
      WidgetService.resource(home).use { service => for
        _ <- service.set(WidgetUpdate("keep", "old"))
        owned <- WidgetService.resource(home).use(_ => IO.unit).attempt
        _ = assert(owned.isLeft)
        _ <- IO.blocking {
          Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${home.resolve("widget.sqlite3")}")) { db =>
            Using.resource(db.createStatement())(_.execute("CREATE TRIGGER reject_update BEFORE UPDATE ON widget BEGIN SELECT RAISE(ABORT, 'test failure'); END"))
          }
        }
        failed <- service.set(WidgetUpdate("keep", "new")).attempt
        _ = assert(failed.isLeft)
        current <- snapshot(service)
      yield {
        assertEquals(current.revision, "1")
        assertEquals(current.items.head.label, "old")
      }
      } *> WidgetService.resource(home).use(service => service.show.map(items => assertEquals(items.head.label, "old")))
    }
  }

  test("subscription sends initial, changed and idle snapshots; slow consumers converge and cancellation releases") {
    temporary.use(home => WidgetService.resource(home, 30.millis).use { service => for
      idle <- service.subscribe.take(2).compile.toVector.timeout(3.seconds)
      _ = assertEquals(idle.map(_.revision), Vector("0", "0"))
      _ = assert(idle(0).serverTime != idle(1).serverTime)
      first <- Deferred[IO, Unit]
      resume <- Deferred[IO, Unit]
      closed <- Deferred[IO, Unit]
      converged <- Deferred[IO, Unit]
      received <- Ref.of[IO, Vector[WidgetSnapshot]](Vector.empty)
      fiber <- service.subscribe.evalMap { value =>
        received.update(_ :+ value) *> (if value.revision == "0" then first.complete(()).void *> resume.get
          else if value.revision == "100" then converged.complete(()).void else IO.unit)
      }.onFinalize(closed.complete(()).void).compile.drain.start
      _ <- (for
        _ <- first.get.timeout(3.seconds)
        _ <- (1 to 100).toList.traverse_(i => service.set(WidgetUpdate("value", s"$i")))
        _ <- resume.complete(())
        _ <- converged.get.timeout(3.seconds)
        defWait = service.subscribe.filter(_.revision == "100").take(1).compile.lastOrError
        latest <- defWait.timeout(3.seconds)
        _ = assertEquals(latest.items.head.label, "100")
        _ <- fiber.cancel
        _ <- closed.get.timeout(3.seconds)
        values <- received.get
        _ = assert(values.map(_.revision.toLong) == values.map(_.revision.toLong).sorted)
      yield ()).guarantee(fiber.cancel)
    yield () })
  }

  test("ordinary body-only SDK commands, complete config replacement and CLI-only SSE declaration") {
    temporary.use(home => WidgetService.resource(home).use { service =>
      val app = httpApp { WidgetRoutes.register(service) }
      val sdk = GraalCommandSdk(app)
      assert(!sdk.commands.contains("widget.subscribe"))
      assertEquals(app.endpoints.find(_.path == "/widget/subscribe").get.exposedOn, Set(cli))
      for
        set <- sdk.call("widget.set", Json.obj("key" -> Json.fromString("test"), "label" -> Json.fromString("测试")))
        _ = assertEquals(set.hcursor.get[Int]("status"), Right(200))
        _ <- sdk.call("widget.config.write", Json.obj("width" -> Json.fromInt(300), "topMost" -> Json.False))
        _ <- sdk.call("widget.config.write", Json.obj())
        config <- service.config
        _ = assertEquals(config, WidgetSettings())
        response <- app.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/widget/subscribe")))
        line <- response.body.through(fs2.text.utf8.decode).through(fs2.text.lines).take(1).compile.lastOrError.timeout(3.seconds)
        _ = assertEquals(response.status, Status.Ok)
        value = io.circe.parser.parse(line.stripPrefix("data: ")).toOption.get
        _ = assertEquals(value.hcursor.get[String]("revision"), Right("3"))
        _ = assert(!value.noSpaces.contains("ttl"))
      yield ()
    })
  }
