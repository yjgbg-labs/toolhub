package groot.notify

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object NotifyRoutes:
  def register(service: NotifyService)(using CommandsScope): Unit = commands("notify") {
    description("Push notifications to online OpenCode session subscribers")
    val invalid = response.json[ErrorBody](400) { description("Invalid session ID or notification") }
    val offline = response.json[ErrorBody](409) { description("The target session has no online subscriber") }
    val large = response.json[ErrorBody](413) { description("Notification exceeds its UTF-8 size limit") }
    val busy = response.json[ErrorBody](429) { description("Subscriber queue is full; nothing was published") }
    val closed = response.json[ErrorBody](503) { description("Notification service is shutting down") }
    recover {
      case error: NotifyFailure =>
        val output = error.code match
          case "notify_offline" => offline
          case "notify_limit" => large
          case "notify_busy" => busy
          case "notify_closed" => closed
          case _ => invalid
        output(ErrorBody(error.code, error.getMessage))
    }
    command[NotifyReceipt]("send") {
      exposeOn(graaljs)
      description("Send message and display text to online subscribers of targetSessionId; success means queued for SSE, not acknowledged by OpenCode")
      val input = body[NotifyRequest]("Target session, model-visible message, and user-visible display description")
      handle { service.send(input.value) }
    }
    command[SSE[NotifyEvent]]("subscribe") {
      exposeOn(cli)
      description("Subscribe to one session: ready, message and 15-second heartbeat events; live only, no replay")
      val session = param[String]("sessionId", "OpenCode session ID") { minLength(1); maxLength(128) }
      handle { service.subscribe(session.value).map(SSE(_)) }
    }
  }
