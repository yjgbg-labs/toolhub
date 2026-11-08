package groot.shell

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object ShellRoutes:
  def register(service: ShellService)(using CommandsScope): Unit = commands("shell") {
    description("Run scripts in named local Bash or SSH Bash, macOS Zsh and Windows PowerShell environments")
    exposeOn(cli, codemode, graaljs)
    val invalid = response.json[ErrorBody](400) { description("Invalid environment, script, cwd or timeout") }
    val missing = response.json[ErrorBody](404) { description("Environment not found") }
    val conflict = response.json[ErrorBody](409) { description("Environment already exists") }
    val large = response.json[ErrorBody](413) { description("Script or output exceeds 64 KiB") }
    val failed = response.json[ErrorBody](500) { description("Could not access the registry or start a local process") }
    val remote = response.json[ErrorBody](502) { description("Could not establish or operate the SSH connection") }
    val timedOut = response.json[ErrorBody](504) { description("Shell execution exceeded its deadline") }
    recover {
      case error: ShellFailure =>
        val result = error.code match
          case "shell_not_found" => missing
          case "shell_exists" => conflict
          case "shell_limit" => large
          case "shell_timeout" => timedOut
          case "shell_ssh" => remote
          case _ => invalid
        result(ErrorBody(error.code, error.getMessage))
      case error: java.io.IOException => failed(ErrorBody("shell_io", error.getMessage))
    }
    command[ShellResult]("execute") {
      description("Execute a script in a registered environment; return exitCode/stdout/stderr; cancellation cleans up the invocation")
      val request = body[ShellRequest]("Environment name and script (up to 64 KiB UTF-8); timeout defaults to 30s; cwd is supported for local Bash only")
      handle { service.execute(request.value) }
    }
    commands("env") {
      description("Persist named shell environments; SSH targets use the service user's SSH config, keys and known_hosts")
      command[Vector[ShellEnvironment]]("list") {
        description("List registered shell environments, sorted by name")
        handle { service.list }
      }
      command[ShellEnvironment]("add") {
        description("Register a new environment; omit target for local Bash, or set an SSH target for Bash, macOS Zsh or Windows PowerShell")
        val environment = body[ShellEnvironment]("Unique name, shell type, optional SSH target and idle connection timeout (default 5m)")
        handle { service.add(environment.value) }
      }
      command[Unit]("delete") {
        description("Remove an environment; existing executions finish before its SSH resources are released")
        val name = param[String]("name", "Environment name") { minLength(1); maxLength(128) }
        handle { service.delete(name.value) }
      }
    }
  }
