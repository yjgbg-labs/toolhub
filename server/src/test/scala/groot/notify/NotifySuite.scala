package groot.notify

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import framework.model.JsonModel
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import io.circe.Json
import io.circe.parser.parse
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.*
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class NotifySuite extends CatsEffectSuite:
  private val openCodeSessionId = "ses_target_01"
  private val agySessionId = "0508816e-1fae-4311-93cc-9f49f8eb4dde"

  private final case class CapturedHttp(
      method: String,
      path: String,
      headers: Map[String, String],
      bodyBytes: Array[Byte]
  ):
    def bodyUtf8: String = new String(bodyBytes, UTF_8)

  private def mockHttpServer(handler: HttpExchange => (Int, Array[Byte])): Resource[IO, (Int, ConcurrentLinkedQueue[CapturedHttp])] =
    Resource.make(IO.blocking {
      val captured = new ConcurrentLinkedQueue[CapturedHttp]()
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/", (exchange: HttpExchange) => {
        val body = exchange.getRequestBody.readAllBytes()
        val hdrs = exchange.getRequestHeaders.entrySet().asScala.map { e =>
          e.getKey.toLowerCase -> e.getValue.asScala.mkString(",")
        }.toMap
        captured.add(CapturedHttp(exchange.getRequestMethod, exchange.getRequestURI.getRawPath, hdrs, body))
        val (status, respBytes) = handler(exchange)
        exchange.sendResponseHeaders(status, if respBytes.isEmpty then -1L else respBytes.length.toLong)
        if respBytes.nonEmpty then
          val os = exchange.getResponseBody
          os.write(respBytes)
          os.close()
        else exchange.close()
      })
      server.start()
      (server, captured)
    }) { (server, _) => IO.blocking(server.stop(0)) }.map { (server, captured) =>
      (server.getAddress.getPort, captured)
    }

  /** Minimal Protobuf wire decoder for length-delimited string fields in tests. */
  private def decodeProtoStrings(bytes: Array[Byte]): Map[Int, String] =
    var idx = 0
    def readVarint(): Int =
      var result = 0
      var shift = 0
      var cont = true
      while cont do
        val b = bytes(idx) & 0xff
        idx += 1
        result |= (b & 0x7f) << shift
        if (b & 0x80) == 0 then cont = false
        else shift += 7
      result

    val fields = Map.newBuilder[Int, String]
    while idx < bytes.length do
      val tag = readVarint()
      val fieldNum = tag >>> 3
      val wireType = tag & 0x07
      assertEquals(wireType, 2, s"Expected length-delimited wireType=2, got $wireType")
      val len = readVarint()
      val str = new String(bytes, idx, len, UTF_8)
      idx += len
      fields += (fieldNum -> str)
    fields.result()

  test("PartialFunction deliverers match disjoint session patterns, compose with orElse, and reject unknown formats") {
    temporary.use { home =>
      val ocServer = mockHttpServer(_ => 200 -> """{"data":{}}""".getBytes(UTF_8))
      val agyServer = mockHttpServer(_ => 200 -> Array.emptyByteArray)
      (ocServer, agyServer).tupled.use { case ((ocPort, ocReqs), (agyPort, agyReqs)) =>
        val ocFile = home.resolve("opencode-service.json")
        val agyDir = home.resolve("agy-daemon")
        val cfg = NotifyConfig(
          openCodeServiceFile = ocFile.toString,
          antigravityDaemonDir = agyDir.toString
        )
        NotifyService.resource(IO.pure(cfg), isProcessAlive = _ == 100L).use { service =>
          for
            _ <- IO.blocking {
              Files.createDirectories(agyDir)
              Files.writeString(
                ocFile,
                s"""{"url":"http://127.0.0.1:$ocPort","password":"secret-pw","pid":100}""",
                UTF_8
              )
              Files.writeString(
                agyDir.resolve("ls_dead.json"),
                s"""{"httpPort":$ocPort,"csrfToken":"dead-token","pid":999}""",
                UTF_8
              )
              Files.writeString(
                agyDir.resolve("ls_alive.json"),
                s"""{"httpPort":$agyPort,"csrfToken":"agy-csrf-123","pid":100}""",
                UTF_8
              )
            }
            _ = assert(service.isSupportedSession(openCodeSessionId))
            _ = assert(service.isSupportedSession(agySessionId))
            _ = assert(!service.isSupportedSession("unknown_session"))

            unsupported <- service.send(NotifyRequest("unknown_session", "hi")).attempt
            _ = assertEquals(unsupported.swap.toOption.map(_.asInstanceOf[NotifyFailure].code), Some("invalid_notify"))

            // 1. OpenCode delivery: Basic auth, /api/session/:id/synthetic, 120-codepoint truncation preserving emoji
            boundary = "界" * 116 + "🧪abc"
            r1 <- service.send(NotifyRequest(openCodeSessionId, boundary))
            _ = assertEquals(r1.target, "opencode")
            r2 <- service.send(NotifyRequest(openCodeSessionId, boundary + "d"))
            _ = assertEquals(r2.target, "opencode")

            ocCaptured = ocReqs.asScala.toVector
            _ = assertEquals(ocCaptured.size, 2)
            expectedAuth = "Basic " + Base64.getEncoder.encodeToString("opencode:secret-pw".getBytes(UTF_8))
            _ = assertEquals(ocCaptured(0).method, "POST")
            _ = assertEquals(ocCaptured(0).path, s"/api/session/$openCodeSessionId/synthetic")
            _ = assertEquals(ocCaptured(0).headers.get("authorization"), Some(expectedAuth))
            json1 = parse(ocCaptured(0).bodyUtf8).toOption.get
            _ = assertEquals(json1.hcursor.get[String]("id"), Right(s"msg_notify_${r1.id.replace("-", "")}"))
            _ = assertEquals(json1.hcursor.get[String]("text"), Right(boundary))
            _ = assertEquals(json1.hcursor.get[String]("description"), Right(boundary))
            _ = assertEquals(json1.hcursor.get[String]("delivery"), Right("steer"))
            _ = assertEquals(json1.hcursor.get[Boolean]("resume"), Right(true))
            json2 = parse(ocCaptured(1).bodyUtf8).toOption.get
            _ = assertEquals(json2.hcursor.get[String]("description"), Right("界" * 116 + "🧪..."))

            // 2. Antigravity delivery: Connect-RPC application/proto + multi-byte varint payload + dead PID skipped
            longMessage = "通知正文🚀" * 40
            r3 <- service.send(NotifyRequest(agySessionId, longMessage))
            _ = assertEquals(r3.target, "antigravity")
            agyCaptured = agyReqs.asScala.toVector
            _ = assertEquals(agyCaptured.size, 1)
            _ = assertEquals(agyCaptured(0).method, "POST")
            _ = assertEquals(agyCaptured(0).path, "/exa.language_server_pb.LanguageServerService/SendAgentMessage")
            _ = assertEquals(agyCaptured(0).headers.get("content-type"), Some("application/proto"))
            _ = assertEquals(agyCaptured(0).headers.get("connect-protocol-version"), Some("1"))
            _ = assertEquals(agyCaptured(0).headers.get("x-codeium-csrf-token"), Some("agy-csrf-123"))
            decodedProto = decodeProtoStrings(agyCaptured(0).bodyBytes)
            _ = assertEquals(decodedProto, Map(1 -> longMessage, 2 -> agySessionId))
          yield ()
        }
      }
    }
  }

  test("OpenCode 409 duplicate ID is idempotent; dead PIDs report notify_offline; Antigravity fails over across multiple live daemons") {
    temporary.use { home =>
      val ocConflict = mockHttpServer(_ => 409 -> """{"error":"conflict"}""".getBytes(UTF_8))
      val agyFirst404 = mockHttpServer(_ => 404 -> """{"code":"not_found"}""".getBytes(UTF_8))
      val agySecond200 = mockHttpServer(_ => 200 -> Array.emptyByteArray)
      (ocConflict, agyFirst404, agySecond200).tupled.use { case ((ocPort, _), (agyPort1, reqs1), (agyPort2, reqs2)) =>
        val ocFile = home.resolve("service.json")
        val agyDir = home.resolve("daemon")
        val cfg = NotifyConfig(openCodeServiceFile = ocFile.toString, antigravityDaemonDir = agyDir.toString)
        NotifyService.resource(IO.pure(cfg), isProcessAlive = _ == 1L).use { service =>
          for
            // Missing files -> notify_offline
            offlineOc <- service.send(NotifyRequest(openCodeSessionId, "ping")).attempt
            _ = assertEquals(offlineOc.swap.toOption.map(_.asInstanceOf[NotifyFailure].code), Some("notify_offline"))
            offlineAgy <- service.send(NotifyRequest(agySessionId, "ping")).attempt
            _ = assertEquals(offlineAgy.swap.toOption.map(_.asInstanceOf[NotifyFailure].code), Some("notify_offline"))

            // Dead PID in OpenCode service.json -> notify_offline
            _ <- IO.blocking(Files.writeString(ocFile, s"""{"url":"http://127.0.0.1:$ocPort","pid":2}""", UTF_8))
            deadOc <- service.send(NotifyRequest(openCodeSessionId, "ping")).attempt
            _ = assertEquals(deadOc.swap.toOption.map(_.asInstanceOf[NotifyFailure].code), Some("notify_offline"))

            // Alive PID + 409 Conflict -> idempotent success
            _ <- IO.blocking(Files.writeString(ocFile, s"""{"url":"http://127.0.0.1:$ocPort","pid":1}""", UTF_8))
            okOc <- service.send(NotifyRequest(openCodeSessionId, "ping"))
            _ = assertEquals(okOc.target, "opencode")

            // Antigravity multi-daemon failover: newer daemon returns 404, older live daemon succeeds with 200
            _ <- IO.blocking {
              Files.createDirectories(agyDir)
              val older = agyDir.resolve("ls_older.json")
              val newer = agyDir.resolve("ls_newer.json")
              Files.writeString(older, s"""{"httpPort":$agyPort2,"csrfToken":"tok2","pid":1}""", UTF_8)
              Files.setLastModifiedTime(older, FileTime.fromMillis(1000L))
              Files.writeString(newer, s"""{"httpPort":$agyPort1,"csrfToken":"tok1","pid":1}""", UTF_8)
              Files.setLastModifiedTime(newer, FileTime.fromMillis(2000L))
            }
            okAgy <- service.send(NotifyRequest(agySessionId, "hello failover"))
            _ = assertEquals(okAgy.target, "antigravity")
            _ = assertEquals(reqs1.size(), 1)
            _ = assertEquals(reqs2.size(), 1)
          yield ()
        }
      }
    }
  }

  test("shutdown rejects sends with notify_closed; NotifyRoutes maps status codes and exposes no notify.subscribe") {
    temporary.use { home =>
      for
        delivered <- Queue.unbounded[IO, Notification]
        customDeliverer: NotifyDeliverer = {
          case n if n.targetSessionId == "ses_missing" =>
            IO.raiseError(NotifyFailure("notify_not_found", "missing"))
          case n if n.targetSessionId == "ses_offline" =>
            IO.raiseError(NotifyFailure("notify_offline", "offline"))
          case n if n.targetSessionId == "ses_bad_gw" =>
            IO.raiseError(NotifyFailure("delivery_error", "upstream 500"))
          case n if NotifyDeliverers.openCodeSessionPattern.matches(n.targetSessionId) =>
            delivered.offer(n).as("opencode")
          case n if NotifyDeliverers.antigravitySessionPattern.matches(n.targetSessionId) =>
            delivered.offer(n).as("antigravity")
        }
        allocated <- NotifyService.withDeliverer(customDeliverer).allocated
        (service, closeService) = allocated
        _ <- (for
          commands <- Deferred[IO, GraalCommandSdk]
          _ <- GraalManager.resource(home, commands = commands.get).use { graal =>
            NotifyTriggerManager.resource(service, graal, home = home, startLoop = false).use { triggers =>
              val app = httpApp { NotifyRoutes.register(service, Some(triggers)) }
              val sdk = GraalCommandSdk(app)
              assertEquals(sdk.commands.toSet, Set("notify.when", "notify.at", "notify.list", "notify.cancel"))
              assert(!sdk.commands.contains("notify.send"))
              assertEquals(app.endpoints.find(_.operationId == "notify.subscribe"), None)
              assertEquals(app.endpoints.find(_.operationId == "notify.send").get.exposedOn, Set(cli))
              assertEquals(app.endpoints.find(_.operationId == "notify.when").get.exposedOn, Set(cli, codemode, graaljs))
              assertEquals(app.endpoints.find(_.operationId == "notify.at").get.exposedOn, Set(cli, codemode, graaljs))

              def postSend(req: NotifyRequest) =
                app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/notify/send"))
                  .withEntity(JsonModel[NotifyRequest].encoder(req)))

              for
                _ <- commands.complete(sdk)
                ok <- postSend(NotifyRequest(agySessionId, "hello agy"))
                _ = assertEquals(ok.status, Status.Ok)
                got <- delivered.take.timeout(3.seconds)
                _ = assertEquals(got.message, "hello agy")

                notFound <- postSend(NotifyRequest("ses_missing", "x"))
                _ = assertEquals(notFound.status, Status.NotFound)

                offline <- postSend(NotifyRequest("ses_offline", "x"))
                _ = assertEquals(offline.status, Status.Conflict)

                badGw <- postSend(NotifyRequest("ses_bad_gw", "x"))
                _ = assertEquals(badGw.status, Status.BadGateway)

                _ <- List(
                  NotifyRequest(openCodeSessionId, " "),
                  NotifyRequest(openCodeSessionId, ""),
                  NotifyRequest("bad session!", "x"),
                  NotifyRequest("unsupported_prefix_1", "x")
                ).traverse_(bad => postSend(bad).map(reply => assertEquals(reply.status, Status.BadRequest)))

                large <- postSend(NotifyRequest(openCodeSessionId, "界" * 22000))
                _ = assertEquals(large.status, Status.PayloadTooLarge)

                _ <- closeService
                closedResp <- postSend(NotifyRequest(openCodeSessionId, "after close"))
                _ = assertEquals(closedResp.status, Status.ServiceUnavailable)
              yield ()
            }
          }
        yield ()).guarantee(closeService)
      yield ()
    }
  }

  test("notify.when and notify.at deliver directly through PartialFunction deliverers for both OpenCode and Antigravity") {
    temporary.use { home =>
      for
        events <- Queue.unbounded[IO, (String, Notification)]
        deliverer: NotifyDeliverer = NotifyDeliverers.combine(Seq(
          { case n if NotifyDeliverers.openCodeSessionPattern.matches(n.targetSessionId) =>
            events.offer("opencode" -> n).as("opencode")
          },
          { case n if NotifyDeliverers.antigravitySessionPattern.matches(n.targetSessionId) =>
            events.offer("antigravity" -> n).as("antigravity")
          }
        ))
        _ <- (for
          commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
          service <- NotifyService.withDeliverer(deliverer)
          graal <- GraalManager.resource(home, commands = commands.get)
          triggers <- NotifyTriggerManager.resource(service, graal, home = home, startLoop = true)
        yield (service, triggers, commands, graal)).use { (service, triggers, commands, graal) =>
          val sdk = GraalCommandSdk(httpApp { NotifyRoutes.register(service, Some(triggers)) })
          for
            _ <- commands.complete(sdk)
            setupTask <- graal.submit(
              JavaScript.source(
                """await tools.notify.when({
                  |  every: "50ms",
                  |  once: true,
                  |  code: 'await tools.notify.at({ at: "+50ms", message: "二阶继承提醒" }); return "一阶条件成立";'
                  |});""".stripMargin
              ),
              cpu = "2s",
              timeout = "10s",
              context = Map("sessionId" -> agySessionId, "cwd" -> "/tmp")
            )
            finishedSetup <- graal.await(setupTask.id)
            _ = assertEquals(finishedSetup.state, "succeeded", finishedSetup.error.toString)

            ocTrigger <- triggers.when(
              sessionId = openCodeSessionId,
              cwd = None,
              request = NotifyWhenRequest(
                code = """return "OpenCode 持续提醒";""",
                every = Some("80ms"),
                once = Some(false)
              )
            )

            collected <- List.fill(4)(()).traverse(_ => events.take.timeout(5.seconds))
            _ <- triggers.cancel(ocTrigger.id)

            agyMsgs = collected.collect { case ("antigravity", n) => n.targetSessionId -> n.message }.toSet
            _ = assert(agyMsgs.contains(agySessionId -> "一阶条件成立"), s"got $collected")
            _ = assert(agyMsgs.contains(agySessionId -> "二阶继承提醒"), s"got $collected")
            ocMsgs = collected.collect { case ("opencode", n) => n.targetSessionId -> n.message }
            _ = assert(ocMsgs.nonEmpty && ocMsgs.forall(_ == (openCodeSessionId -> "OpenCode 持续提醒")), s"got $collected")
          yield ()
        }
      yield ()
    }
  }
