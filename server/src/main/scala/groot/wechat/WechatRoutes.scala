package groot.wechat

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object WechatRoutes:
  def register(service: WechatService)(using CommandsScope): Unit = commands("wechat") {
    description("Connect multiple WeChat accounts, send text and route ClawBot messages to OpenCode through notify")
    exposeOn(cli, codemode, graaljs)
    val invalid = response.json[ErrorBody](400) { description("Invalid WeChat input") }
    val missing = response.json[ErrorBody](404) { description("Account or login not found") }
    val conflict = response.json[ErrorBody](409) { description("Login, incoming conversation context or PIN state required") }
    val busy = response.json[ErrorBody](429) { description("Too many login attempts") }
    val upstream = response.json[ErrorBody](502) { description("WeChat protocol or transport failure") }
    val closed = response.json[ErrorBody](503) { description("Service shutting down") }
    val timeout = response.json[ErrorBody](504) { description("WeChat request timed out") }
    recover { case error: WechatFailure =>
      val output = error.status match
        case 404 => missing
        case 409 => conflict
        case 429 => busy
        case 502 => upstream
        case 503 => closed
        case 504 => timeout
        case _ => invalid
      output(ErrorBody(error.code, error.getMessage))
    }
    command[PlainText]("login") {
      description("Start with {} for a plain-text QR; query with loginId, submit phone PIN with loginId and verifyCode; success returns wechatid")
      val input = body[WechatLogin]("Asynchronous self-account QR login; expires after five minutes")
      handle { service.login(input.value).map(PlainText(_)) }
    }
    command[Vector[WechatAccount]]("accounts") {
      description("List saved WeChat user IDs, bot IDs, session bindings, connection status and pending notification parts")
      handle { service.list }
    }
    command[WechatReceipt]("send") {
      description("Send 1–4000 Unicode characters to a logged-in wechatid using its latest incoming context; success is API acceptance, not a delivery receipt")
      val input = body[WechatSend]("WeChat user ID and text message")
      handle { service.send(input.value) }
    }
    command[Unit]("bind") {
      description("Persist wechatid → OpenCode session routing; session=null pauses delivery; queued messages follow the current binding")
      val input = body[WechatBind]("WeChat user ID and target OpenCode session ID, or null to unbind")
      handle { service.bind(input.value) }
    }
  }
