package groot.graaljs

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.sql.DriverManager
import munit.CatsEffectSuite
import org.http4s.{Header, MediaType, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scala.util.Using
import GraalTestSupport.*

class GraalManagerSuite extends CatsEffectSuite:
  private case class Invocation(task: GraalTask, source: String, done: Deferred[IO, RunResult], cancellation: Deferred[IO, TaskError])
  private class StubRunner(val calls: Ref[IO, Vector[Invocation]], cleanup: IO[Unit]) extends GraalRunner:
    def start(task: GraalTask, source: String, log: Path): IO[GraalExecution] =
      for
        done <- Deferred[IO, RunResult]
        cancellation <- Deferred[IO, TaskError]
        _ <- calls.update(_ :+ Invocation(task, source, done, cancellation))
      yield new GraalExecution:
        def result = done.get
        def cancel(reason: TaskError) =
          val state = if reason.code == "cancelled" || reason.code == "interrupted" then reason.code else "failed"
          cancellation.complete(reason).void *> cleanup *> done.complete(RunResult(state, Some(1d), 2d, Some(reason))).void

    def invocation(id: String): IO[Invocation] = calls.get.map(_.find(_.task.id == id).get)
    def finish(id: String): IO[Unit] = invocation(id).flatMap(_.done.complete(RunResult("succeeded", Some(1d), 2d, None))).void

  private def stub(cleanup: IO[Unit] = IO.unit): IO[StubRunner] = Ref.of[IO, Vector[Invocation]](Vector.empty).map(new StubRunner(_, cleanup))
  private val source = "  const message = '中文🙂';\nconsole.log(message, \"quoted\");\n"
  private def submit(manager: GraalManager, timeout: String = "30s") = manager.start(source, "10s", timeout)
  private def append(path: Path, text: String): IO[Unit] = IO.blocking { Files.writeString(path, text, StandardOpenOption.APPEND); () }

  test("concurrent admissions reserve exactly sixteen slots and release them in FIFO order") {
    temporary.use { home =>
      for
        runner <- stub()
        _ <- GraalManager.managed(home.resolve("data"), Resource.pure(runner)).use { manager =>
          for
            _ <- (1 to 19).toList.parTraverse(_ => submit(manager))
            active <- manager.list
            _ = assertEquals(active.count(_.state == "running"), 16)
            queued = active.filter(_.state == "queued")
            _ = assertEquals(queued.size, 3)
            cancelled <- manager.cancel(queued(1).id)
            _ = assertEquals(cancelled.state, "cancelled")
            _ <- runner.finish(active.head.id)
            _ <- awaitUntil(manager.status(queued.head.id))(_.state == "running")
            first <- runner.invocation(queued.head.id)
            _ = assertEquals(first.source, source)
            _ <- runner.finish(active(1).id)
            _ <- awaitUntil(manager.status(queued(2).id))(_.state == "running")
            calls <- runner.calls.get
            _ = assertEquals(calls.takeRight(2).map(_.task.id), Vector(queued.head.id, queued(2).id))
            _ = assert(!calls.exists(_.task.id == cancelled.id))
            repeated <- manager.cancel(cancelled.id)
          yield assertEquals(repeated, cancelled)
        }
      yield ()
    }
  }

  test("cancellation keeps the reservation until cleanup finishes, and queued time does not consume timeout") {
    temporary.use { home =>
      for
        release <- Deferred[IO, Unit]
        runner <- stub(release.get)
        _ <- GraalManager.managed(home.resolve("data"), Resource.pure(runner), Limits.taskBytes).use { manager =>
          (for
            first <- submit(manager)
            second <- submit(manager, "50ms")
            _ <- IO.sleep(100.millis)
            waiting <- manager.status(second.id)
            _ = assertEquals(waiting.state, "queued")
            cancelled <- manager.cancel(first.id)
            _ = assertEquals(cancelled.state, "cancelling")
            call <- runner.invocation(first.id)
            _ <- call.cancellation.get
            stillWaiting <- manager.status(second.id)
            _ = assertEquals(stillWaiting.state, "queued")
            _ <- release.complete(())
            finalState <- awaitUntil(manager.status(second.id))(_.terminal)
            _ = assertEquals(finalState.error.map(_.code), Some("timeout"))
            _ = assert(finalState.elapsedTimeMs >= 100d)
          yield ()).guarantee(release.complete(()).void)
        }
      yield ()
    }
  }

  test("file followers replay, stream split UTF-8 without loss, terminate after summary and can disconnect independently") {
    temporary.use { home =>
      for
        runner <- stub()
        _ <- GraalManager.managed(home.resolve("data"), Resource.pure(runner)).use { manager =>
          for
            task <- submit(manager)
            path = Path.of(task.logPath)
            _ <- append(path, "first\n")
            short <- manager.follow(task.id)
            first <- short.events.take(1).compile.toList
            _ = assertEquals(first.map(_.toString), List("first"))
            state <- manager.status(task.id)
            _ = assertEquals(state.state, "running")
            stream <- manager.follow(task.id)
            fiber <- stream.events.compile.toList.start
            bytes = "中文🙂\n".getBytes(UTF_8)
            _ <- IO.blocking(Files.write(path, bytes.take(1), StandardOpenOption.APPEND))
            partial <- manager.logs(task.id)
            _ = assertEquals(partial.toString, "first\n")
            _ <- IO.sleep(150.millis)
            _ <- IO.blocking(Files.write(path, bytes.drop(1), StandardOpenOption.APPEND))
            _ <- append(path, "last without newline")
            _ <- runner.finish(task.id)
            lines <- fiber.joinWithNever.timeout(5.seconds)
            snapshot <- manager.logs(task.id)
            _ = assertEquals(lines.mkString("\n").trim, snapshot.toString.trim)
            _ = assertEquals(lines.count(_.contains("state=succeeded")), 1)
            completed <- manager.follow(task.id).flatMap(_.events.compile.toList)
          yield assertEquals(completed, lines)
        }
      yield ()
    }
  }

  test("SQLite retains completed tasks and source snapshots; recovery interrupts abandoned tasks and ownership is exclusive") {
    temporary.use { home =>
      val data = home.resolve("data")
      for
        runner <- stub()
        id <- GraalManager.managed(data, Resource.pure(runner)).use { manager =>
          for
            second <- GraalManager.managed(data, Resource.pure(runner)).use(_ => IO.unit).attempt
            _ = assert(second.isLeft)
            task <- submit(manager)
            _ <- runner.finish(task.id)
            _ <- awaitUntil(manager.status(task.id))(_.terminal)
          yield task.id
        }
        _ <- GraalStore.resource(data).use { store =>
          for
            saved <- store.get(id)
            _ = assertEquals(saved.state, "succeeded")
            savedSource <- store.source(id)
            _ = assertEquals(savedSource, source)
            _ <- store.save(saved.copy(state = "running", finishedAt = None, cpuTimeMs = None))
            _ <- IO.blocking(Files.write(store.logPath(id), Array(0xe4.toByte), StandardOpenOption.APPEND))
          yield ()
        }
        // Older task JSON includes file metadata. It must remain readable after the API change.
        _ <- IO.blocking {
          Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${data.resolve("tasks.sqlite3")}")) { db =>
            Using.resource(db.createStatement()) { statement =>
              statement.executeUpdate("UPDATE tasks SET task_json = json_set(task_json, '$.script', '/old/script.js', '$.cwd', '/old')")
            }
          }
        }
        _ <- GraalManager.managed(data, Resource.pure(runner)).use { manager =>
          for
            recovered <- manager.status(id)
            _ = assertEquals(recovered.state, "interrupted")
            _ = assertEquals(recovered.cpuTimeMs, None)
            logs <- manager.logs(id)
            _ = assert(logs.contains("state=interrupted"))
            replay <- manager.follow(id).flatMap(_.events.compile.toList)
            _ = assert(replay.exists(_.contains("state=interrupted")))
            active <- manager.list
          yield assertEquals(active, Nil)
        }
        header <- IO.blocking(Files.readAllBytes(data.resolve("tasks.sqlite3")).take(16))
      yield assertEquals(new String(header, UTF_8), "SQLite format 3\u0000")
    }
  }

  test("HTTP validates required CPU/timeout, exposes text and SSE, and reserves notifications with 501") {
    temporary.use { home =>
      for
        runner <- stub()
        _ <- GraalManager.managed(home.resolve("data"), Resource.pure(runner)).use { manager =>
          val app = httpApp { GraalJsRoutes.register(manager) }
          def request(path: String, query: (String, String)*) =
            Request[IO](uri = Uri.unsafeFromString(path).withQueryParams(query.toMap))
          val input = Seq("cpu" -> "1s", "timeout" -> "10s")
          def start(query: Seq[(String, String)] = input, code: String = source) =
            request("/graaljs/start", query*).withMethod(Method.POST).withEntity(code)
          for
            created <- app.httpApp(start())
            json <- created.as[Json]
            _ = assertEquals(created.status, Status.Ok)
            id = json.hcursor.get[String]("id").toOption.get
            call <- runner.invocation(id)
            _ = assertEquals(call.source, source)
            _ = assert(!json.asObject.get.contains("script") && !json.asObject.get.contains("cwd"))
            missingCpu <- app.httpApp(start(input.filterNot(_._1 == "cpu")))
            _ = assertEquals(missingCpu.status, Status.BadRequest)
            missingTimeout <- app.httpApp(start(input.filterNot(_._1 == "timeout")))
            _ = assertEquals(missingTimeout.status, Status.BadRequest)
            badCpu <- app.httpApp(start(input.filterNot(_._1 == "cpu") :+ ("cpu" -> "0s")))
            _ = assertEquals(badCpu.status, Status.BadRequest)
            missingBody <- app.httpApp(request("/graaljs/start", input*).withMethod(Method.POST))
            _ = assertEquals(missingBody.status, Status.BadRequest)
            _ <- List("", " \n\t").traverse_ { code =>
              app.httpApp(start(code = code)).map(response => assertEquals(response.status, Status.BadRequest))
            }
            unsupported <- app.httpApp(start().putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
            _ = assertEquals(unsupported.status, Status.UnsupportedMediaType)
            oversized <- app.httpApp(start(code = "中" * (Limits.maxSourceBytes / 3 + 1)))
            _ = assertEquals(oversized.status, Status.PayloadTooLarge)
            invalidUtf8 <- app.httpApp(start().withBodyStream(fs2.Stream.emits(Array[Byte](0xc3.toByte, 0x28)).covary[IO]))
            _ = assertEquals(invalidUtf8.status, Status.BadRequest)
            calls <- runner.calls.get
            _ = assertEquals(calls.size, 1)
            absent <- app.httpApp(request("/graaljs/logs-follow", "id" -> "../../outside"))
            _ = assertEquals(absent.status, Status.NotFound)
            notify <- app.httpApp(request("/graaljs/notify-on-finish", "id" -> id))
            _ = assertEquals(notify.status, Status.NotImplemented)
            _ <- runner.finish(id)
            _ <- awaitUntil(manager.status(id))(_.terminal)
            text <- app.httpApp(request("/graaljs/logs", "id" -> id))
            _ = assertEquals(text.contentType.map(_.mediaType), MediaType.parse("text/plain").toOption)
            sse <- app.httpApp(request("/graaljs/logs-follow", "id" -> id))
            _ = assertEquals(sse.contentType.map(_.mediaType), MediaType.parse("text/event-stream").toOption)
            wire <- sse.as[String]
            _ = assert(wire.contains("data: [graaljs] state=succeeded"))
            openapi <- app.httpApp(request("/openapi.json")).flatMap(_.as[Json])
          yield
            assertEquals(openapi.hcursor.downField("paths").focus.get.asObject.get.size, 7)
            val operation = openapi.hcursor.downField("paths").downField("/graaljs/start").downField("post")
            assertEquals(operation.downField("requestBody").get[Boolean]("required"), Right(true))
            assertEquals(operation.downField("requestBody").downField("content").downField("text/plain").downField("schema").get[String]("type"), Right("string"))
            assertEquals(operation.get[List[Json]]("parameters").toOption.get.map(_.hcursor.get[String]("name").toOption.get), List("cpu", "timeout"))
        }
      yield ()
    }
  }
