package groot.bashonr6s

import framework.model.JsonModel

case class BashOnR6sResult(exitCode: Int, stdout: String, stderr: String) derives JsonModel
private[bashonr6s] case class BashOnR6sFailure(code: String, message: String) extends RuntimeException(message)
