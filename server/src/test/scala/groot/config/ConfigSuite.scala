package groot.config

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.model.JsonModel
import framework.rpc.PlainText
import framework.rpcDsl.*
import framework.runtime.DynamicServer
import framework.validation.*
import groot.fs.{FsLimit, FsService, WriteResult}
import java.net.{InetAddress, ServerSocket}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import munit.CatsEffectSuite
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class ConfigSuite extends CatsEffectSuite:

  final class evenInt extends contract[Int](_ % 2 == 0, "must be even")

  @contract[SampleQuota](q => q.minItems <= q.maxItems, "minItems must not exceed maxItems")
  final case class SampleQuota(
      @min(0) minItems: Int = 0,
      @min(1) @max(100) @evenInt maxItems: Int = 10,
      @nonBlank tags: Vector[String] = Vector.empty
  ) derives JsonModel, Validator

  private val tempDir: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory("groot-config-test-"))
  )(dir =>
    IO.blocking {
      if Files.exists(dir) then
        Using.resource(Files.walk(dir))(_.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists))
    }
  )

  private def freePort(): Int =
    Using.resource(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")))(_.getLocalPort)

  private def waitUntil(check: IO[Boolean], timeout: FiniteDuration = 5.seconds): IO[Unit] =
    def loop: IO[Unit] = check.flatMap(ok => if ok then IO.unit else IO.sleep(40.millis) *> loop)
    loop.timeout(timeout)

  test("@contract and subclass annotations (@min, @max, custom extends contract[Int]) validate fields and class invariants") {
    assertEquals(Validator[SampleQuota].validate(SampleQuota(2, 10, Vector("a"))), Right(SampleQuota(2, 10, Vector("a"))))

    val fieldErrors = Validator[SampleQuota].validateIssues(
      SampleQuota(-1, 3, Vector("ok", "  "))
    )
    assertEquals(fieldErrors.map(_.path), Vector("minItems", "maxItems", "tags[1]"))

    val crossField = Validator[SampleQuota].validateIssues(SampleQuota(minItems = 20, maxItems = 10))
    assertEquals(crossField.map(_.path), Vector(""))
    assert(crossField.head.message.contains("minItems must not exceed maxItems"))
  }

  test("parseHocon merges partial HOCON overrides, byte units, and comments") {
    val hocon =
      """# Groot HOCON config
        |server.host = "127.0.0.1"
        |server.port = 19090
        |server.maxBodyBytes = 4MiB
        |
        |// Nested block + byte units
        |graaljs {
        |  engineBytes = 128MiB
        |  taskBytes = 8MiB
        |}
        |
        |http {
        |  timeout = 45s
        |  maxBytes = 2MiB
        |}
        |
        |notifications {
        |  deliveryTimeout = 10s
        |  maxActiveTriggers = 128
        |}
        |""".stripMargin

    val parsed = ConfigLoader.parseHocon[ApplicationConfig](hocon, ApplicationConfig())
    assert(parsed.isRight, s"Expected Right, got $parsed")
    val cfg = parsed.toOption.get
    assertEquals(cfg.server.host, "127.0.0.1")
    assertEquals(cfg.server.port, 19090)
    assertEquals(cfg.server.maxBodyBytes, 4L * 1024 * 1024)
    assertEquals(cfg.graaljs.engineBytes, 128L * 1024 * 1024)
    assertEquals(cfg.graaljs.taskBytes, 8L * 1024 * 1024)
    assertEquals(cfg.http.timeout, "45s")
    assertEquals(cfg.http.maxBytes, 2 * 1024 * 1024)
    assertEquals(cfg.notifications.deliveryTimeout, "10s")
    assertEquals(cfg.notifications.maxActiveTriggers, 128)
    assertEquals(cfg.sqlite.maxRows, 10000)
    assertEquals(cfg.fxTwitter.baseUrl, "https://api.fxtwitter.com")
    assertEquals(cfg.telegram.apiBase, "https://api.telegram.org")
  }

  test("parseHocon rejects unknown fields and annotation contract violations with full paths") {
    val unknown = ConfigLoader.parseHocon[ApplicationConfig]("http.noSuchOption = 42", ApplicationConfig())
    assert(unknown.isLeft)
    assert(unknown.swap.toOption.get.contains("Unknown config field 'http.noSuchOption'"))

    val badMulti = ConfigLoader.parseHocon[ApplicationConfig](
      """server.port = 70000
        |notifications.deliveryRetry.backoff = 0.5
        |""".stripMargin,
      ApplicationConfig()
    )
    assert(badMulti.isLeft)
    val msg = badMulti.swap.toOption.get
    assert(msg.contains("server.port"), msg)
    assert(msg.contains("notifications.deliveryRetry.backoff"), msg)

    val badBudget = ConfigLoader.parseHocon[ApplicationConfig](
      "graaljs { engineBytes = 4MiB, taskBytes = 8MiB }",
      ApplicationConfig()
    )
    assert(badBudget.isLeft)
    assert(badBudget.swap.toOption.get.contains("taskBytes must not exceed engineBytes"))
  }

  test("500ms metadata polling hot-reloads via IO[FsConfig] and keeps Last-Known-Good on invalid edits") {
    tempDir.use { dir =>
      val confPath = dir.resolve("application.conf")
      val extraPath = dir.resolve("extra.conf")
      IO.blocking(Files.writeString(extraPath, "fs.maxEntries = 42\n", UTF_8)) *>
        ConfigLoader.resource[ApplicationConfig](
          resolvePath = IO.pure(confPath),
          default = ApplicationConfig(),
          pollInterval = 100.millis
        ).use { sig =>
          val fsSvc = new FsService(sig.get.map(_.fs))
          val sampleFile = dir.resolve("payload.txt")
          for
            init <- sig.get
            _ = assertEquals(init, ApplicationConfig())

            _ <- IO.blocking(
              Files.writeString(
                confPath,
                """include "extra.conf"
                  |fs.maxBytes = 16
                  |""".stripMargin,
                UTF_8
              )
            )
            _ <- waitUntil(sig.get.map(c => c.fs.maxBytes == 16 && c.fs.maxEntries == 42))
            tooBig <- fsSvc.write(sampleFile.toString, "0123456789abcdef_overflow").attempt
            _ = assert(tooBig.left.exists(_.isInstanceOf[FsLimit]))

            _ <- IO.blocking(Files.writeString(confPath, "fs { maxBytes = -5 }", UTF_8))
            _ <- IO.sleep(250.millis)
            afterInvalid <- sig.get
            _ = assertEquals(afterInvalid.fs.maxBytes, 16)

            tmp = dir.resolve("application.conf.tmp")
            _ <- IO.blocking {
              Files.writeString(tmp, "fs.maxBytes = 1KiB\n", UTF_8)
              Files.move(tmp, confPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            _ <- waitUntil(sig.get.map(_.fs.maxBytes == 1024))
            okWrite <- fsSvc.write(sampleFile.toString, "0123456789abcdef_overflow")
            _ = assertEquals(okWrite, WriteResult(25))

            _ <- IO.blocking(Files.deleteIfExists(confPath))
            _ <- waitUntil(sig.get.map(_ == ApplicationConfig()))
          yield ()
        }
    }
  }

  test("server.host, server.port, server.cors and server.maxBodyBytes hot-rebind live without restart") {
    tempDir.use { dir =>
      val confPath = dir.resolve("application.conf")
      val portA = freePort()
      val portB = freePort()
      IO.blocking(
        Files.writeString(
          confPath,
          s"""server {
             |  host = "127.0.0.1"
             |  port = $portA
             |  maxBodyBytes = 32
             |  cors.allowOrigins = "https://alpha.example"
             |}
             |""".stripMargin,
          UTF_8
        )
      ) *>
        (for
          sig <- ConfigLoader.resource[ApplicationConfig](
            resolvePath = IO.pure(confPath),
            default = ApplicationConfig(),
            pollInterval = 100.millis
          )
          initCfg <- Resource.eval(sig.get)
          app = httpApp {
            server {
              host(initCfg.server.host)
              port(initCfg.server.port)
              maxBodyBytes(initCfg.server.maxBodyBytes)
            }
            command[PlainText]("health") {
              handle { IO.pure(PlainText("ok")) }
            }
            command[PlainText]("echo") {
              val text = body.text("payload")
              handle { IO.pure(PlainText(text.value)) }
            }
          }
          addrSig <- DynamicServer.resource(app, sig.map(_.server.toSettings))
          client <- EmberClientBuilder.default[IO].withTimeout(3.seconds).build
        yield (addrSig, client)).use { (addrSig, client) =>
          def getHealth(port: Int, origin: String): IO[(Int, Option[String], String)] =
            val req = Request[IO](
              method = Method.GET,
              uri = Uri.unsafeFromString(s"http://127.0.0.1:$port/health"),
              headers = Headers(Header.Raw(CIString("Origin"), origin))
            )
            client.run(req).use { resp =>
              resp.bodyText.compile.string.map { text =>
                val allowOrigin = resp.headers.get(CIString("Access-Control-Allow-Origin")).map(_.head.value)
                (resp.status.code, allowOrigin, text)
              }
            }

          def postEcho(port: Int, payload: String): IO[Int] =
            val req = Request[IO](
              method = Method.POST,
              uri = Uri.unsafeFromString(s"http://127.0.0.1:$port/echo")
            ).withEntity(payload).putHeaders(Header.Raw(CIString("Content-Type"), "text/plain"))
            client.run(req).use(resp => IO.pure(resp.status.code))

          for
            initAddr <- addrSig.get
            _ = assertEquals(initAddr.getPort, portA)
            resA <- getHealth(portA, "https://alpha.example")
            _ = assertEquals(resA, (200, Some("https://alpha.example"), "ok"))
            statusTooLarge <- postEcho(portA, "x" * 64)
            _ = assertEquals(statusTooLarge, 413)

            _ <- IO.blocking(
              Files.writeString(
                confPath,
                s"""server {
                   |  host = "127.0.0.1"
                   |  port = $portB
                   |  maxBodyBytes = 1KiB
                   |  cors.allowOrigins = "https://beta.example"
                   |}
                   |""".stripMargin,
                UTF_8
              )
            )
            _ <- waitUntil(addrSig.get.map(_.getPort == portB))
            resB <- getHealth(portB, "https://beta.example")
            _ = assertEquals(resB, (200, Some("https://beta.example"), "ok"))
            statusOk <- postEcho(portB, "x" * 64)
            _ = assertEquals(statusOk, 200)

            oldPortAttempt <- getHealth(portA, "https://alpha.example").attempt
            _ = assert(oldPortAttempt.isLeft, "Expected old port listener to be closed after rebind")
          yield ()
        }
    }
  }
