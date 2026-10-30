package groot.jobs

import cats.effect.IO
import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object JobsRoutes:
  def register()(using CommandsScope): Unit = command[Shell]("jobs") {
    description("Compile a single jobs.yaml into a shell script for local execution")
    val job = param[String]("job", "Target job") { minLength(1) }
    val parallel = param[Int]("parallel", "Maximum concurrent jobs") { default(5); minimum(1) }
    val yaml = body[String]("Contents of one jobs.yaml; defaults to jobs.yaml in the CLI working directory") {
      defaultFile("jobs.yaml")
    }
    val invalid = response.json[ErrorBody](400) { description("Invalid job configuration or target") }
    recover { case error: InvalidJobs => invalid(ErrorBody("invalid_jobs", error.getMessage)) }
    handle {
      IO.blocking(Shell(ShellCompiler.compile(JobsConfig.plan(yaml.value, job.value), parallel.value)))
    }
  }
