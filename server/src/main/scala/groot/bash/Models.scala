package groot.bash

import framework.model.JsonModel

case class BashRequest(script: String, cwd: Option[String] = None, timeout: String = "30s") derives JsonModel
case class BashResult(exitCode: Int, stdout: String, stderr: String) derives JsonModel
case class BashFailure(code: String, message: String) extends RuntimeException(message)
