package groot.zshonmac

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object ZshOnMacRoutes:
  def register(service: ZshOnMacService)(using CommandsScope): Unit = command[ZshOnMacResult]("zshOnMac") {
    exposeOn(cli, graaljs, codemode)
    description("Run zsh on macOS over reusable SSH; return exitCode/stdout/stderr; idle connections expire after 5 minutes by default")
    val script = body[String]("Zsh script as a JSON string (up to 64 KiB UTF-8); each output stream is capped at 64 KiB")
    val timeout = param[String]("timeout", "Connection and execution timeout, e.g. 500ms or 30s") { default("30s"); minLength(1) }
    val invalid = response.json[ErrorBody](400) { description("Invalid script or timeout") }
    val large = response.json[ErrorBody](413) { description("Script or output exceeds its limit") }
    val timedOut = response.json[ErrorBody](504) { description("Zsh execution exceeded its deadline") }
    val failed = response.json[ErrorBody](502) { description("Could not establish or operate the SSH connection") }
    recover {
      case error: ZshOnMacFailure if error.code == "zsh_on_mac_limit" => large(ErrorBody(error.code, error.getMessage))
      case error: ZshOnMacFailure if error.code == "zsh_on_mac_timeout" => timedOut(ErrorBody(error.code, error.getMessage))
      case error: ZshOnMacFailure if error.code == "invalid_zsh_on_mac" => invalid(ErrorBody(error.code, error.getMessage))
      case error: ZshOnMacFailure => failed(ErrorBody(error.code, error.getMessage))
      case error: java.io.IOException => failed(ErrorBody("zsh_on_mac_io", error.getMessage))
    }
    handle { service(script.value, timeout.value) }
  }
