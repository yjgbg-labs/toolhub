package sprout.schedule

import cats.effect.IO
import sprout.{EndpointScope, ErrorBody, RoutesScope}
import sprout.dsl.*

object ScheduleRoutes:
  private def errors[A](using EndpointScope[A]): IO[A] => IO[A | EndpointResult] =
    val invalid = response.json[ErrorBody](400) { description("Invalid schedule configuration") }
    val missing = response.json[ErrorBody](404) { description("Job not found") }
    action => action.recover[A | EndpointResult] {
      case error: InvalidSchedule => invalid(ErrorBody("invalid_schedule", error.getMessage))
      case error: MissingJob => missing(ErrorBody("not_found", error.getMessage))
    }

  def register(scheduler: Scheduler)(using RoutesScope): Unit = group("schedule") {
    description("Manage scheduled jobs")

    operation[List[ScheduleEntry]]("list") {
      summary("List scheduled jobs, their runtime state and queue status")
      val respond = errors
      handle { respond(scheduler.list) }
    }

    operation[ScheduleJob]("new") {
      summary("Create or replace a scheduled command and clear its runtime state")
      val name = path[String]("name") { minLength(1) }
      val input = body.json[ScheduleJob]
      val respond = errors
      handle { respond(scheduler.put(name.value, input.value)) }
    }

    operation[Unit]("delete") {
      summary("Delete a job; existing execution may finish and historical logs are retained")
      val name = path[String]("name") { minLength(1) }
      val respond = errors
      handle { respond(scheduler.delete(name.value)) }
    }

    operation[JobState]("reset") {
      summary("Clear runtime state so an enabled job becomes due without overlapping an active run")
      val name = path[String]("name") { minLength(1) }
      val respond = errors
      handle { respond(scheduler.reset(name.value)) }
    }

    operation[String]("log") {
      summary("Read the last lines of a scheduled job's combined stdout/stderr log")
      val name = path[String]("name") { minLength(1) }
      val lines = query[Int]("lines") { default(100); minimum(1); maximum(10000) }
      val respond = errors
      handle { respond(scheduler.log(name.value, lines.value)) }
    }

    group("config") {
      description("Manage scheduler configuration")

      operation[ScheduleConfig]("read") {
        summary("Read the scheduler's static configuration")
        val respond = errors
        handle { respond(scheduler.config) }
      }

      operation[ScheduleConfig]("write") {
        summary("Validate and atomically replace the full scheduler configuration; changes apply on the next tick")
        val input = body.json[ScheduleConfig]
        val respond = errors
        handle { respond(scheduler.writeConfig(input.value)) }
      }
    }
  }
