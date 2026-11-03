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
  private def integer(value: Int): WineArgument = WineArgument("i32", Json.fromInt(value))
  test("one-shot API validates requests before lazy runtime setup") {
    home.use(p => WineService.managed(WineConfig.default.copy(home = p, runtime = p.resolve("missing"))).use { service =>
      val sdk = GraalCommandSdk(httpApp { WineRoutes.register(service) })
      for
        invalid <- sdk.call("wine.exec", Json.obj("exe" -> Json.fromString("cmd.exe"), "timeoutMs" -> Json.fromInt(0)))
        _ = assertEquals(invalid.hcursor.get[Int]("status"), Right(400))
        _ = assert(invalid.hcursor.downField("body").get[String]("message").getOrElse("").contains("timeoutMs"))
        forward <- service.call(WineCall("dotnet", Some("test.dll"), steps = Vector(WineStep(target = Some("unknown"))))).attempt
        _ = assert(forward.isLeft)
        duplicate <- service.call(WineCall("dotnet", Some("test.dll"), member = "Add", steps = Vector(WineStep()))).attempt
        _ = assert(duplicate.isLeft)
        absent <- sdk.call("wine.exec", Json.obj("exe" -> Json.fromString("cmd.exe")))
        _ = assertEquals(absent.hcursor.get[Int]("status"), Right(503))
        _ = assert(!Files.exists(p.resolve("prefix")))
      yield ()
    })
  }
  if sys.env.get("WINE_TEST").contains("1") then test("real one-shot EXEs/libraries, batch scope, cancellation and descendant isolation") {
    home.use { p =>
      val config = WineConfig.default.copy(home = p)
      val fixture = Path.of("wine/dist/fixture/Fixture.dll").toAbsolutePath.toString
      val managed = WineCall("dotnet", Some(fixture), typeName = Some("Fixture.Calculator"))
      def exec(mode: String, args: Vector[String] = Vector.empty) = WineExec(fixture, Vector(mode) ++ args, runtime = "dotnet")
      def ready(path: Path): IO[Unit] = IO.blocking(Files.exists(path)).flatMap(found => if found then IO.unit else IO.sleep(30.millis) *> ready(path))
      def unchanged(path: Path): IO[Unit] = for
        before <- IO.blocking(Files.size(path))
        _ <- IO.sleep(200.millis)
        after <- IO.blocking(Files.size(path))
        _ = assertEquals(after,before,"Windows descendants must be terminated")
      yield ()
      WineService.managed(config).use { service => for
        native <- service.call(WineCall("native", Some("kernel32.dll"), member = "lstrlenW", returnType = "i32",
          args = Vector(WineArgument("utf16", Json.fromString("hello 世界")))))
        _ = assertEquals(native.value, Json.fromInt(8))
        arguments = Vector("", "a b", "中文", "a\"b", "ends\\", "&literal", "$HOME", "%PATH%")
        echo <- service.execute(exec("args",arguments))
        _ = assertEquals(echo.exitCode,7)
        _ = assertEquals(echo.stdout.replace("\r", "").split("\n").toVector,
          arguments.map(s => java.util.Base64.getEncoder.encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        input = "stdin 中文\n"
        stdin <- service.execute(exec("stdin").copy(stdinBase64 = java.util.Base64.getEncoder.encodeToString(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        _ = assertEquals(stdin.stdout,input)
        variable <- service.execute(exec("env",Vector("TEST_VALUE")).copy(env = Map("TEST_VALUE" -> "$literal 中文")))
        _ = assertEquals(variable.stdout,"$literal 中文")
        noisy <- service.execute(exec("noisy"))
        _ = assertEquals(noisy.stdout.length,65536)
        _ = assert(noisy.stdoutTruncated)
        batch <- service.call(managed.copy(typeName = None, steps = Vector(
          WineStep(operation = "create", typeName = managed.typeName, args = Vector(integer(10)), saveAs = Some("object")),
          WineStep(target = Some("object"), member = "Add", args = Vector(integer(5))),
          WineStep(operation = "get", target = Some("object"), member = "Total"))))
        _ = assertEquals(batch.value,Json.fromInt(15))
        failed <- service.call(managed.copy(member = "Fail")).attempt
        _ = assert(failed.swap.toOption.exists(_.getMessage.contains("fixture failure")))
        hung <- service.call(managed.copy(member = "Hang", timeoutMs = 1000)).attempt.start
        sum <- service.call(managed.copy(member = "Sum", args = Vector(integer(20),integer(22))))
        _ = assertEquals(sum.value,Json.fromInt(42))
        timeout <- hung.joinWithNever
        _ = assert(timeout.swap.toOption.exists { case e: WineFailure => e.error.code == "wine_timeout"; case _ => false })
        exeTimeout <- service.execute(exec("hang").copy(timeoutMs = 1000)).attempt
        _ = assert(exeTimeout.swap.toOption.exists {
          case e: WineFailure => e.error.code == "wine_timeout" && e.error.stdout.contains("started")
          case _ => false
        })
        marker = p.resolve("cancelled-child")
        parent <- service.execute(exec("spawn",Vector(WineValidation.windows(marker.toString)))).start
        _ <- ready(marker).timeout(10.seconds)
        _ <- parent.cancel
        _ <- unchanged(marker)
        marker2 = p.resolve("exited-child")
        _ <- service.execute(exec("spawn-exit",Vector(WineValidation.windows(marker2.toString))))
        _ <- unchanged(marker2)
        com <- service.call(WineCall("com",classId = Some("Scripting.Dictionary"),steps = Vector(
          WineStep(member = "Add",args = Vector(WineArgument("string",Json.fromString("key")),integer(42))),
          WineStep(member = "Item",operation = "get",args = Vector(WineArgument("string",Json.fromString("key")))))))
        _ = assertEquals(com.value,Json.fromInt(42))
        framework <- service.call(managed.copy(path = Some(Path.of("wine/dist/fixture-framework/Fixture.exe").toAbsolutePath.toString),
          runtime = "framework", architecture = "x86", member = "Sum", args = Vector(integer(1),integer(2))))
        _ = assertEquals(framework.value,Json.fromInt(3))
        _ <- service.execute(WineExec("reg.exe",Vector("add","HKCU\\Software\\SproutFixture","/v","Persist","/d","kept","/f")))
        children <- IO.blocking(Using.resource(Files.list(p.resolve("calls")))(_.count()))
        _ = assertEquals(children,0L)
      yield () } *> WineService.managed(config).use { service => for
        persisted <- service.execute(WineExec("reg.exe",Vector("query","HKCU\\Software\\SproutFixture","/v","Persist")))
        _ = assert(persisted.stdout.contains("kept"))
      yield () }
    }
  }
