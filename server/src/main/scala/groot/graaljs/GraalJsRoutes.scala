package groot.graaljs

import cats.effect.IO
import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object GraalJsRoutes:
  def register(manager: GraalManager)(using CommandsScope): Unit = commands("graaljs") {
    description("Run isolated JavaScript tasks with CPU, timeout and memory limits")
    val invalid = response.json[ErrorBody](400) { description("Invalid task, limits or JavaScript source") }
    val missing = response.json[ErrorBody](404) { description("Task not found") }
    val full = response.json[ErrorBody](429) { description("Task queue is full") }
    val stopping = response.json[ErrorBody](503) { description("Runner is stopping") }
    recover {
      case error: InvalidGraalTask => invalid(ErrorBody("invalid_graaljs_task", error.getMessage))
      case error: MissingGraalTask => missing(ErrorBody("not_found", error.getMessage))
      case error: GraalQueueFull => full(ErrorBody("queue_full", error.getMessage))
      case error: GraalStopping => stopping(ErrorBody("stopping", error.getMessage))
    }

    command[GraalTask]("submit") {
      description("Submit JavaScript source from the UTF-8 text body; queued until a 4 MiB slot from the 64 MiB budget is available")
      val source = body.text("JavaScript source code, sent directly as text/plain") { minLength(1) }
      val cpu = param[String]("cpu", "Cumulative guest CPU time limit, e.g. 500ms or 2s") { minLength(1) }
      val timeout = param[String]("timeout", "Wall-clock execution timeout, excluding queue time, e.g. 30s") { minLength(1) }
      val sessionId = param[Option[String]]("sessionId", "Optional caller session ID; implicitly supplied to SDK commands declaring sessionId")
      val cwd = param[Option[String]]("cwd", "Optional caller working directory; implicitly supplied to SDK commands declaring cwd")
      handle {
        val ctx = List("sessionId" -> sessionId.value, "cwd" -> cwd.value).collect {
          case (k, Some(v)) if v.trim.nonEmpty => k -> v.trim
        }.toMap
        IO(JavaScript.source(source.value)).flatMap(manager.submit(_, cpu.value, timeout.value, ctx))
      }
    }

    command[List[GraalTask]]("list") {
      description("List queued, running and cancelling JavaScript tasks in submission order")
      handle { manager.list }
    }

    command[GraalTask]("status") {
      description("Read task state, limits, timestamps, CPU time, total elapsed time and failure details")
      val id = param[String]("id", "Task ID") { minLength(1) }
      handle { manager.status(id.value) }
    }

    command[PlainText]("logs") {
      description("Read the current combined stdout/stderr log as plain text, including the final timing summary")
      val id = param[String]("id", "Task ID") { minLength(1) }
      handle { manager.logs(id.value) }
    }

    command[SSE[PlainText]]("logsFollow") {
      description("Replay the local log then stream new lines until the task finishes; disconnecting does not cancel the task")
      val id = param[String]("id", "Task ID") { minLength(1) }
      handle { manager.follow(id.value) }
    }

    command[GraalTask]("cancel") {
      description("Cancel a queued or running task; release its reservation only after Context cleanup")
      val id = param[String]("id", "Task ID") { minLength(1) }
      handle { manager.cancel(id.value) }
    }

    command[Unit]("notifyOnFinish") {
      description("Reserved completion-notification API; currently returns 501 Not Implemented")
      val id = param[String]("id", "Task ID") { minLength(1) }
      val unavailable = response.json[ErrorBody](501) { description("Notifications are not implemented") }
      handle { manager.status(id.value).flatMap(_ => unavailable.raise(ErrorBody("not_implemented", "Completion notifications are not implemented"))) }
    }
  }
