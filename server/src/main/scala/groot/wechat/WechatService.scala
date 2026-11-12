package groot.wechat

import cats.effect.{FiberIO, IO, Ref, Resource}
import cats.effect.std.{Mutex, Queue, Supervisor}
import cats.syntax.all.*
import groot.notify.{NotifyRequest, NotifyService}
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

final class WechatService private[wechat] (store: WechatStore, api: WechatApi,
    publish: NotifyRequest => IO[Unit], supervisor: Supervisor[IO], mutex: Mutex[IO], retry: FiniteDuration,
    loginLifetime: FiniteDuration):
  import WechatApi.{field, required}
  private case class LoginState(status: String, message: String, wechatid: Option[String] = None)
  private case class Login(id: String, qr: String, text: String, expires: Instant, state: Ref[IO, LoginState], codes: Queue[IO, String])
  private var logins = Map.empty[String, Login]
  private var workers = Map.empty[String, FiberIO[Unit]]
  private var closed = false
  private def serial[A](action: => IO[A]): IO[A] = mutex.lock.surround(IO.defer {
    if closed then IO.raiseError(WechatFailure("wechat_closed", "WeChat service is shutting down", 503)) else action
  })

  def list: IO[Vector[WechatAccount]] = store.list
  def bind(input: WechatBind): IO[Unit] = IO.defer {
    validateId(input.wechatid)
    input.session.foreach { value =>
      if !value.matches("ses[A-Za-z0-9_-]{1,125}") then
        throw WechatFailure("invalid_wechat", "session must be an OpenCode session ID starting with ses")
    }
    store.bind(input.wechatid, input.session)
  }
  def send(input: WechatSend): IO[WechatReceipt] = IO.defer {
    validateId(input.wechatid)
    if input.message.trim.isEmpty || input.message.codePointCount(0, input.message.length) > 4000 then
      throw WechatFailure("invalid_wechat", "message must contain 1 to 4000 Unicode characters and be nonblank")
    store.get(input.wechatid).flatMap { account =>
      if account.status == "login_required" then IO.raiseError(WechatFailure("wechat_login_required", "Run wechat.login again", 409))
      else if account.context.isEmpty then IO.raiseError(WechatFailure("wechat_context_required", "Send a message to ClawBot from this WeChat account first", 409))
      else api.send(account, input.message)
    }
  }
  private def validateId(value: String): Unit =
    if value.trim.isEmpty || value.length > 256 || value.exists(_.isControl) then
      throw WechatFailure("invalid_wechat", "wechatid must be the user ID returned by login or accounts")

  /** Login RPCs return immediately; all upstream long polls run in supervised fibers. */
  def login(input: WechatLogin): IO[String] = input.loginId match
    case None =>
      if input.verifyCode.nonEmpty then IO.raiseError(WechatFailure("invalid_wechat", "verifyCode requires loginId"))
      else serial { for
        now <- IO.realTimeInstant
        _ <- IO {
          logins = logins.filter((_, login) => login.expires.plusSeconds(3600).isAfter(now))
          if logins.size >= 64 then throw WechatFailure("wechat_busy", "Too many recent login attempts; retry later", 429)
        }
        accounts <- store.all
        qr <- api.qr(accounts.map(_.token))
        id <- IO(s"wxlogin_${UUID.randomUUID().toString.replace("-", "")}")
        text <- IO {
          val url = required(qr, "qrcode_img_content")
          if url.length > 2048 then throw WechatFailure("wechat_upstream", "QR content is too large", 502)
          s"${QrText.render(url)}\n$url"
        }
        state <- Ref.of[IO, LoginState](LoginState("wait", "请用微信扫描二维码，并在手机上确认。"))
        codes <- Queue.bounded[IO, String](1)
        entry = Login(id, required(qr, "qrcode"), text, now.plusMillis(loginLifetime.toMillis), state, codes)
        result <- IO.uncancelable { _ =>
          IO { logins += id -> entry } *>
            supervisor.supervise(loginLoop(entry, WechatApi.defaultBase, None)
              .timeoutTo(loginLifetime, state.set(LoginState("expired", "登录已过期，请重新调用 wechat.login({})。")))) *>
            show(entry, qr = true)
        }
      yield result }
    case Some(id) => for {
      entry <- serial(IO(logins.getOrElse(id, throw WechatFailure("wechat_not_found", "Unknown loginId; start a new login", 404))))
      _ <- input.verifyCode.traverse_ { code =>
        if !code.matches("[0-9]{1,16}") then IO.raiseError(WechatFailure("invalid_wechat", "verifyCode must contain 1 to 16 digits"))
        else IO.uncancelable { _ => entry.state.modify { state =>
          if state.status.contains("need_verifycode") then (LoginState("verifying", "正在提交 PIN。"), true)
          else (state, false)
        }.flatMap { accepted =>
          if !accepted then IO.raiseError(WechatFailure("wechat_conflict", "This login is not waiting for a PIN", 409))
          else entry.codes.offer(code)
        }}
      }
      result <- show(entry, qr = false)
    } yield result

  private def show(entry: Login, qr: Boolean): IO[String] = entry.state.get.map { state =>
    s"loginId: ${entry.id}\nstatus: ${state.status}\nexpiresAt: ${entry.expires}\n" +
      state.wechatid.fold("")(id => s"wechatid: $id\n") + state.message +
      (if qr then s"\n\n${entry.text}" else "") +
      s"\n\nwechat.login({loginId: \"${entry.id}\"})\nwechat.login({loginId: \"${entry.id}\", verifyCode: \"手机显示的数字\"})"
  }

  private def loginLoop(entry: Login, base: String, pin: Option[String]): IO[Unit] = IO.defer {
    api.status(base, entry.qr, pin).attempt.flatMap {
      case Left(_) => entry.state.set(LoginState("retrying", "微信连接暂时失败，正在重试。")) *>
        IO.sleep(retry) *> loginLoop(entry, base, pin)
      case Right(response) =>
        val status = required(response, "status")
        def again(nextBase: String = base, nextPin: Option[String] = pin): IO[Unit] =
          IO.sleep(retry) *> loginLoop(entry, nextBase, nextPin)
        if status.contains("need_verifycode") then
          entry.state.set(LoginState(status, if pin.nonEmpty then "PIN 不匹配，请重新提交手机显示的数字。" else "请提交手机微信显示的 PIN。")) *>
            entry.codes.take.flatMap { code =>
              entry.state.set(LoginState("verifying", "正在提交 PIN。")) *> loginLoop(entry, base, Some(code))
            }
        else status match
          case "confirmed" =>
            val id = required(response, "ilink_user_id")
            validateId(id)
            val account = Account(id, required(response, "ilink_bot_id"), required(response, "bot_token"),
              WechatApi.trustedBase(field(response, "baseurl").getOrElse(base)))
            install(account) *> entry.state.set(LoginState("confirmed", "登录成功；使用此 wechatid 发送消息或绑定会话。", Some(id)))
          case "binded_redirect" =>
            entry.state.set(LoginState(status, "此微信已连接，无需重复登录；调用 wechat.accounts 查看已保存的 wechatid。", field(response, "ilink_user_id")))
          case "expired" | "verify_code_blocked" =>
            entry.state.set(LoginState(status, "二维码已失效或 PIN 已被锁定，请重新调用 wechat.login({})。"))
          case "scaned_but_redirect" =>
            val next = WechatApi.trustedBase(s"https://${required(response, "redirect_host")}")
            entry.state.set(LoginState(status, "已扫码，正在连接微信节点。")) *> again(next)
          case "scaned" => entry.state.set(LoginState(status, "已扫码，请在手机上确认。")) *> again(nextPin = None)
          case "wait" => entry.state.set(LoginState(status, "等待微信扫码。")) *> again()
          case _ => entry.state.set(LoginState("failed", "微信返回了不支持的登录状态，请重新登录。"))
    }
  }.handleErrorWith {
    case e: WechatFailure => entry.state.set(LoginState("failed", e.getMessage))
    case _ => entry.state.set(LoginState("failed", "登录处理失败，请重新登录。"))
  }

  private def install(account: Account): IO[Unit] = serial { IO.uncancelable { _ =>
    workers.get(account.wechatid).traverse_(_.cancel) *> store.save(account) *> start(account.wechatid)
  }}
  private def start(id: String): IO[Unit] = supervisor.supervise((receive(id), deliver(id)).parTupled.void)
    .flatMap(fiber => IO { workers += id -> fiber })
  private def receive(id: String): IO[Unit] = IO.defer {
    (store.get(id).flatMap { a =>
      if a.status == "login_required" then IO.never
      else api.updates(a).flatMap(store.ingest(a, _))
    }).attempt.flatMap {
      case Right(_) => IO.sleep(retry) *> receive(id)
      case Left(e: WechatFailure) if e.code == "wechat_login_required" =>
        store.status(id, "login_required").attempt.void *> IO.sleep(retry) *> receive(id)
      case Left(e: WechatFailure) if e.code == "wechat_timeout" => receive(id)
      case Left(_) => store.status(id, "reconnecting").attempt.void *> IO.sleep(retry.max(5.seconds)) *> receive(id)
    }
  }
  private def deliver(id: String): IO[Unit] = IO.defer {
    store.pending(id).flatMap {
      case None => IO.sleep(retry)
      case Some(item) =>
        // Notify admission and marking are cancellation-masked, but a process crash can still replay a message.
        IO.uncancelable(_ => publish(NotifyRequest(item.session, item.message)) *> store.delivered(item.id))
    }.handleErrorWith(_ => IO.sleep(retry)) *> deliver(id)
  }
  private def restore: IO[Unit] = store.all.flatMap(_.traverse_(a => start(a.wechatid)))
  private def close: IO[Unit] = mutex.lock.surround(IO { closed = true })

object WechatService:
  def resource(notify: NotifyService, home: Path = groot.schedule.Store.defaultHome.resolve("wechat")): Resource[IO, WechatService] =
    WechatApi.resource.flatMap(api => create(home, api, request => notify.send(request).void))

  private[wechat] def create(home: Path, api: WechatApi, publish: NotifyRequest => IO[Unit],
      retry: FiniteDuration = 1.second, loginLifetime: FiniteDuration = 5.minutes): Resource[IO, WechatService] = for
    store <- WechatStore.resource(home)
    supervisor <- Supervisor[IO](await = false)
    mutex <- Resource.eval(Mutex[IO])
    service <- Resource.make(IO(new WechatService(store, api, publish, supervisor, mutex, retry, loginLifetime)))(_.close)
    _ <- Resource.eval(service.restore)
  yield service
