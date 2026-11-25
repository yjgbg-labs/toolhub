package groot.notify

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object NotifyRoutes:
  def register(service: NotifyService, triggers: Option[NotifyTriggerManager] = None)(using CommandsScope): Unit = commands("notify") {
    description("Session notification triggers (notify.when / notify.at) and direct server-side delivery to OpenCode and Antigravity sessions")
    val invalid = response.json[ErrorBody](400) { description("Invalid or unsupported session ID, trigger definition, or notification") }
    val notFound = response.json[ErrorBody](404) { description("Notify trigger or target session not found") }
    val offline = response.json[ErrorBody](409) { description("Target session runtime (OpenCode or Antigravity) is offline") }
    val large = response.json[ErrorBody](413) { description("Notification or script exceeds its UTF-8 size limit") }
    val busy = response.json[ErrorBody](429) { description("Active trigger capacity is full") }
    val badGateway = response.json[ErrorBody](502) { description("Upstream client runtime failed to accept notification") }
    val closed = response.json[ErrorBody](503) { description("Notification service is shutting down") }
    recover {
      case error: NotifyFailure =>
        val output = error.code match
          case "notify_not_found" => notFound
          case "notify_offline" => offline
          case "notify_limit" => large
          case "notify_busy" => busy
          case "delivery_error" => badGateway
          case "notify_closed" => closed
          case _ => invalid
        output(ErrorBody(error.code, error.getMessage))
    }
    command[NotifyReceipt]("send") {
      exposeOn(cli)
      description("Deliver a message directly to an OpenCode session (ses...) or Antigravity conversation (UUID) via PartialFunction routing")
      val input = body[NotifyRequest]("Target session ID and full message")
      handle { service.send(input.value) }
    }
    triggers.foreach { manager =>
      command[NotifyTrigger]("when") {
        description("Evaluate a GraalJS predicate periodically (after optional initial delay, default 0ms); when code returns a non-empty string, send that string to the caller session. Set once=true (default) for one-shot or once=false for repeated notifications.")
        val sessionId = param[String]("sessionId", "Target session ID (implicitly supplied by session-bound clients)") { minLength(1) }
        val cwd = param[Option[String]]("cwd", "Optional working directory inherited by predicate executions")
        val input = body[NotifyWhenRequest]("Predicate code returning a non-empty string when triggered, every interval (default 15s), initial delay (default 0ms), once flag (default true), and optional timeout")
        handle { manager.when(sessionId.value, cwd.value, input.value) }
      }
      command[NotifyTrigger]("at") {
        description("Schedule a one-shot reminder at an ISO-8601 timestamp or relative duration (e.g. +10m), implemented as a delayed notify.when")
        val sessionId = param[String]("sessionId", "Target session ID (implicitly supplied by session-bound clients)") { minLength(1) }
        val input = body[NotifyAtRequest]("Target timestamp/relative duration and reminder message")
        handle { manager.at(sessionId.value, input.value) }
      }
      command[List[NotifyTrigger]]("list") {
        description("List notify.when / notify.at triggers")
        val activeOnly = param[Option[Boolean]]("activeOnly", "Only return currently active triggers")
        handle { manager.list(activeOnly.value.getOrElse(false)) }
      }
      command[NotifyTrigger]("cancel") {
        description("Cancel an active notify.when / notify.at trigger by ID")
        val id = param[String]("id", "Trigger ID (ntf_...)") { minLength(1) }
        handle { manager.cancel(id.value) }
      }
    }
  }
