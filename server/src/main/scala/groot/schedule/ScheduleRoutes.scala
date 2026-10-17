package groot.schedule

import sprout.{ErrorBody, CommandsScope}
import sprout.rpcDsl.*

object ScheduleRoutes:
  def register(scheduler: Scheduler)(using CommandsScope): Unit = commands("schedule") {
    description("Manage scheduled jobs")

    val invalid = response.json[ErrorBody](400) { description("Invalid schedule configuration") }
    val missing = response.json[ErrorBody](404) { description("Job not found") }
    middleware { next =>
      next.recover {
        case error: InvalidSchedule => invalid(ErrorBody("invalid_schedule", error.getMessage))
        case error: MissingJob => missing(ErrorBody("not_found", error.getMessage))
      }
    }

    command[List[ScheduleEntry]]("list") {
      summary("List scheduled jobs, their runtime state and queue status")
      handle { scheduler.list }
    }

    command[ScheduleJob]("new") {
      summary("Create or replace a scheduled command and clear its runtime state")
      val name = param[String]("name", "Job name") { minLength(1) }
      val input = body[ScheduleJob]("Scheduled command and interval")
      handle { scheduler.put(name.value, input.value) }
    }

    command[Unit]("delete") {
      summary("Delete a job; existing execution may finish and historical logs are retained")
      val name = param[String]("name", "Job name") { minLength(1) }
      handle { scheduler.delete(name.value) }
    }

    command[JobState]("reset") {
      summary("Clear runtime state so an enabled job becomes due without overlapping an active run")
      val name = param[String]("name", "Job name") { minLength(1) }
      handle { scheduler.reset(name.value) }
    }

    command[String]("log") {
      summary("Read the last lines of a scheduled job's combined stdout/stderr log")
      val name = param[String]("name", "Job name") { minLength(1) }
      val lines = param[Int]("lines", "Number of log lines") { default(100); minimum(1); maximum(10000) }
      handle { scheduler.log(name.value, lines.value) }
    }

    commands("config") {
      description("Manage scheduler configuration")

      command[ScheduleConfig]("read") {
        summary("Read the scheduler's static configuration")
        handle { scheduler.config }
      }

      command[ScheduleConfig]("write") {
        summary("Validate and atomically replace the full scheduler configuration; changes apply on the next tick")
        val input = body[ScheduleConfig]("Complete scheduler configuration")
        handle { scheduler.writeConfig(input.value) }
      }
    }
  }
