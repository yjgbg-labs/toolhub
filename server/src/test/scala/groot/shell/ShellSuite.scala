package groot.shell

import cats.effect.IO
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import io.circe.Json
import java.nio.file.Files
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

class ShellSuite extends CatsEffectSuite:
  test("environment CRUD persists across restarts and deleted defaults stay deleted") {
    temporary.use { home =>
      val local = ShellEnvironment("local")
      for
        _ <- ShellService.resource(home, Vector(local)).use { service =>
          val app = httpApp { ShellRoutes.register(service) }
          val sdk = GraalCommandSdk(app)
          for
            _ <- IO(assertEquals(sdk.commands, Vector("shell.env.add", "shell.env.delete", "shell.env.list", "shell.execute")))
            added <- sdk.call("shell.env.add", Json.obj("name" -> Json.fromString("custom")))
            _ = assertEquals(added.hcursor.get[Int]("status"), Right(200))
            listed <- service.list
            _ = assertEquals(listed, Vector(ShellEnvironment("custom"), local))
            duplicate <- sdk.call("shell.env.add", Json.obj("name" -> Json.fromString("custom")))
            _ = assertEquals(duplicate.hcursor.get[Int]("status"), Right(409))
            missing <- sdk.call("shell.execute", JsonModel[ShellRequest].encoder(ShellRequest("missing", "exit 0")))
            _ = assertEquals(missing.hcursor.get[Int]("status"), Right(404))
            _ <- GraalManager.resource(home.resolve("tasks"), IO.pure(sdk)).use { manager => for
              task <- manager.start(JavaScript.source("""
                const r = (await tools.shell.execute({env:'custom',script:"printf 'named'; exit 7"})).body;
                if (r.stdout !== 'named' || r.exitCode !== 7) throw Error(JSON.stringify(r));
                await tools.shell.env.delete({name:'local'});
                if ((await tools.shell.env.list()).body.length !== 1) throw Error('environment deletion');
              """), "1s", "10s")
              done <- manager.await(task.id)
              _ = assertEquals(done.state, "succeeded", done.error.toString)
            yield () }
            removed <- sdk.call("shell.env.delete", Json.obj("name" -> Json.fromString("local")))
            _ = assertEquals(removed.hcursor.get[Int]("status"), Right(404))
            _ <- List("bash", "powershell", "zshOnMac", "bashOnR6s").traverse_ { name =>
              app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString(s"/$name"))).map(r => assertEquals(r.status, Status.NotFound))
            }
          yield ()
        }
        _ <- ShellService.resource(home, Vector(local)).use { service => for
          listed <- service.list
          _ = assertEquals(listed, Vector(ShellEnvironment("custom")))
          result <- service.execute(ShellRequest("custom", "printf persisted"))
          _ = assertEquals(result.stdout, "persisted")
          _ <- service.delete("custom")
        yield () }
        _ <- ShellService.resource(home, Vector(local)).use(_.list.map(values => assertEquals(values, Vector.empty)))
      yield ()
    }
  }

  test("invalid environments are rejected and concurrent registration cannot overwrite a name") {
    temporary.use { home => ShellService.resource(home, Vector.empty).use { service =>
      val sdk = GraalCommandSdk(httpApp { ShellRoutes.register(service) })
      val invalid = Vector(
        ShellEnvironment(" "), ShellEnvironment("nul\u0000name"), ShellEnvironment("x" * 129),
        ShellEnvironment("mac", ShellKind.zsh), ShellEnvironment("windows", ShellKind.powershell),
        ShellEnvironment("option", target = Some("-oProxyCommand=evil")), ShellEnvironment("empty", target = Some("")),
        ShellEnvironment("space", target = Some("user@host other")), ShellEnvironment("idle", idle = "0s"), ShellEnvironment("idle", idle = "1ms")
      )
      for
        _ <- invalid.traverse_ { value => sdk.call("shell.env.add", JsonModel[ShellEnvironment].encoder(value)).map { response =>
          assertEquals(response.hcursor.get[Int]("status"), Right(400), response.noSpaces)
        } }
        race <- List.fill(2)(service.add(ShellEnvironment("winner")).attempt).parSequence
        _ = assertEquals(race.count(_.isRight), 1)
        _ = assertEquals(race.flatMap(_.swap.toOption).map(_.asInstanceOf[ShellFailure].code), List("shell_exists"))
        _ <- service.add(ShellEnvironment("remote", target = Some("example.invalid")))
        invalidCwd <- sdk.call("shell.execute", JsonModel[ShellRequest].encoder(ShellRequest("remote", "pwd", cwd = Some("/tmp"))))
        _ = assertEquals(invalidCwd.hcursor.get[Int]("status"), Right(400))
      yield ()
    } }
  }

  test("a registry has one owner and invalid persisted data is preserved rather than reseeded") {
    temporary.use { home => for
      _ <- ShellService.resource(home, Vector.empty).use { _ =>
        ShellService.resource(home, Vector.empty).use(_ => IO.unit).attempt.map(result => assert(result.isLeft))
      }
      path = home.resolve("environments.json")
      corrupt = "{broken registry"
      _ <- IO.blocking(Files.writeString(path, corrupt))
      failed <- ShellService.resource(home, Vector(ShellEnvironment("local"))).use(_ => IO.unit).attempt
      _ = assert(failed.isLeft)
      saved <- IO.blocking(Files.readString(path))
      _ = assertEquals(saved, corrupt)
      _ <- IO.blocking(Files.writeString(path, "{\"version\":1,\"environments\":[]}"))
      _ <- ShellService.resource(home, Vector.empty).use(_.list.map(values => assertEquals(values, Vector.empty)))
    yield () }
  }
