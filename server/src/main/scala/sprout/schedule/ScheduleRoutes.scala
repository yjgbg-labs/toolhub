package sprout.schedule

import cats.effect.IO
import sprout.{EndpointScope, ErrorBody, RoutesScope}
import sprout.dsl.*

object ScheduleRoutes:
  private def errors(using EndpointScope): IO[EndpointResult] => IO[EndpointResult] =
    val invalid = response.json[ErrorBody](400) { description("Invalid schedule configuration") }
    val missing = response.json[ErrorBody](404) { description("Job not found") }
    action => action.recover {
      case error: InvalidSchedule => invalid(ErrorBody("invalid_schedule", error.getMessage))
      case error: MissingJob => missing(ErrorBody("not_found", error.getMessage))
    }

  def register(scheduler: Scheduler)(using RoutesScope): Unit = group("schedule") {
    description("Manage scheduled jobs")

    operation("list") {
      summary("List scheduled jobs, their runtime state and queue status")
      val ok = response.json[List[ScheduleEntry]](200) {}
      val respond = errors
      handle { respond(scheduler.list.map(ok(_))) }
    }

    operation("new") {
      summary("Create or replace a scheduled command and clear its runtime state")
      val name = path[String]("name") { minLength(1) }
      val input = body.json[ScheduleJob]
      val created = response.json[ScheduleJob](201) {}
      val respond = errors
      handle { respond(scheduler.put(name.value, input.value).map(created(_))) }
    }

    operation("delete") {
      summary("Delete a job; existing execution may finish and historical logs are retained")
      val name = path[String]("name") { minLength(1) }
      val done = response.empty(204) {}
      val respond = errors
      handle { respond(scheduler.delete(name.value).map(done(_))) }
    }

    operation("reset") {
      summary("Clear runtime state so an enabled job becomes due without overlapping an active run")
      val name = path[String]("name") { minLength(1) }
      val ok = response.json[JobState](200) {}
      val respond = errors
      handle { respond(scheduler.reset(name.value).map(ok(_))) }
    }

    operation("log") {
      summary("Read the last lines of a scheduled job's combined stdout/stderr log")
      val name = path[String]("name") { minLength(1) }
      val lines = query[Int]("lines") { default(100); minimum(1); maximum(10000) }
      val ok = response.text(200) {}
      val respond = errors
      handle { respond(scheduler.log(name.value, lines.value).map(ok(_))) }
    }

    group("config") {
      description("Manage scheduler configuration")

      operation("read") {
        summary("Read the scheduler's static configuration")
        val ok = response.json[ScheduleConfig](200) {}
        val respond = errors
        handle { respond(scheduler.config.map(ok(_))) }
      }

      operation("write") {
        summary("Validate and atomically replace the full scheduler configuration; changes apply on the next tick")
        val input = body.json[ScheduleConfig]
        val ok = response.json[ScheduleConfig](200) {}
        val respond = errors
        handle { respond(scheduler.writeConfig(input.value).map(ok(_))) }
      }
    }
  }
