package groot.jobs

import cats.effect.IO
import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object JobsRoutes:
  def register()(using CommandsScope): Unit = command[Shell]("jobs") {
    description("Compile a single jobs.yaml into a shell script for local execution")
    val cwd = param[String]("cwd", "Caller working directory, supplied automatically by the CLI") { minLength(1) }
    val job = param[String]("job", "Target job") { minLength(1) }
    val parallel = param[Int]("parallel", "Maximum concurrent jobs") { default(5); minimum(1) }
    val invalid = response.json[ErrorBody](400) { description("Invalid job configuration or target") }
    recover { case error: InvalidJobs => invalid(ErrorBody("invalid_jobs", error.getMessage)) }
    handle {
      IO.blocking(JobsConfig.read(cwd.value)).map { source =>
        Shell(ShellCompiler.compile(JobsConfig.plan(source, job.value), parallel.value))
      }
    }
  }
