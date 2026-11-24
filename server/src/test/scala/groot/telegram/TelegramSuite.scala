package groot.telegram

import cats.effect.{Deferred, IO, Ref, Resource}
import com.comcast.ip4s.*
import framework.rpcDsl.*
import framework.validation.Validator
import groot.graaljs.{GraalCommandSdk, GraalJsRoutes, GraalManager, JavaScript}
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Base64
import munit.CatsEffectSuite
import org.http4s.{Header, Headers, HttpApp, Method, Response, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class TelegramSuite extends CatsEffectSuite:
  private val syntheticToken = "100001:TEST_SYNTHETIC_BOT_TOKEN_FOR_SUITE_00001"

  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking {
      Files.createDirectories(Path.of("/tmp/opencode"))
      Files.createTempDirectory(Path.of("/tmp/opencode"), "telegram-test-")
    }
  )(p =>
    IO.blocking {
      val fs = Files.walk(p)
      try fs.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
      finally fs.close()
    }
  )

  private def serve(app: HttpApp[IO]): Resource[IO, Uri] =
    EmberServerBuilder.default[IO]
      .withHost(ipv4"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(app)
      .build
      .map(s => Uri.unsafeFromString(s"http://127.0.0.1:${s.address.getPort}"))

  private def j(raw: String): Json = parse(raw).fold(throw _, identity)

  private def jsonResp(status: Status, body: String): Response[IO] =
    Response[IO](
      status = status,
      headers = Headers(Header.Raw(CIString("Content-Type"), "application/json"))
    ).withEntity(j(body).noSpaces)

  private def awaitUntil[A](action: IO[A])(check: A => Boolean): IO[A] =
    def loop: IO[A] = action.flatMap(v => if check(v) then IO.pure(v) else IO.sleep(15.millis) *> IO.defer(loop))
    loop.timeout(20.seconds)

  test("TelegramConfig Validator enforces URL, duration, proxyUrl and byte bounds without Option fields") {
    val valid = TelegramConfig(
      apiBase = "https://groot-tg.example.com",
      proxyUrl = "http://127.0.0.1:7890",
      timeout = "15s"
    )
    assert(Validator[TelegramConfig].validate(valid).isRight)
    assert(Validator[TelegramConfig].validate(TelegramConfig(proxyUrl = "")).isRight)
    assert(Validator[TelegramConfig].validate(TelegramConfig(apiBase = "ftp://bad")).isLeft)
    assert(Validator[TelegramConfig].validate(TelegramConfig(proxyUrl = "not-a-url")).isLeft)
    assert(Validator[TelegramConfig].validate(TelegramConfig(timeout = "0s")).isLeft)
    assert(Validator[TelegramConfig].validate(TelegramConfig(maxRateLimitWaitMs = -1)).isLeft)
  }

  test("TelegramService login/logout persists 0600 state.json and redacts raw token on upstream errors") {
    val mock = HttpApp[IO] { req =>
      (req.method, req.uri.path.renderString) match
        case (Method.POST, p) if p == s"/bot$syntheticToken/getMe" =>
          IO.pure(
            jsonResp(
              Status.Ok,
              """{"ok":true,"result":{"id":100001,"is_bot":true,"first_name":"TestBot","username":"test_suite_bot","can_join_groups":true}}"""
            )
          )
        case (Method.POST, p) if p == s"/bot$syntheticToken/badMethod" =>
          IO.pure(
            jsonResp(
              Status.Unauthorized,
              s"""{"ok":false,"error_code":401,"description":"Invalid token $syntheticToken rejected"}"""
            )
          )
        case _ =>
          IO.pure(jsonResp(Status.NotFound, """{"ok":false,"error_code":404,"description":"Not found"}"""))
    }

    temporary.use { root =>
      serve(mock).use { uri =>
        val cfg = TelegramConfig(apiBase = uri.renderString, botToken = "")
        TelegramService.resource(root, IO.pure(cfg)).use { svc =>
          for
            me <- svc.login(syntheticToken, Some("777001"))
            _ = assertEquals(me.id, 100001L)
            _ = assertEquals(me.username, "test_suite_bot")
            _ = assertEquals(me.defaultChatId, Some("777001"))
            statePath = root.resolve("telegram").resolve("state.json")
            _ = assert(Files.isRegularFile(statePath))
            err <- svc.api(RawApiRequest("badMethod")).attempt
            _ = err match
              case Left(f: TelegramFailure) =>
                assertEquals(f.code, "telegram_unauthenticated")
                assert(!f.getMessage.contains(syntheticToken), s"Token leaked in: ${f.getMessage}")
                assert(f.getMessage.contains("<redacted>"))
              case other => fail(s"Expected TelegramFailure, got $other")
            out <- svc.logout
            _ = assertEquals(out.loggedOut, true)
            _ = assertEquals(out.username, Some("test_suite_bot"))
            afterLogout <- svc.me.attempt
            _ = assert(afterLogout.left.exists(_.isInstanceOf[TelegramFailure]))
          yield ()
        }
      }
    }
  }

  test("GraalJS tools.telegram.* end-to-end: auto-discovers chatId, tracks update offsets, retries 429, uploads multipart and downloads files") {
    Ref.of[IO, Int](0).flatMap { rateLimitHits =>
      Ref.of[IO, Vector[Long]](Vector.empty).flatMap { seenOffsets =>
        Ref.of[IO, String]("").flatMap { uploadedBody =>
          val mock = HttpApp[IO] { req =>
            req.body.compile.to(Array).flatMap { rawBytes =>
              val bodyStr = new String(rawBytes, UTF_8)
              (req.method, req.uri.path.renderString) match
                case (Method.POST, p) if p == s"/bot$syntheticToken/getMe" =>
                  IO.pure(
                    jsonResp(
                      Status.Ok,
                      """{"ok":true,"result":{"id":100001,"is_bot":true,"first_name":"GrootBot","username":"groot_suite_bot","can_join_groups":true}}"""
                    )
                  )

                case (Method.POST, p) if p == s"/bot$syntheticToken/getUpdates" =>
                  val off = parse(bodyStr).toOption.flatMap(_.hcursor.get[Long]("offset").toOption)
                  seenOffsets.update(_ :+ off.getOrElse(-1L)).as {
                    if off.exists(_ > 501L) then jsonResp(Status.Ok, """{"ok":true,"result":[]}""")
                    else
                      jsonResp(
                        Status.Ok,
                        """{
                          "ok": true,
                          "result": [
                            {
                              "update_id": 500,
                              "message": {
                                "message_id": 11,
                                "date": 1700000000,
                                "chat": {"id": 998877, "type": "private", "username": "alice", "first_name": "Alice"},
                                "from": {"id": 998877, "is_bot": false, "username": "alice", "first_name": "Alice"},
                                "text": "/start"
                              }
                            },
                            {
                              "update_id": 501,
                              "callback_query": {
                                "id": "cb_1",
                                "from": {"id": 998877, "is_bot": false, "username": "alice", "first_name": "Alice"},
                                "message": {
                                  "message_id": 12,
                                  "date": 1700000005,
                                  "chat": {"id": 998877, "type": "private", "username": "alice", "first_name": "Alice"},
                                  "text": "Prompt"
                                },
                                "data": "approve:42"
                              }
                            }
                          ]
                        }"""
                      )
                  }

                case (Method.POST, p) if p == s"/bot$syntheticToken/sendMessage" =>
                  rateLimitHits.getAndUpdate(_ + 1).map { hit =>
                    if hit == 0 then
                      jsonResp(
                        Status.TooManyRequests,
                        """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 1","parameters":{"retry_after":1}}"""
                      )
                    else
                      val c = j(bodyStr).hcursor
                      val cid = c.get[String]("chat_id").getOrElse("0")
                      val txt = c.get[String]("text").getOrElse("")
                      jsonResp(
                        Status.Ok,
                        s"""{
                          "ok": true,
                          "result": {
                            "message_id": 88,
                            "date": 1700000010,
                            "chat": {"id": ${cid.toLongOption.getOrElse(0L)}, "type": "private", "username": "alice", "first_name": "Alice"},
                            "text": ${Json.fromString(txt).noSpaces}
                          }
                        }"""
                      )
                  }

                case (Method.POST, p) if p == s"/bot$syntheticToken/editMessageText" =>
                  val c = j(bodyStr).hcursor
                  val mid = c.get[Long]("message_id").getOrElse(0L)
                  val txt = c.get[String]("text").getOrElse("")
                  IO.pure(
                    jsonResp(
                      Status.Ok,
                      s"""{
                        "ok": true,
                        "result": {
                          "message_id": $mid,
                          "date": 1700000010,
                          "edit_date": 1700000020,
                          "chat": {"id": 998877, "type": "private", "username": "alice"},
                          "text": ${Json.fromString(txt).noSpaces}
                        }
                      }"""
                    )
                  )

                case (Method.POST, p) if p == s"/bot$syntheticToken/pinChatMessage" || p == s"/bot$syntheticToken/unpinChatMessage" || p == s"/bot$syntheticToken/deleteMessage" =>
                  IO.pure(jsonResp(Status.Ok, """{"ok":true,"result":true}"""))

                case (Method.POST, p) if p == s"/bot$syntheticToken/sendDocument" =>
                  uploadedBody.set(bodyStr).as(
                    jsonResp(
                      Status.Ok,
                      """{
                        "ok": true,
                        "result": {
                          "message_id": 89,
                          "date": 1700000030,
                          "chat": {"id": 998877, "type": "private"},
                          "caption": "Report file",
                          "document": {"file_id": "doc_fid_1", "file_name": "report.txt", "file_size": 12}
                        }
                      }"""
                    )
                  )

                case (Method.POST, p) if p == s"/bot$syntheticToken/getFile" =>
                  IO.pure(jsonResp(Status.Ok, """{"ok":true,"result":{"file_id":"doc_fid_1","file_path":"documents/report.txt"}}"""))

                case (Method.GET, p) if p == s"/file/bot$syntheticToken/documents/report.txt" =>
                  IO.pure(Response[IO](status = Status.Ok).withEntity("hello-tg-doc"))

                case (Method.POST, p) if p == s"/bot$syntheticToken/getWebhookInfo" =>
                  IO.pure(jsonResp(Status.Ok, """{"ok":true,"result":{"url":"","has_custom_certificate":false,"pending_update_count":0}}"""))

                case (Method.POST, p) if p == s"/bot$syntheticToken/sendChatAction" =>
                  IO.pure(jsonResp(Status.Ok, """{"ok":true,"result":true}"""))

                case _ =>
                  IO.pure(jsonResp(Status.NotFound, """{"ok":false,"error_code":404,"description":"Not found"}"""))
            }
          }

          temporary.use { root =>
            serve(mock).use { uri =>
              val uploadPath = root.resolve("sample-upload.txt")
              val downloadPath = root.resolve("downloaded.txt")
              val cfg = TelegramConfig(
                apiBase = uri.renderString,
                botToken = syntheticToken,
                maxRateLimitWaitMs = 5000
              )
              for
                _ <- IO.blocking(Files.writeString(uploadPath, "payload-1234", UTF_8))
                commands <- Deferred[IO, GraalCommandSdk]
                _ <- TelegramService.resource(root, IO.pure(cfg)).use { svc =>
                  GraalManager.resource(root.resolve("graal"), commands.get).use { manager =>
                    val app = httpApp {
                      GraalJsRoutes.register(manager)
                      TelegramRoutes.register(svc)
                    }
                    for
                      _ <- commands.complete(GraalCommandSdk(app))
                      jsUpload = uploadPath.toString.replace("\\", "\\\\")
                      jsDownload = downloadPath.toString.replace("\\", "\\\\")
                      sub <- manager.submit(
                        JavaScript.source(s"""
                          const tg = tools.telegram;
                          // 1. send without chatId -> auto-discovers 998877 via getUpdates AND retries 429 automatically!
                          const sent = (await tg.send({
                            text: "Hello from Groot!",
                            buttons: [[{ text: "Open", url: "https://yjgbg.com" }]]
                          })).body;
                          const edited = (await tg.edit({ messageId: sent.messageId, text: "Edited text" })).body;
                          const pinned = (await tg.pin({ messageId: sent.messageId })).body;
                          const unpinned = (await tg.unpin({ messageId: sent.messageId })).body;
                          const deleted = (await tg.delete({ messageId: sent.messageId })).body;

                          // 2. updates with ack=true then unreadOnly=true
                          const u1 = (await tg.updates({ unreadOnly: true, ack: true })).body;
                          const u2 = (await tg.updates({ unreadOnly: true, ack: true })).body;

                          // 3. multipart document upload + file download (both disk & inline base64)
                          const docMsg = (await tg.document({ source: "$jsUpload", caption: "Report file", filename: "report.txt" })).body;
                          const dlInline = (await tg.download({ fileId: "doc_fid_1" })).body;
                          const dlDisk = (await tg.download({ fileId: "doc_fid_1", outputPath: "$jsDownload" })).body;

                          // 4. me + webhook + raw api
                          const me = (await tg.me()).body;
                          const wh = (await tg.webhook.info()).body;
                          const raw = (await tg.api({ method: "sendChatAction", payload: { chat_id: "998877", action: "typing" } })).body;

                          console.log(JSON.stringify({
                            sentChatId: sent.chatId,
                            sentText: sent.text,
                            editedText: edited.text,
                            pinOk: pinned.ok && unpinned.ok && deleted.ok,
                            u1Count: u1.updates.length,
                            cbData: u1.updates[1].callbackQuery.data,
                            u2Count: u2.updates.length,
                            docAttachment: docMsg.attachments[0].fileId,
                            dlInlineB64: dlInline.contentBase64,
                            dlDiskSize: dlDisk.sizeBytes,
                            meDefaultChat: me.defaultChatId,
                            meLastUpdate: me.lastUpdateId,
                            whUrl: wh.url,
                            raw
                          }));
                        """),
                        "15s",
                        "25s"
                      )
                      done <- awaitUntil(manager.status(sub.id))(_.terminal)
                      log <- manager.logs(sub.id).map(_.toString)
                      _ = assertEquals(done.state, "succeeded", log)
                      s = j(log.linesIterator.find(_.startsWith("{")).getOrElse(fail(log))).hcursor
                      _ = assertEquals(s.get[String]("sentChatId"), Right("998877"))
                      _ = assertEquals(s.get[String]("sentText"), Right("Hello from Groot!"))
                      _ = assertEquals(s.get[String]("editedText"), Right("Edited text"))
                      _ = assertEquals(s.get[Boolean]("pinOk"), Right(true))
                      _ = assertEquals(s.get[Int]("u1Count"), Right(2))
                      _ = assertEquals(s.get[String]("cbData"), Right("approve:42"))
                      _ = assertEquals(s.get[Int]("u2Count"), Right(0))
                      _ = assertEquals(s.get[String]("docAttachment"), Right("doc_fid_1"))
                      expectedB64 = Base64.getEncoder.encodeToString("hello-tg-doc".getBytes(UTF_8))
                      _ = assertEquals(s.get[String]("dlInlineB64"), Right(expectedB64))
                      _ = assertEquals(s.get[Long]("dlDiskSize"), Right(12L))
                      diskContent <- IO.blocking(Files.readString(downloadPath, UTF_8))
                      _ = assertEquals(diskContent, "hello-tg-doc")
                      _ = assertEquals(s.get[String]("meDefaultChat"), Right("998877"))
                      _ = assertEquals(s.get[Long]("meLastUpdate"), Right(501L))
                      _ = assertEquals(s.get[String]("whUrl"), Right(""))
                      _ = assertEquals(s.get[Boolean]("raw"), Right(true))
                      hits <- rateLimitHits.get
                      _ = assertEquals(hits, 2)
                      offsets <- seenOffsets.get
                      _ = assert(offsets.contains(502L), s"Expected acked offset 502 in $offsets")
                      mp <- uploadedBody.get
                      _ = assert(mp.contains("payload-1234") && mp.contains("filename=\"report.txt\""))
                    yield ()
                  }
                }
              yield ()
            }
          }
        }
      }
    }
  }
