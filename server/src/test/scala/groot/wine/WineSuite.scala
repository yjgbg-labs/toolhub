package groot.wine

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.GraalCommandSdk
import io.circe.Json
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class WineSuite extends CatsEffectSuite:
  override val munitIOTimeout = 180.seconds
  private def home: Resource[IO, Path] = Resource.make(IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "wine-service-")))(p =>
    IO.blocking(Using.resource(Files.walk(p))(_.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists))))
  test("generated routes use structured bodies, validate names and retain fixed prefix architecture") {
    home.use(p => WineService.managed(WineConfig.default.copy(home = p, runtime = p.resolve("missing"))).use { service =>
      val sdk = GraalCommandSdk(httpApp { WineRoutes.register(service) })
      for
        empty <- sdk.call("wine.instance.list", Json.obj())
        _ = assertEquals(empty.hcursor.get[Int]("status"), Right(200))
        invalid <- sdk.call("wine.instance.start", Json.obj("instanceName" -> Json.fromString("../bad")))
        _ = assertEquals(invalid.hcursor.get[Int]("status"), Right(400))
        absent <- sdk.call("wine.instance.start", Json.obj("instanceName" -> Json.fromString("sample")))
        _ = assertEquals(absent.hcursor.get[Int]("status"), Right(503))
        stopped <- service.list
        _ = assertEquals(stopped.map(_.status), Vector("stopped"))
        removed <- service.purge("sample")
        _ = assert(removed)
      yield ()
    })
  }
  if sys.env.get("WINE_TEST").contains("1") then test("real Wine sessions, EXEs, timeout isolation, prefix persistence and recovery") {
    home.use { p => WineService.managed(WineConfig.default.copy(home = p)).use { service => for
      instance <- service.start("test", "win64", true)
      _ = assertEquals(instance.status, "running")
      again <- service.start("test", "win64", true)
      _ = assertEquals(instance, again)
      _ <- service.load("test", "kernel", WineLoad("native", Some("kernel32.dll")))
      length <- service.call("test", "kernel", WineCall("lstrlenW", Vector(WineArgument("utf16", Json.fromString("hello 世界"))), returnType = "i32"), 10000)
      _ = assertEquals(length.hcursor.get[Int]("value"), Right(8))
      _ <- service.load("test", "managed", WineLoad("dotnet", Some(Path.of("wine/dist/fixture/Fixture.dll").toAbsolutePath.toString)))
      managed <- service.call("test", "managed", WineCall("Sum", Vector(WineArgument("i32", Json.fromInt(20)), WineArgument("i32", Json.fromInt(22))), typeName = Some("Fixture.Calculator")), 10000)
      _ = assertEquals(managed.hcursor.get[Int]("value"), Right(42))
      _ <- service.load("test", "dictionary", WineLoad("com", classId = Some("Scripting.Dictionary")))
      _ <- service.call("test", "dictionary", WineCall("Add", Vector(WineArgument("string", Json.fromString("answer")), WineArgument("i32", Json.fromInt(42)))), 10000)
      count <- service.call("test", "dictionary", WineCall("Count", operation = "get"), 10000)
      _ = assertEquals(count.hcursor.get[Int]("value"), Right(1))
      task <- service.execute("test", WineExec("cmd.exe", Vector("/d", "/c", "echo wine-ok & exit /b 7")))
      finished <- service.await(task.id, 10000)
      _ = assertEquals(finished.exitCode, Some(7L))
      logs <- service.logs(task.id, "stdout")
      _ = assert(logs.contains("wine-ok"))
      executable = WineConfig.default.runtime.resolve("dotnet-x64/dotnet.exe").toString
      fixture = WineValidation.windows(Path.of("wine/dist/fixture/Fixture.dll").toAbsolutePath.toString)
      arguments = Vector("", "a b", "中文", "a\"b", "ends\\", "&literal")
      echo <- service.execute("test", WineExec(executable, Vector(fixture, "args") ++ arguments))
      echoDone <- service.await(echo.id, 10000)
      _ = assertEquals(echoDone.exitCode, Some(7L))
      echoed <- service.logs(echo.id, "stdout")
      _ = assertEquals(echoed.replace("\r", "").split("\n").toVector,
        arguments.map(s => java.util.Base64.getEncoder.encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
      slow <- service.execute("test", WineExec(executable, Vector(fixture, "hang")))
      short <- service.await(slow.id, 100).attempt
      _ = assert(short.isLeft)
      ongoing <- service.status(slow.id)
      _ = assertEquals(ongoing.state, "running")
      cancelled <- service.cancel(slow.id)
      _ = assertEquals(cancelled.state, "cancelled")
      timed <- service.execute("test", WineExec(executable, Vector(fixture, "hang"), timeoutMs = 500))
      timedResult <- service.await(timed.id, 10000)
      _ = assertEquals(timedResult.state, "timed_out")
      marker = p.resolve("descendant.txt")
      parent <- service.execute("test", WineExec(executable, Vector(fixture, "spawn", WineValidation.windows(marker.toString))))
      _ <- { def ready: IO[Unit] = IO.blocking(Files.exists(marker)).flatMap(found => if found then IO.unit else IO.sleep(50.millis) *> ready)
        ready.timeout(10.seconds) }
      _ <- service.cancel(parent.id)
      before <- IO.blocking(Files.size(marker))
      _ <- IO.sleep(300.millis)
      after <- IO.blocking(Files.size(marker))
      _ = assertEquals(after, before, "Cancelling a task must kill its Windows grandchildren")
      _ <- service.load("test", "legacy", WineLoad("dotnet", Some(Path.of("wine/dist/fixture-framework/Fixture.exe").toAbsolutePath.toString), runtime = "framework"))
      legacy <- service.call("test", "legacy", WineCall("Sum", Vector(WineArgument("i32", Json.fromInt(1)), WineArgument("i32", Json.fromInt(2))), typeName = Some("Fixture.Calculator")), 10000)
      _ = assertEquals(legacy.hcursor.get[Int]("value"), Right(3))
      registry <- service.execute("test", WineExec("reg.exe", Vector("add", "HKCU\\Software\\SproutFixture", "/v", "Persist", "/d", "kept", "/f")))
      registryDone <- service.await(registry.id, 10000)
      _ = assertEquals(registryDone.exitCode, Some(0L))
      hung <- service.call("test", "managed", WineCall("Hang", typeName = Some("Fixture.Calculator")), 200).attempt
      _ = assert(hung.swap.toOption.exists { case e: WineFailure => e.code == "wine_timeout"; case _ => false })
      still <- service.call("test", "dictionary", WineCall("Count", operation = "get"), 10000)
      _ = assertEquals(still.hcursor.get[Int]("value"), Right(1))
      _ <- service.stop("test")
      _ <- service.start("test", "win64", true)
      persisted <- service.execute("test", WineExec("reg.exe", Vector("query", "HKCU\\Software\\SproutFixture", "/v", "Persist")))
      _ <- service.await(persisted.id, 10000)
      persistedLog <- service.logs(persisted.id, "stdout")
      _ = assert(persistedLog.contains("kept"))
      _ <- service.load("test", "kernel", WineLoad("native", Some("kernel32.dll")))
      _ <- service.purge("test")
      states <- service.list
      _ = assertEquals(states, Vector.empty)
      history <- service.status(task.id)
      _ = assertEquals(history.exitCode, Some(7L))
    yield () } }
  }
