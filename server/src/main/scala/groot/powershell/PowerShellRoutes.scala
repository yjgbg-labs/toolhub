package groot.powershell

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object PowerShellRoutes:
  def register(service: PowerShellService)(using CommandsScope): Unit = command[PowerShellResult]("powershell") {
    exposeOn(cli, graaljs, codemode)
    description("Run Windows PowerShell over reusable SSH; return exitCode/stdout/stderr; idle connections expire after 5 minutes by default")
    val input = body[PowerShellRequest]("PowerShell script (up to 64 KiB UTF-8) and optional timeout (default 30s); each output stream is capped at 64 KiB")
    val invalid = response.json[ErrorBody](400) { description("Invalid script or timeout") }
    val large = response.json[ErrorBody](413) { description("Script or output exceeds its limit") }
    val timeout = response.json[ErrorBody](504) { description("PowerShell execution exceeded its deadline") }
    val failed = response.json[ErrorBody](502) { description("Could not establish or operate the SSH connection") }
    recover {
      case error: PowerShellFailure if error.code == "powershell_limit" => large(ErrorBody(error.code, error.getMessage))
      case error: PowerShellFailure if error.code == "powershell_timeout" => timeout(ErrorBody(error.code, error.getMessage))
      case error: PowerShellFailure if error.code == "invalid_powershell" => invalid(ErrorBody(error.code, error.getMessage))
      case error: PowerShellFailure => failed(ErrorBody(error.code, error.getMessage))
      case error: java.io.IOException => failed(ErrorBody("powershell_io", error.getMessage))
    }
    handle { service(input.value) }
  }
