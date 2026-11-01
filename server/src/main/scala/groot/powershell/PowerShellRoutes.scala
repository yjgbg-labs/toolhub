package groot.powershell

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object PowerShellRoutes:
  def register(service: PowerShellService)(using CommandsScope): Unit = command[PowerShellResult]("powershell") {
    exposeOn(cli, graaljs, codemode)
    description("Run Windows PowerShell over reusable SSH; return exitCode/stdout/stderr; idle connections expire after 5 minutes by default")
    val script = body[String]("PowerShell script as a JSON string (up to 64 KiB UTF-8); each output stream is capped at 64 KiB")
    val timeout = param[String]("timeout", "Connection and execution timeout, e.g. 500ms or 30s") { default("30s"); minLength(1) }
    val invalid = response.json[ErrorBody](400) { description("Invalid script or timeout") }
    val large = response.json[ErrorBody](413) { description("Script or output exceeds its limit") }
    val timedOut = response.json[ErrorBody](504) { description("PowerShell execution exceeded its deadline") }
    val failed = response.json[ErrorBody](502) { description("Could not establish or operate the SSH connection") }
    recover {
      case error: PowerShellFailure if error.code == "powershell_limit" => large(ErrorBody(error.code, error.getMessage))
      case error: PowerShellFailure if error.code == "powershell_timeout" => timedOut(ErrorBody(error.code, error.getMessage))
      case error: PowerShellFailure if error.code == "invalid_powershell" => invalid(ErrorBody(error.code, error.getMessage))
      case error: PowerShellFailure => failed(ErrorBody(error.code, error.getMessage))
      case error: java.io.IOException => failed(ErrorBody("powershell_io", error.getMessage))
    }
    handle { service(PowerShellRequest(script.value, timeout.value)) }
  }
