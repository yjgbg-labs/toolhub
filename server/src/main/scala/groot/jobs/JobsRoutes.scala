package groot.jobs

import cats.effect.IO
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import scala.io.Source
import scala.util.Using

object JobsRoutes:
  private lazy val runner = Using.resource(Source.fromResource("groot/jobs.js", getClass.getClassLoader))(_.mkString)

  private def quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

  def register()(using CommandsScope): Unit = command[Shell]("jobs") {
    description("Run a local jobs.yaml dependency graph with Bun on the CLI machine")
    val job = param[String]("job", "Target job, including an optional @namespace") { minLength(1) }
    val parallel = param[Int]("parallel", "Maximum concurrent jobs") { default(5); minimum(1) }
    handle {
      IO(Shell(s"exec bun -e ${quote(runner)} -- ${quote(job.value)} ${parallel.value}"))
    }
  }
