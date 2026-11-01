package groot.zshonmac

import framework.model.JsonModel

case class ZshOnMacResult(exitCode: Int, stdout: String, stderr: String) derives JsonModel
private[zshonmac] case class ZshOnMacFailure(code: String, message: String) extends RuntimeException(message)
