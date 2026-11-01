package groot.bash

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object BashRoutes:
  def register(service: BashService)(using CommandsScope): Unit = command[BashResult]("bash") {
    exposeOn(graaljs)
    description("Execute Bash on the server; return exitCode, stdout and stderr; task cancellation stops the process group")
    val request = body[BashRequest]("Bash script (up to 64 KiB), optional absolute cwd and timeout (default 30s); output is capped at 64 KiB per stream")
    val invalid = response.json[ErrorBody](400) { description("Invalid script, cwd or timeout") }
    val large = response.json[ErrorBody](413) { description("Script or output exceeds its size limit") }
    val timeout = response.json[ErrorBody](504) { description("Bash command exceeded its deadline") }
    val failed = response.json[ErrorBody](500) { description("Could not start or communicate with Bash") }
    recover {
      case error: BashFailure if error.code == "bash_timeout" => timeout(ErrorBody(error.code, error.getMessage))
      case error: BashFailure if error.code == "bash_limit" => large(ErrorBody(error.code, error.getMessage))
      case error: BashFailure => invalid(ErrorBody(error.code, error.getMessage))
      case error: java.io.IOException => failed(ErrorBody("bash_io", error.getMessage))
    }
    handle { service(request.value) }
  }
