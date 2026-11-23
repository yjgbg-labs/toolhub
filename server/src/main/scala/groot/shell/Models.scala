package groot.shell

import framework.model.JsonModel

enum ShellKind derives JsonModel:
  case bash, zsh, powershell

case class ShellEnvironment(name: String, shell: ShellKind = ShellKind.bash, target: Option[String] = None, idle: String = "5m") derives JsonModel
case class ShellRequest(env: String, script: String, timeout: String = "30s", cwd: Option[String] = None) derives JsonModel
case class ShellResult(exitCode: Int, stdout: String, stderr: String) derives JsonModel
private[shell] case class ShellRegistry(version: Int, environments: Vector[ShellEnvironment]) derives JsonModel
private[shell] case class ShellFailure(code: String, message: String) extends RuntimeException(message)

final case class ShellConfig(
    @framework.validation.positive maxScriptBytes: Int = 64 * 1024,
    @framework.validation.positive maxOutputBytes: Int = 64 * 1024,
    @framework.validation.nonBlank sshExecutable: String = "/usr/bin/ssh"
) derives JsonModel, framework.validation.Validator
