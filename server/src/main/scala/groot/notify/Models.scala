package groot.notify

import framework.model.JsonModel

case class NotifyRequest(targetSessionId: String, message: String) derives JsonModel
case class Notification(id: String, targetSessionId: String, message: String, createdAt: String) derives JsonModel
case class NotifyReceipt(id: String, subscribers: Int) derives JsonModel
enum NotifyEventKind derives JsonModel:
  case ready, message, heartbeat
case class NotifyEvent(kind: NotifyEventKind, notification: Option[Notification] = None) derives JsonModel

case class NotifyWhenRequest(
    targetSessionId: String,
    code: String,
    every: Option[String] = None,
    delay: Option[String] = None,
    once: Option[Boolean] = None,
    timeout: Option[String] = None,
    cpu: Option[String] = None,
    scriptTimeout: Option[String] = None
) derives JsonModel

case class NotifyAtRequest(
    targetSessionId: String,
    at: String,
    message: String
) derives JsonModel

case class NotifyTrigger(
    id: String,
    targetSessionId: String,
    code: String,
    every: String,
    delay: String,
    once: Boolean,
    cpu: String,
    scriptTimeout: String,
    createdAt: String,
    expiresAt: Option[String] = None,
    nextCheckAt: Option[String] = None,
    lastCheckedAt: Option[String] = None,
    lastTriggeredAt: Option[String] = None,
    lastTaskId: Option[String] = None,
    lastError: Option[String] = None,
    checkCount: Int = 0,
    triggerCount: Int = 0,
    status: String = "active"
) derives JsonModel

private[notify] case class NotifyFailure(code: String, message: String) extends RuntimeException(message)
