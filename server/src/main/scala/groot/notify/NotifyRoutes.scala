package groot.notify

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object NotifyRoutes:
  def register(service: NotifyService, triggers: Option[NotifyTriggerManager] = None)(using CommandsScope): Unit = commands("notify") {
    description("Session notification triggers (notify.when / notify.at) and live SSE broadcast for client plugins")
    val invalid = response.json[ErrorBody](400) { description("Invalid session ID, trigger definition, or notification") }
    val notFound = response.json[ErrorBody](404) { description("Notify trigger not found") }
    val offline = response.json[ErrorBody](409) { description("No notification subscriber is online") }
    val large = response.json[ErrorBody](413) { description("Notification or script exceeds its UTF-8 size limit") }
    val busy = response.json[ErrorBody](429) { description("Subscriber queue or trigger capacity is full") }
    val closed = response.json[ErrorBody](503) { description("Notification service is shutting down") }
    recover {
      case error: NotifyFailure =>
        val output = error.code match
          case "notify_not_found" => notFound
          case "notify_offline" => offline
          case "notify_limit" => large
          case "notify_busy" => busy
          case "notify_closed" => closed
          case _ => invalid
        output(ErrorBody(error.code, error.getMessage))
    }
    command[NotifyReceipt]("send") {
      exposeOn(cli)
      description("Broadcast message and targetSessionId to all online subscribers (CLI/internal bridge use only; not exposed to GraalJS)")
      val input = body[NotifyRequest]("Target session and full message")
      handle { service.send(input.value) }
    }
    command[SSE[NotifyEvent]]("subscribe") {
      exposeOn(cli)
      description("Subscribe to notifications for all sessions: ready, message and 15-second heartbeat events; live only, no replay")
      handle { service.subscribe.map(SSE(_)) }
    }
    triggers.foreach { manager =>
      command[NotifyTrigger]("when") {
        exposeOn(cli, graaljs)
        description("Evaluate a GraalJS predicate periodically (after optional initial delay, default 0ms); when code returns a non-empty string, send that string to targetSessionId. Set once=true (default) for one-shot or once=false for repeated notifications.")
        val input = body[NotifyWhenRequest]("Predicate code returning a non-empty string when triggered, targetSessionId, every interval (default 15s), initial delay (default 0ms), once flag (default true), and optional timeout")
        handle { manager.when(input.value) }
      }
      command[NotifyTrigger]("at") {
        exposeOn(cli, graaljs)
        description("Schedule a one-shot reminder at an ISO-8601 timestamp or relative duration (e.g. +10m), implemented as a delayed notify.when")
        val input = body[NotifyAtRequest]("Target session, at timestamp/relative duration, and reminder message")
        handle { manager.at(input.value) }
      }
      command[List[NotifyTrigger]]("list") {
        exposeOn(cli, graaljs)
        description("List notify.when / notify.at triggers")
        val activeOnly = param[Option[Boolean]]("activeOnly", "Only return currently active triggers")
        handle { manager.list(activeOnly.value.getOrElse(false)) }
      }
      command[NotifyTrigger]("cancel") {
        exposeOn(cli, graaljs)
        description("Cancel an active notify.when / notify.at trigger by ID")
        val id = param[String]("id", "Trigger ID (ntf_...)") { minLength(1) }
        handle { manager.cancel(id.value) }
      }
    }
  }
