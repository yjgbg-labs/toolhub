package groot.notify

import framework.model.JsonModel

case class NotifyRequest(targetSessionId: String, message: String) derives JsonModel
case class Notification(id: String, targetSessionId: String, message: String, createdAt: String) derives JsonModel
case class NotifyReceipt(id: String, subscribers: Int) derives JsonModel
enum NotifyEventKind derives JsonModel:
  case ready, message, heartbeat
case class NotifyEvent(kind: NotifyEventKind, notification: Option[Notification] = None) derives JsonModel
private[notify] case class NotifyFailure(code: String, message: String) extends RuntimeException(message)
