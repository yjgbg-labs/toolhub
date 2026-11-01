package groot.state

import framework.model.JsonModel

case class StateWrite(key: String, value: String) derives JsonModel
private[state] case class InvalidState(message: String) extends RuntimeException(message)
private[state] case class StateLimit(message: String) extends RuntimeException(message)
