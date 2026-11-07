package groot.bashonr6s

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object BashOnR6sRoutes:
  def register(service: BashOnR6sService)(using CommandsScope): Unit = command[BashOnR6sResult]("bashOnR6s") {
    exposeOn(cli, graaljs, codemode)
    description("Run Bash on the R6S router over reusable SSH; return exitCode/stdout/stderr; idle connections expire after 5 minutes by default")
    val script = body[String]("Bash script as a JSON string (up to 64 KiB UTF-8); each output stream is capped at 64 KiB")
    val timeout = param[String]("timeout", "Connection and execution timeout, e.g. 500ms or 30s") { default("30s"); minLength(1) }
    val invalid = response.json[ErrorBody](400) { description("Invalid script or timeout") }
    val large = response.json[ErrorBody](413) { description("Script or output exceeds its limit") }
    val timedOut = response.json[ErrorBody](504) { description("Bash execution exceeded its deadline") }
    val failed = response.json[ErrorBody](502) { description("Could not establish or operate the SSH connection") }
    recover {
      case error: BashOnR6sFailure if error.code == "bash_on_r6s_limit" => large(ErrorBody(error.code, error.getMessage))
      case error: BashOnR6sFailure if error.code == "bash_on_r6s_timeout" => timedOut(ErrorBody(error.code, error.getMessage))
      case error: BashOnR6sFailure if error.code == "invalid_bash_on_r6s" => invalid(ErrorBody(error.code, error.getMessage))
      case error: BashOnR6sFailure => failed(ErrorBody(error.code, error.getMessage))
      case error: java.io.IOException => failed(ErrorBody("bash_on_r6s_io", error.getMessage))
    }
    handle { service(script.value, timeout.value) }
  }
