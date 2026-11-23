package groot.shell

import framework.model.JsonModel
import framework.validation.{Contracts, Validator, contract}

enum ShellKind derives JsonModel, Validator:
  case bash, zsh, powershell

case class ShellEnvironment(
    @contract[String](Contracts.nonBlank) name: String,
    shell: ShellKind = ShellKind.bash,
    @contract[String](Contracts.nonBlank) target: Option[String] = None,
    @contract[String](Contracts.isDuration) idle: String = "5m"
) derives JsonModel, Validator

final case class ShellConfig(
    @contract[Int](_ > 0) maxScriptBytes: Int = 64 * 1024,
    @contract[Int](_ > 0) maxOutputBytes: Int = 64 * 1024,
    @contract[String](Contracts.nonBlank) sshExecutable: String = "/usr/bin/ssh",
    seedEnvironments: Vector[ShellEnvironment] = ShellService.defaults
) derives JsonModel, Validator
case class ShellRequest(env: String, script: String, timeout: String = "30s", cwd: Option[String] = None) derives JsonModel
case class ShellResult(exitCode: Int, stdout: String, stderr: String) derives JsonModel
private[shell] case class ShellRegistry(version: Int, environments: Vector[ShellEnvironment]) derives JsonModel
private[shell] case class ShellFailure(code: String, message: String) extends RuntimeException(message)
