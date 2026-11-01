package groot.bashonr6s

import cats.effect.IO
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import io.circe.Json
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import scala.concurrent.duration.*

class BashOnR6sSuite extends CatsEffectSuite:
  // Simulate the SSH control socket, but execute the real Linux bootstrap and cancellation code.
  private def transport(home: Path): IO[Path] = IO.blocking {
    val file = home.resolve("ssh-test")
    Files.writeString(file, """#!/bin/bash
      set -eu
      root=$(dirname "$0")
      remote=${!#}
      socket= op= start=false persist=false
      while (($#)); do
        case "$1" in
          -S) socket=$2; shift ;;
          -O) op=$2; shift ;;
          -fN) start=true ;;
          ControlPersist=300) persist=true ;;
        esac
        shift
      done
      if [[ $op == check ]]; then test -f "$socket.alive"; exit $?; fi
      if [[ $op == exit ]]; then rm -f "$socket.alive"; printf yes > "$root/closed"; exit 0; fi
      if $start; then
        $persist || exit 99
        touch "$socket.alive"
        printf 'start\n' >> "$root/masters"
        exit 0
      fi
      if [[ "$remote" == *'kill -TERM'* ]]; then
        printf 'cancel\n' >> "$root/cancelled"
      else
        cat > "$root/received"
        exec 0< "$root/received"
      fi
      printf 'exit 99\n' > "$root/poison"
      export BASH_ENV="$root/poison"
      # Model sshd's independent remote session rather than inheriting the local SSH group.
      eval "exec /usr/bin/setsid --fork --wait ${remote#exec }"
      """)
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    file
  }

  test("HTTP and GraalJS execute Bash with string body, query timeout and reusable SSH") {
    temporary.use { home => for
      ssh <- transport(home)
      source = "printf '%s\\n' '中文🙂'\nprintf 'stderr\\n' >&2\nexit 7"
      _ <- BashOnR6sService.resource(home.resolve("pool"), ssh = ssh.toString).use { service =>
        val app = httpApp { BashOnR6sRoutes.register(service) }
        val sdk = GraalCommandSdk(app)
        for
          _ <- IO(assertEquals(app.endpoints.head.exposedOn, Set(framework.Client.cli, framework.Client.graaljs, framework.Client.codemode)))
          operation = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths").downField("/bash-on-r6s").downField("post")
          _ = assertEquals(operation.downField("requestBody").downField("content").downField("application/json").downField("schema").get[String]("type"), Right("string"))
          timeout = operation.get[Vector[Json]]("parameters").toOption.get.find(_.hcursor.get[String]("name") == Right("timeout")).get.hcursor
          _ = assertEquals(timeout.get[String]("in"), Right("query"))
          _ = assertEquals(timeout.get[Boolean]("required"), Right(false))
          _ = assertEquals(timeout.downField("schema").get[String]("default"), Right("30s"))
          response <- app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/bash-on-r6s?timeout=10s"))
            .withEntity(Json.fromString(source).noSpaces)
            .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json")))
          body <- response.as[String]
          _ = assertEquals(response.status, Status.Ok)
          _ = assertEquals(io.circe.parser.parse(body).toOption.get.hcursor.get[Int]("exitCode"), Right(7), body)
          payload <- IO.blocking(Files.readString(home.resolve("received")))
          _ = assertEquals(payload, source + "\n")
          _ <- GraalManager.resource(home.resolve("tasks"), IO.pure(sdk)).use { manager => for
            task <- manager.start(JavaScript.source(s"const r=(await tools['bash-on-r6s']({body:${Json.fromString(source).noSpaces}})).body;if(r.exitCode!==7||r.stdout!=='中文🙂\\n'||r.stderr!=='stderr\\n')throw Error(JSON.stringify(r));"), "1s", "10s")
            done <- manager.await(task.id)
            _ = assertEquals(done.state, "succeeded", done.error.toString)
          yield () }
          masters <- IO.blocking(Files.readString(home.resolve("masters")))
          _ = assertEquals(masters, "start\n")
        yield ()
      }
      closed <- IO.blocking(Files.readString(home.resolve("closed")))
      _ = assertEquals(closed, "yes")
    yield () }
  }

  test("validation avoids SSH; output limits, timeout and cancellation clean up real process groups") {
    temporary.use { home => for
      ssh <- transport(home)
      slow = s"printf '%s' $$$$ > '${home.resolve("pid")}'; sleep 30"
      _ <- BashOnR6sService.resource(home.resolve("pool"), ssh = ssh.toString).use { service =>
        val sdk = GraalCommandSdk(httpApp { BashOnR6sRoutes.register(service) })
        def call(script: String, timeout: String = "30s") = sdk.call("bash-on-r6s", Json.obj("body" -> Json.fromString(script), "timeout" -> Json.fromString(timeout)))
        for
          _ <- List((" ", "30s"), ("x\u0000", "30s"), ("x", ""), ("x", "0s"), ("x", "oops"))
            .traverse_((script, timeout) => call(script, timeout).map(result => assertEquals(result.hcursor.get[Int]("status"), Right(400))))
          largeInput <- call("x" * (BashOnR6sService.maxBytes + 1))
          _ = assertEquals(largeInput.hcursor.get[Int]("status"), Right(413))
          connected <- IO.blocking(Files.exists(home.resolve("masters")))
          _ = assert(!connected)
          largeOutput <- call("yes x").timeout(5.seconds)
          _ = assertEquals(largeOutput.hcursor.get[Int]("status"), Right(413), largeOutput.noSpaces)
          timed <- call(slow, "500ms")
          _ = assertEquals(timed.hcursor.get[Int]("status"), Right(504))
          pid <- IO.blocking(Files.readString(home.resolve("pid")).toLong)
          _ <- awaitUntil(IO(ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)))(!_)
          _ <- IO.blocking(Files.delete(home.resolve("pid")))
          _ <- service(slow).background.use(_ => awaitUntil(IO.blocking(Files.exists(home.resolve("pid"))))(identity).void)
          cancelledPid <- IO.blocking(Files.readString(home.resolve("pid")).toLong)
          _ <- awaitUntil(IO(ProcessHandle.of(cancelledPid).map[Boolean](_.isAlive).orElse(false)))(!_)
          after <- call("pwd")
          _ = assertEquals(after.hcursor.get[Int]("status"), Right(200))
          _ = assertEquals(after.hcursor.downField("body").get[Int]("exitCode"), Right(0))
          cancelled <- IO.blocking(Files.readString(home.resolve("cancelled")))
          _ = assertEquals(cancelled, "cancel\ncancel\ncancel\n")
        yield ()
      }
    yield () }
  }
