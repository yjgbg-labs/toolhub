package groot.config

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpc.PlainText
import framework.rpcDsl.*
import groot.fs.{FsLimit, FsService}
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

  test("parseHocon merges partial HOCON overrides, byte units, comments and notify alias") {
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
        |notify {
        |  heartbeat = 10s
        |  subscriberCapacity = 128
        |}
        |""".stripMargin

    val parsed = ConfigLoader.parseHocon[GrootConfig](hocon, GrootConfig(), GrootConfig.validate)
    assert(parsed.isRight, s"Expected Right, got $parsed")
    val cfg = parsed.toOption.get
    assertEquals(cfg.server.host, "127.0.0.1")
    assertEquals(cfg.server.port, 19090)
    assertEquals(cfg.server.maxBodyBytes, 4L * 1024 * 1024)
    assertEquals(cfg.graaljs.engineBytes, 128L * 1024 * 1024)
    assertEquals(cfg.graaljs.taskBytes, 8L * 1024 * 1024)
    assertEquals(cfg.http.timeout, "45s")
    assertEquals(cfg.http.maxBytes, 2 * 1024 * 1024)
    assertEquals(cfg.notifications.heartbeat, "10s")
    assertEquals(cfg.notifications.subscriberCapacity, 128)
    // Unspecified sections retain defaults
    assertEquals(cfg.sqlite.maxRows, 10000)
    assertEquals(cfg.fxTwitter.baseUrl, "https://api.fxtwitter.com")
  }

  test("parseHocon rejects unknown fields and invalid semantic constraints") {
    val unknown = ConfigLoader.parseHocon[GrootConfig]("http.noSuchOption = 42", GrootConfig(), GrootConfig.validate)
    assert(unknown.isLeft)
    assert(unknown.swap.toOption.get.contains("Unknown config field 'http.noSuchOption'"))

    val badPort = ConfigLoader.parseHocon[GrootConfig]("server.port = 70000", GrootConfig(), GrootConfig.validate)
    assert(badPort.isLeft)
    assert(badPort.swap.toOption.get.contains("server.port"))

    val badBudget = ConfigLoader.parseHocon[GrootConfig](
      "graaljs { engineBytes = 4MiB, taskBytes = 8MiB }",
      GrootConfig(),
      GrootConfig.validate
    )
    assert(badBudget.isLeft)
    assert(badBudget.swap.toOption.get.contains("taskBytes"))
  }

  test("500ms metadata polling hot-reloads file creation, atomic rename, include, invalid edits and deletion") {
    tempDir.use { dir =>
      val confPath = dir.resolve("application.conf")
      val extraPath = dir.resolve("extra.conf")
      IO.blocking(Files.writeString(extraPath, "fs.maxEntries = 42\n", UTF_8)) *>
        ConfigLoader.resource[GrootConfig](
          resolvePath = IO.pure(confPath),
          default = GrootConfig(),
          validate = GrootConfig.validate,
          pollInterval = 100.millis
        ).use { sig =>
          val fsSvc = new FsService(sig.map(_.fs))
          val sampleFile = dir.resolve("payload.txt")
          for
            // 1. Missing application.conf -> defaults
            init <- sig.get
            _ = assertEquals(init, GrootConfig())

            // 2. Create application.conf with include + tight fs.maxBytes
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

            // 3. Corrupt HOCON -> keeps Last-Known-Good (fs.maxBytes == 16)
            _ <- IO.blocking(Files.writeString(confPath, "fs { maxBytes = [unclosed", UTF_8))
            _ <- IO.sleep(250.millis)
            afterCorrupt <- sig.get
            _ = assertEquals(afterCorrupt.fs.maxBytes, 16)

            // 4. Atomic rename replacement -> raises fs.maxBytes to 1KiB
            tmp = dir.resolve("application.conf.tmp")
            _ <- IO.blocking {
              Files.writeString(tmp, "fs.maxBytes = 1KiB\n", UTF_8)
              Files.move(tmp, confPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            _ <- waitUntil(sig.get.map(_.fs.maxBytes == 1024))
            okWrite <- fsSvc.write(sampleFile.toString, "0123456789abcdef_overflow")
            _ = assertEquals(okWrite, groot.fs.WriteResult(25))

            // 5. Delete application.conf -> reverts to GrootConfig() defaults
            _ <- IO.blocking(Files.deleteIfExists(confPath))
            _ <- waitUntil(sig.get.map(_ == GrootConfig()))
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
          sig <- ConfigLoader.resource[GrootConfig](
            resolvePath = IO.pure(confPath),
            default = GrootConfig(),
            validate = GrootConfig.validate,
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
          addrSig <- app.resourceDynamic(sig.map(c => GrootConfig.toServerSettings(c.server)))
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
            // maxBodyBytes = 32 rejects 64-byte body
            statusTooLarge <- postEcho(portA, "x" * 64)
            _ = assertEquals(statusTooLarge, 413)

            // Rebind to portB, new CORS origin, and larger maxBodyBytes in application.conf
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

            // Old portA listener must be closed
            oldPortAttempt <- getHealth(portA, "https://alpha.example").attempt
            _ = assert(oldPortAttempt.isLeft, "Expected old port listener to be closed after rebind")

            // Attempting to rebind onto an already-occupied port keeps portB alive
            _ <- Resource.fromAutoCloseable(IO.blocking(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")))).use { occupied =>
              val busyPort = occupied.getLocalPort
              for
                _ <- IO.blocking(
                  Files.writeString(
                    confPath,
                    s"""server {
                       |  host = "127.0.0.1"
                       |  port = $busyPort
                       |}
                       |""".stripMargin,
                    UTF_8
                  )
                )
                _ <- IO.sleep(300.millis)
                stillPortB <- addrSig.get
                _ = assertEquals(stillPortB.getPort, portB)
                resStillAlive <- getHealth(portB, "https://beta.example")
                _ = assertEquals(resStillAlive._1, 200)
              yield ()
            }
          yield ()
        }
    }
  }
