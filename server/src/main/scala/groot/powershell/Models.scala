package groot.powershell

import framework.model.JsonModel

case class PowerShellResult(exitCode: Int, stdout: String, stderr: String) derives JsonModel
private[groot] case class PowerShellFailure(code: String, message: String) extends RuntimeException(message)
