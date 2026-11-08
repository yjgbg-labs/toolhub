package groot.bash

import cats.effect.IO
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import groot.shell.{ShellEnvironment, ShellRequest, ShellRoutes, ShellService}
import io.circe.Json
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import scala.concurrent.duration.*

class BashSuite extends CatsEffectSuite:
  private val service = new BashService
  private def registered = temporary.flatMap(home => ShellService.resource(home.resolve("shell"), Vector(ShellEnvironment("local")))).map { shell =>
    val app = httpApp { ShellRoutes.register(shell) }
    (app, GraalCommandSdk(app))
  }
  private def call(request: BashRequest) = registered.use { (_, sdk) =>
    sdk.call("shell.execute", JsonModel[ShellRequest].encoder(ShellRequest("local", request.script, request.timeout, request.cwd)))
  }
  private def quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
  private def running(pid: Long): IO[Boolean] = IO.blocking {
    val file = Path.of(s"/proc/$pid/stat")
    try
      val stat = Files.readString(file)
      !Set('Z', 'X').contains(stat.charAt(stat.lastIndexOf(')') + 2))
    catch case _: java.nio.file.NoSuchFileException => false
  }
  private def pids(path: Path): IO[List[Long]] =
    awaitUntil(IO.blocking(if Files.exists(path) then Files.readString(path).trim else ""))(_.matches("[0-9]+( [0-9]+)*"))
      .map(_.split(" ").toList.map(_.toLong))
  private def stopped(ids: List[Long]): IO[Unit] = ids.traverse_(id => awaitUntil(running(id))(!_).void)
  private def blocking(path: Path): String =
    s"trap '' TERM; (trap '' TERM; sleep 30) & echo \"$$$$ $$!\" > ${quote(path.toString)}; wait"

  test("local shell environment is exposed to all clients and preserves cwd, Unicode and nonzero exits") {
    registered.use { (app, _) => temporary.use { home =>
      for
        response <- call(BashRequest("printf '中文🙂\\n'; printf '%s' \"$PWD\" >&2; exit 7", Some(home.toString)))
        _ = assertEquals(response.hcursor.get[Int]("status"), Right(200))
        result = response.hcursor.downField("body")
        _ = assertEquals(result.get[Int]("exitCode"), Right(7))
        _ = assertEquals(result.get[String]("stdout"), Right("中文🙂\n"))
        _ = assertEquals(result.get[String]("stderr"), Right(home.toString))
        _ = assertEquals(app.endpoints.find(_.operationId == "shell.execute").get.exposedOn, Set(framework.Client.cli, framework.Client.codemode, framework.Client.graaljs))
        _ <- List(Method.GET, Method.POST).traverse_ { method =>
          app.httpApp(Request[IO](method, Uri.unsafeFromString("/bash"))).map(r => assertEquals(r.status, Status.NotFound))
        }
      yield ()
    } }
  }

  test("validates inputs and drains both streams concurrently with bounded output") {
    for
      _ <- List(BashRequest(" "), BashRequest("echo ok", Some("relative")), BashRequest("echo ok", timeout = "0s"),
        BashRequest("echo ok", timeout = "999999999999999999999d")).traverse_ { request =>
        call(request).map(result => assertEquals(result.hcursor.get[Int]("status"), Right(400)))
      }
      large <- call(BashRequest("x" * (BashService.maxBytes + 1)))
      _ = assertEquals(large.hcursor.get[Int]("status"), Right(413))
      output <- service(BashRequest("head -c 60000 /dev/zero & head -c 60000 /dev/zero >&2 & wait"))
      _ = assertEquals((output.exitCode, output.stdout.length, output.stderr.length), (0, 60000, 60000))
      // Overflow occurs while the process is still running; it must terminate, not wait for the timeout.
      overflow <- call(BashRequest("head -c 65537 /dev/zero; sleep 30")).timeout(5.seconds)
    yield assertEquals(overflow.hcursor.get[Int]("status"), Right(413))
  }

  test("timeout and cancellation kill a TERM-resistant process group before returning") {
    temporary.use { home =>
      for
        timed <- call(BashRequest(blocking(home.resolve("timed")), timeout = "500ms"))
        _ = assertEquals(timed.hcursor.get[Int]("status"), Right(504))
        timedIds <- pids(home.resolve("timed"))
        _ <- stopped(timedIds)
        _ <- service(BashRequest(blocking(home.resolve("cancelled")))).background.use { _ =>
          pids(home.resolve("cancelled")).void
        }.timeout(5.seconds)
        cancelledIds <- pids(home.resolve("cancelled"))
        _ <- stopped(cancelledIds)
      yield ()
    }
  }

  test("cancelling an exited leader leaves no inherited pipe owners") {
    temporary.use { home =>
      val path = home.resolve("orphan")
      // A leader can exit before its background child. Group cleanup must not depend on leader.isAlive.
      val script = s"(trap '' TERM; echo \"$$BASHPID\" > ${quote(path.toString)}; sleep 30) & sleep 0.1; exit 0"
      for
        _ <- service(BashRequest(script)).background.use(_ => pids(path).void *> IO.sleep(200.millis)).timeout(5.seconds)
        ids <- pids(path)
        _ <- stopped(ids)
      yield ()
    }
  }

  test("normal completion preserves intentionally detached processes with redirected IO") {
    for
      result <- service(BashRequest("sleep 30 </dev/null >/dev/null 2>&1 & echo $!"))
      pid = result.stdout.trim.toLong
      _ <- running(pid).map(alive => assert(alive)).guarantee(IO.blocking {
        ProcessHandle.of(pid).ifPresent(_.destroyForcibly()); ()
      })
    yield assertEquals(result.exitCode, 0)
  }

  test("real GraalJS SDK returns results and cancels subprocesses on task timeout and explicit cancellation") {
    registered.use { (_, sdk) => temporary.use { home => GraalManager.resource(home.resolve("tasks"), IO.pure(sdk)).use { manager =>
      for
        ok <- manager.start(JavaScript.source("const r=(await tools.shell.execute({env:'local',script:\"printf 'sdk'; exit 9\"})).body; if(r.exitCode!==9||r.stdout!=='sdk')throw Error(JSON.stringify(r)); console.log(r.stdout);"), "1s", "10s")
        done <- manager.await(ok.id)
        _ = assertEquals(done.state, "succeeded", done.error.toString)
        _ <- List(false, true).traverse_ { cancel =>
          val path = home.resolve(if cancel then "sdk-cancel" else "sdk-timeout")
          val source = "await tools.shell.execute({env:'local',script:" + Json.fromString(blocking(path)).noSpaces + "});"
          for
            task <- manager.start(JavaScript.source(source), "1s", if cancel then "10s" else "500ms")
            ids <- pids(path)
            _ <- if cancel then manager.cancel(task.id).void else IO.unit
            finished <- manager.await(task.id)
            _ = assertEquals(finished.state, if cancel then "cancelled" else "failed")
            _ <- stopped(ids)
          yield ()
        }
      yield ()
    } } }
  }
