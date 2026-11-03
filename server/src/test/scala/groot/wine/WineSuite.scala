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
  if sys.env.get("WINE_TEST").contains("1") then test("real native/COM calls and persistent prefix") {
    home.use { p =>
      val config = WineConfig.default.copy(home = p)
      WineService.managed(config).use { service => for
        native <- service.call(WineCall("native", Some("kernel32.dll"), member = "lstrlenW", returnType = "i32",
          args = Vector(WineArgument("utf16", Json.fromString("hello 世界")))))
        _ = assertEquals(native.value, Json.fromInt(8))
        com <- service.call(WineCall("com",classId = Some("Scripting.Dictionary"),steps = Vector(
          WineStep(member = "Add",args = Vector(WineArgument("string",Json.fromString("key")),integer(42))),
          WineStep(member = "Item",operation = "get",args = Vector(WineArgument("string",Json.fromString("key")))))))
        _ = assertEquals(com.value,Json.fromInt(42))
        _ <- service.execute(WineExec("reg.exe",Vector("add","HKCU\\Software\\SproutFixture","/v","Persist","/d","kept","/f")))
        children <- IO.blocking(Using.resource(Files.list(p.resolve("calls")))(_.count()))
        _ = assertEquals(children,0L)
      yield () } *> WineService.managed(config).use { service => for
        persisted <- service.execute(WineExec("reg.exe",Vector("query","HKCU\\Software\\SproutFixture","/v","Persist")))
        _ = assert(persisted.stdout.contains("kept"))
      yield () }
    }
  }
