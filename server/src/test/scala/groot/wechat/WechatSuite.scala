package groot.wechat

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import com.google.zxing.{BinaryBitmap, RGBLuminanceSource}
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import framework.model.JsonModel
import framework.rpcDsl.*
import groot.notify.{NotifyEvent, NotifyEventKind, NotifyService}
import groot.graaljs.GraalTestSupport.*
import groot.graaljs.GraalCommandSdk
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri}
import org.http4s.circe.*
import org.http4s.client.Client
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

class WechatSuite extends CatsEffectSuite:
  private def json(value: String): Json = io.circe.parser.parse(value).toOption.get
  private def message(id: String, owner: String = "user-a", token: String = "ctx-a", text: String = "你好 🧪"): Json =
    Json.obj("message_id" -> Json.fromString(id), "from_user_id" -> Json.fromString(owner),
      "message_type" -> Json.fromInt(1), "context_token" -> Json.fromString(token), "item_list" -> Json.arr(
        Json.obj("type" -> Json.fromInt(1), "text_item" -> Json.obj("text" -> Json.fromString(text)))))
  private def batch(cursor: String, messages: Json*): Json = Json.obj("get_updates_buf" -> Json.fromString(cursor), "msgs" -> Json.arr(messages*))
  private val a = Account("user-a", "bot-a", "token-a", WechatApi.defaultBase)
  private val b = Account("user-b", "bot-b", "token-b", WechatApi.defaultBase)
  private def reader(service: NotifyService): Resource[IO, Queue[IO, NotifyEvent]] = for
    events <- Resource.eval(Queue.unbounded[IO, NotifyEvent])
    stream <- Resource.eval(service.subscribe)
    _ <- stream.evalMap(events.offer).compile.drain.background
    _ <- Resource.eval(events.take.timeout(3.seconds).map(event => assertEquals(event.kind, NotifyEventKind.ready)))
  yield events

  private class Fake(val requests: Queue[IO, (Request[IO], Json)], val qa: Queue[IO, Json], val qb: Queue[IO, Json],
      val statuses: Queue[IO, Json], val canceled: Ref[IO, Int]):
    val api = new WechatApi(Client.fromHttpApp(HttpApp[IO] { req =>
      (if req.method == Method.POST then req.as[Json] else IO.pure(Json.obj())).flatMap { body =>
        requests.offer((req, body)) *> (req.uri.path.renderString match
          case "/ilink/bot/get_bot_qrcode" => IO.pure(json("""{"qrcode":"qr-token","qrcode_img_content":"https://weixin.qq.com/test?qr=123"}"""))
          case "/ilink/bot/get_qrcode_status" => statuses.take
          case "/ilink/bot/getupdates" =>
            val token = req.headers.get(CIString("Authorization")).map(_.head.value)
            (if token.contains("Bearer token-a") then qa else qb).take.onCancel(canceled.update(_ + 1))
          case "/ilink/bot/sendmessage" => IO.pure(json("""{"ret":0,"message_id":9007199254740993}"""))
          case _ => IO.raiseError(new Exception("Unexpected request"))
        ).map(value => Response[IO](Status.Ok).withEntity(value))
      }
    }))
  private def fake: IO[Fake] = (Queue.unbounded[IO, (Request[IO], Json)], Queue.unbounded[IO, Json],
    Queue.unbounded[IO, Json], Queue.unbounded[IO, Json], Ref.of[IO, Int](0)).mapN(new Fake(_, _, _, _, _))
  private def confirm(account: Account): Json = Json.obj("status" -> Json.fromString("confirmed"),
    "ilink_user_id" -> Json.fromString(account.wechatid), "ilink_bot_id" -> Json.fromString(account.botId),
    "bot_token" -> Json.fromString(account.token), "baseurl" -> Json.fromString(account.baseUrl))
  private def loginId(text: String): String = text.linesIterator.find(_.startsWith("loginId: ")).get.stripPrefix("loginId: ")

  test("plain-text QR decodes back to its Unicode URL with a four-module quiet zone") {
    val url = "https://weixin.qq.com/登录?ticket=sample"
    val lines = QrText.render(url).linesIterator.toVector
    val modules = lines.head.length / 2
    val scale = 6
    val width = modules * scale
    val pixels = Array.tabulate(width * width) { i =>
      if lines(i / width / scale)(i % width / scale * 2) == '█' then 0 else 0xffffff
    }
    assert(lines.take(4).forall(_.trim.isEmpty))
    assertEquals(new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, width, pixels)))).getText, url)
  }

  test("inbox, cursor, context and binding survive restart; replay is deduplicated and malformed batches roll back") {
    temporary.use { home =>
      WechatStore.resource(home).use { store => for
        _ <- store.save(a)
        _ <- store.save(b)
        _ <- store.bind(a.wechatid, Some("ses_a"))
        _ <- store.ingest(a, batch("cursor-a", message("one"), message("foreign", "user-b", "wrong")))
        _ <- store.ingest(b, batch("cursor-b", message("two", "user-b", "ctx-b")))
        _ <- store.ingest(a, batch("cursor-a", message("one")))
        broken <- store.ingest(a, json("""{"get_updates_buf":"lost","msgs":{}}""")).attempt
        _ = assert(broken.isLeft)
        saved <- store.get(a.wechatid)
        _ = assertEquals((saved.cursor, saved.context), ("cursor-a", Some("ctx-a")))
        count <- store.list
        _ = assertEquals(count.map(_.pending), Vector(1L, 1L))
      yield () } *> WechatStore.resource(home).use { store => for
        saved <- store.get(a.wechatid)
        _ = assertEquals(saved.session, Some("ses_a"))
        item <- store.pending(a.wechatid).map(_.get)
        _ = assert(item.message.contains("你好 🧪"))
        _ = assertEquals(item.session, "ses_a")
        _ <- store.delivered(item.id)
        _ <- store.ingest(saved, batch("cursor-a", message("one")))
        pending <- store.pending(a.wechatid)
        _ = assertEquals(pending, None)
        _ <- store.save(a.copy(botId = "new-bot", token = "new-token"))
        replaced <- store.get(a.wechatid)
        _ = assertEquals((replaced.cursor, replaced.context, replaced.session), ("", None, Some("ses_a")))
        other <- store.get(b.wechatid)
        _ = assertEquals((other.token, other.context), ("token-b", Some("ctx-b")))
      yield () }
    }
  }

  test("unbound media and long Unicode messages retain all items and split below notify size limit") {
    temporary.use { home => WechatStore.resource(home).use { store =>
      val text = "🧪" * 22000
      val media = json("""{"message_id":"media","from_user_id":"user-a","message_type":1,"item_list":[{"type":2,"image_item":{"media":{"encrypt_query_param":"opaque"}}},{"type":3,"voice_item":{"text":"语音识别"}}]}""")
      for
        _ <- store.save(a)
        _ <- store.ingest(a, batch("next", message("long", text = text), media))
        unbound <- store.pending(a.wechatid)
        _ = assertEquals(unbound, None)
        _ <- store.bind(a.wechatid, Some("ses_media"))
        pieces <- Vector.fill(4)(()).traverse { _ => store.pending(a.wechatid).map(_.get).flatTap(p => store.delivered(p.id)) }
        _ = assert(pieces.forall(_.message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 65536))
        _ = assertEquals(pieces.take(3).map(_.message.split("\n", 2)(1)).mkString, text)
        _ = assert(pieces.last.message.contains("image_item"))
        _ = assert(pieces.last.message.contains("语音识别"))
      yield ()
    }}
  }

  test("asynchronous QR/PIN redirect login saves identity, preserves large IDs and sends account-scoped protocol fields") {
    temporary.use { home => for
      upstream <- fake
      _ <- WechatService.create(home, upstream.api, _ => IO.unit, retry = 10.millis).use { service => for
        text <- service.login(WechatLogin()).timeout(1.second)
        id = loginId(text)
        first <- upstream.requests.take
        _ = assertEquals(first._2.hcursor.get[Vector[String]]("local_token_list"), Right(Vector.empty))
        _ <- upstream.statuses.offer(json("""{"status":"confirmed_but_need_verifycode"}"""))
        _ <- awaitUntil(service.login(WechatLogin(Some(id))))(_.contains("status: confirmed_but_need_verifycode"))
        _ <- service.login(WechatLogin(Some(id), Some("000000")))
        _ <- upstream.statuses.offer(json("""{"status":"need_verifycode"}"""))
        wrong <- awaitUntil(service.login(WechatLogin(Some(id))))(_.contains("status: need_verifycode"))
        _ = assert(wrong.contains("PIN 不匹配"))
        _ <- service.login(WechatLogin(Some(id), Some("123456")))
        _ <- upstream.statuses.offer(json("""{"status":"scaned_but_redirect","redirect_host":"ilinkai2.weixin.qq.com"}"""))
        _ <- upstream.statuses.offer(confirm(a))
        done <- awaitUntil(service.login(WechatLogin(Some(id))))(_.contains("status: confirmed"))
        _ = assert(done.contains("wechatid: user-a"))
        _ = assert(!done.contains("token-a"))
        noContext <- service.send(WechatSend("user-a", "回复")).attempt
        _ = assertEquals(noContext.swap.toOption.map(_.asInstanceOf[WechatFailure].code), Some("wechat_context_required"))
        _ <- upstream.qa.offer(batch("next", message("one")))
        _ <- awaitUntil(service.list)(_.headOption.exists(_.pending == 1))
        receipt <- service.send(WechatSend("user-a", "回复 🧪"))
        _ = assertEquals(receipt.messageId, Some("9007199254740993"))
        requests <- upstream.requests.tryTakeN(None)
        sent = requests.find(_._1.uri.path.renderString.endsWith("/sendmessage")).get
        poll = requests.filter(_._1.uri.path.renderString.endsWith("/get_qrcode_status"))
        _ = assert(poll.exists(_._1.uri.query.params.get("verify_code").contains("123456")))
        _ = assert(poll.exists(_._1.uri.host.exists(_.value == "ilinkai2.weixin.qq.com")))
        _ = assertEquals(sent._1.headers.get(CIString("Authorization")).map(_.head.value), Some("Bearer token-a"))
        _ = assertEquals(sent._1.headers.get(CIString("AuthorizationType")).map(_.head.value), Some("ilink_bot_token"))
        _ = assertEquals(sent._1.headers.get(CIString("iLink-App-ClientVersion")).map(_.head.value), Some("196608"))
        uin = new String(java.util.Base64.getDecoder.decode(sent._1.headers.get(CIString("X-WECHAT-UIN")).get.head.value))
        _ = assert(uin.toLong >= 0 && uin.toLong <= 0xffffffffL)
        msg = sent._2.hcursor.downField("msg")
        _ = assertEquals(msg.get[String]("to_user_id"), Right("user-a"))
        _ = assertEquals(msg.get[String]("context_token"), Right("ctx-a"))
        _ = assertEquals(msg.get[Int]("message_type"), Right(2))
        _ = assertEquals(msg.get[Int]("message_state"), Right(2))
        _ = assertEquals(sent._2.hcursor.downField("base_info").get[String]("bot_agent"), Right("Groot/1.0"))
      yield () }
      canceled <- upstream.canceled.get
      _ = assert(canceled > 0)
    yield () }
  }

  test("independent accounts resume offline inboxes on restart and route through real notify; stale credentials isolate one account") {
    temporary.use { home => for
      upstream <- fake
      _ <- WechatStore.resource(home).use { store =>
        store.save(a) *> store.save(b) *> store.bind("user-a", Some("ses_a")) *> store.bind("user-b", Some("ses_b")) *>
          store.ingest(a, batch("resume-a", message("one"))) *> store.ingest(b, batch("resume-b", message("two", "user-b", "ctx-b")))
      }
      _ <- NotifyService.resource().use { notify =>
        WechatService.create(home, upstream.api, request => notify.send(request).void, retry = 10.millis).use { service => for
          _ <- upstream.qa.offer(json("""{"ret":-14,"errmsg":"secret must not leak"}"""))
          _ <- awaitUntil(service.list)(_.exists(_.status == "login_required"))
          pending <- service.list
          _ = assertEquals(pending.map(_.pending), Vector(1L, 1L))
          _ <- reader(notify).use { events => for
            delivered <- List.fill(2)(()).traverse(_ => events.take.timeout(3.seconds))
            targets = delivered.flatMap(_.notification.map(_.targetSessionId)).toSet
            _ = assertEquals(targets, Set("ses_a", "ses_b"))
            _ <- awaitUntil(service.list)(_.forall(_.pending == 0))
            _ <- upstream.qb.offer(batch("b2", message("three", "user-b", "ctx-b2")))
            next <- events.take.timeout(3.seconds)
            _ = assertEquals(next.notification.map(_.targetSessionId), Some("ses_b"))
            _ <- service.send(WechatSend("user-b", "B reply"))
            denied <- service.send(WechatSend("user-a", "A reply")).attempt
            _ = assertEquals(denied.swap.toOption.map(_.asInstanceOf[WechatFailure].code), Some("wechat_login_required"))
            requests <- upstream.requests.tryTakeN(None)
            polls = requests.filter(_._1.uri.path.renderString.endsWith("/getupdates"))
            _ = assert(polls.exists(_._2.hcursor.get[String]("get_updates_buf") == Right("resume-a")))
            _ = assert(polls.exists(_._2.hcursor.get[String]("get_updates_buf") == Right("resume-b")))
            sent = requests.find(_._1.uri.path.renderString.endsWith("/sendmessage")).get
            _ = assertEquals(sent._2.hcursor.downField("msg").get[String]("context_token"), Right("ctx-b2"))
            _ = assertEquals(sent._1.headers.get(CIString("Authorization")).map(_.head.value), Some("Bearer token-b"))
          yield () }
        yield () }
      }
    yield () }
  }

  test("login expiration, unsupported redirect and route validation return stable public contracts without credentials") {
    temporary.use { home => for
      upstream <- fake
      _ <- WechatService.create(home, upstream.api, _ => IO.unit, retry = 10.millis, loginLifetime = 100.millis).use { service =>
        val app = httpApp { WechatRoutes.register(service) }
        val sdk = GraalCommandSdk(app)
        assertEquals(sdk.commands.toSet, Set("wechat.login", "wechat.accounts", "wechat.send", "wechat.bind"))
        assert(app.endpoints.forall(_.exposedOn == Set(cli, codemode, graaljs)))
        for
          text <- service.login(WechatLogin())
          id = loginId(text)
          _ <- awaitUntil(service.login(WechatLogin(Some(id))))(_.contains("status: expired"))
          invalid <- sdk.call("wechat.send", JsonModel[WechatSend].encoder(WechatSend("user-a", " ")))
          _ = assertEquals(invalid.hcursor.get[Int]("status"), Right(400))
          missing <- sdk.call("wechat.bind", JsonModel[WechatBind].encoder(WechatBind("missing", Some("ses_a"))))
          _ = assertEquals(missing.hcursor.get[Int]("status"), Right(404))
          badSession <- sdk.call("wechat.bind", JsonModel[WechatBind].encoder(WechatBind("missing", Some("oops"))))
          _ = assertEquals(badSession.hcursor.get[Int]("status"), Right(400))
          response <- app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/wechat/login"))
            .withEntity(JsonModel[WechatLogin].encoder(WechatLogin(verifyCode = Some("123")))))
          _ = assertEquals(response.status, Status.BadRequest)
          _ = intercept[WechatFailure](WechatApi.trustedBase("https://attacker.example"))
          _ = intercept[WechatFailure](WechatApi.trustedBase("https://ilinkai.weixin.qq.com@attacker.example"))
        yield ()
      }
    yield () }
  }

  test("relogin replaces only the matching worker, retains pending bindings and clears the old conversation context") {
    temporary.use { home => for
      upstream <- fake
      _ <- WechatStore.resource(home).use { store =>
        store.save(a) *> store.save(b) *> store.bind("user-a", Some("ses_a")) *> store.ingest(a, batch("old", message("one")))
      }
      _ <- WechatService.create(home, upstream.api, _ => IO.raiseError(new Exception("offline")), retry = 10.millis).use { service => for
        _ <- upstream.requests.take
        _ <- upstream.requests.take
        login <- service.login(WechatLogin())
        id = loginId(login)
        _ <- upstream.statuses.offer(confirm(a.copy(token = "token-new", botId = "bot-new")))
        _ <- awaitUntil(service.login(WechatLogin(Some(id))))(_.contains("status: confirmed"))
        accounts <- service.list
        _ = assertEquals(accounts.size, 2)
        changed = accounts.find(_.wechatid == "user-a").get
        _ = assertEquals((changed.botId, changed.session, changed.pending), ("bot-new", Some("ses_a"), 1L))
        _ = assertEquals(accounts.find(_.wechatid == "user-b").get.botId, "bot-b")
        canceled <- upstream.canceled.get
        _ = assertEquals(canceled, 1)
        noContext <- service.send(WechatSend("user-a", "reply")).attempt
        _ = assertEquals(noContext.swap.toOption.map(_.asInstanceOf[WechatFailure].code), Some("wechat_context_required"))
        _ <- service.bind(WechatBind("user-a", None))
        unbound <- service.list
        _ = assertEquals(unbound.find(_.wechatid == "user-a").get.session, None)
      yield () }
    yield () }
  }

  test("protocol failures reject nonzero return codes and malformed replies without exposing upstream secrets") {
    val responses = Vector(
      Response[IO](Status.BadGateway).withEntity("token-secret"),
      Response[IO](Status.Ok).withEntity(json("""{"ret":0,"errcode":-2,"errmsg":"token-secret"}""")),
      Response[IO](Status.Ok).withEntity(json("""{"ret":"invalid"}""")),
      Response[IO](Status.Ok).withEntity(Json.arr()),
      Response[IO](Status.Ok).withEntity("invalid JSON token-secret")
    )
    responses.traverse_ { response =>
      val api = new WechatApi(Client.fromHttpApp(HttpApp[IO](_ => IO.pure(response))))
      api.send(a.copy(context = Some("ctx")), "text").attempt.map { result =>
        val error = result.swap.toOption.get.asInstanceOf[WechatFailure]
        assertEquals(error.status, 502)
        assert(!error.getMessage.contains("token-secret"))
      }
    }
  }
