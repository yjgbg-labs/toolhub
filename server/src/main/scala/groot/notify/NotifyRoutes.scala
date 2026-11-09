package groot.notify

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object NotifyRoutes:
  def register(service: NotifyService)(using CommandsScope): Unit = commands("notify") {
    description("Broadcast notifications to online subscribers for delivery to target OpenCode sessions")
    val invalid = response.json[ErrorBody](400) { description("Invalid session ID or notification") }
    val offline = response.json[ErrorBody](409) { description("No notification subscriber is online") }
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
      description("Broadcast message, display and targetSessionId to all online subscribers; success means queued for SSE, not acknowledged by OpenCode")
      val input = body[NotifyRequest]("Target session, model-visible message, and user-visible display description")
      handle { service.send(input.value) }
    }
    command[SSE[NotifyEvent]]("subscribe") {
      exposeOn(cli)
      description("Subscribe to notifications for all sessions: ready, message and 15-second heartbeat events; live only, no replay")
      handle { service.subscribe.map(SSE(_)) }
    }
  }
