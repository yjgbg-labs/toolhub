package groot.wechat

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Base64, UUID}
import java.util.concurrent.ThreadLocalRandom
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

/** iLink protocol, referenced against Tencent/openclaw-weixin 0ad1ae9 (3.0.0-beta-0). */
private[wechat] final class WechatApi(client: Client[IO]):
  import WechatApi.*

  private def request(method: Method, uri: Uri, body: Json = Json.obj(), token: Option[String] = None,
      timeout: FiniteDuration = 35.seconds): IO[Json] = IO.defer {
    val common = Vector(Header.Raw(CIString("iLink-App-Id"), "bot"), Header.Raw(CIString("iLink-App-ClientVersion"), "196608"))
    val auth = if method == Method.POST then Vector(
      Header.Raw(CIString("AuthorizationType"), "ilink_bot_token"),
      Header.Raw(CIString("X-WECHAT-UIN"), Base64.getEncoder.encodeToString(
        java.lang.Integer.toUnsignedString(ThreadLocalRandom.current().nextInt()).getBytes(UTF_8)))
    ) ++ token.map(t => Header.Raw(CIString("Authorization"), s"Bearer $t")) else Vector.empty
    val req = Request[IO](method, uri, headers = Headers(common ++ auth))
    client.run(if method == Method.POST then req.withEntity(body) else req).use { response =>
      if !response.status.isSuccess then IO.raiseError(WechatFailure("wechat_upstream", s"WeChat HTTP ${response.status.code}", 502))
      else response.body.take(8 * 1024 * 1024 + 1L).compile.toVector.flatMap { bytes => IO {
        if bytes.size > 8 * 1024 * 1024 then throw WechatFailure("wechat_upstream", "WeChat response exceeds 8 MiB", 502)
        val json = io.circe.parser.parse(new String(bytes.toArray, UTF_8)).fold(
          _ => throw WechatFailure("wechat_upstream", "Invalid WeChat JSON response", 502), identity)
        if !json.isObject then throw WechatFailure("wechat_upstream", "Expected a WeChat JSON object", 502)
        val c = json.hcursor
        val codes = Vector("ret", "errcode").map { key => c.get[Option[Int]](key).fold(
          _ => throw WechatFailure("wechat_upstream", "Invalid WeChat result code", 502), _.getOrElse(0)) }
        if codes.contains(-14) then throw WechatFailure("wechat_login_required", "WeChat credentials expired; run wechat.login again", 409)
        if codes.exists(_ != 0) then throw WechatFailure("wechat_upstream", s"WeChat rejected request (${codes.mkString(",")})", 502)
        json
      }}
    }.timeoutTo(timeout, IO.raiseError(WechatFailure("wechat_timeout", "WeChat request timed out", 504)))
      .handleErrorWith {
        case e: WechatFailure => IO.raiseError(e)
        case _ => IO.raiseError(WechatFailure("wechat_upstream", "WeChat transport failed", 502))
      }
  }

  def qr(tokens: Vector[String]): IO[Json] = request(Method.POST,
    Uri.unsafeFromString(s"$defaultBase/ilink/bot/get_bot_qrcode").withQueryParam("bot_type", "3"),
    Json.obj("local_token_list" -> Json.arr(tokens.take(10).map(Json.fromString)*)))

  def status(base: String, qr: String, pin: Option[String]): IO[Json] =
    val uri = Uri.unsafeFromString(s"${base.stripSuffix("/")}/ilink/bot/get_qrcode_status").withQueryParam("qrcode", qr)
    request(Method.GET, pin.fold(uri)(uri.withQueryParam("verify_code", _)))

  def updates(account: Account): IO[Json] = request(Method.POST,
    Uri.unsafeFromString(s"${account.baseUrl.stripSuffix("/")}/ilink/bot/getupdates"),
    Json.obj("get_updates_buf" -> Json.fromString(account.cursor), "base_info" -> baseInfo), Some(account.token), 70.seconds)

  def send(account: Account, message: String): IO[WechatReceipt] = IO.defer {
    val id = s"groot-${UUID.randomUUID()}"
    val msg = Json.obj("from_user_id" -> Json.fromString(""), "to_user_id" -> Json.fromString(account.wechatid),
      "client_id" -> Json.fromString(id), "message_type" -> Json.fromInt(2), "message_state" -> Json.fromInt(2),
      "context_token" -> Json.fromString(account.context.get), "item_list" -> Json.arr(Json.obj(
        "type" -> Json.fromInt(1), "text_item" -> Json.obj("text" -> Json.fromString(message)))))
    request(Method.POST, Uri.unsafeFromString(s"${account.baseUrl.stripSuffix("/")}/ilink/bot/sendmessage"),
      Json.obj("msg" -> msg, "base_info" -> baseInfo), Some(account.token)).map { result =>
      WechatReceipt(id, field(result, "message_id"))
    }
  }

private[wechat] object WechatApi:
  val defaultBase = "https://ilinkai.weixin.qq.com"
  private val baseInfo = Json.obj("channel_version" -> Json.fromString("3.0.0-beta-0-groot.1"), "bot_agent" -> Json.fromString("Groot/1.0"))
  def field(json: Json, name: String): Option[String] = json.hcursor.downField(name).focus.flatMap { v =>
    v.asString.orElse(v.asNumber.map(_.toString)).filter(_.nonEmpty)
  }
  def required(json: Json, name: String): String = field(json, name).getOrElse(
    throw WechatFailure("wechat_upstream", s"WeChat response missing $name", 502))
  def trustedBase(value: String): String =
    val uri = Uri.fromString(value).toOption.getOrElse(throw WechatFailure("wechat_upstream", "Invalid WeChat API address", 502))
    val host = uri.host.map(_.value.toLowerCase(java.util.Locale.ROOT)).getOrElse("")
    if uri.scheme.map(_.value) != Some("https") || !host.endsWith(".weixin.qq.com") ||
        uri.userInfo.nonEmpty || uri.port.exists(_ != 443) || uri.query.nonEmpty || uri.fragment.nonEmpty ||
        (uri.path.renderString != "" && uri.path.renderString != "/") then
      throw WechatFailure("wechat_upstream", "Unexpected WeChat API address", 502)
    value.stripSuffix("/")
  def resource: Resource[IO, WechatApi] = EmberClientBuilder.default[IO]
    .withTimeout(150.seconds).withIdleConnectionTime(150.seconds).build.map(new WechatApi(_))
