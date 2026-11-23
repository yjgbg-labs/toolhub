package groot.notify

import framework.model.JsonModel
import framework.validation.{Contracts, Validator, contract}
import groot.graaljs.TaskError
import groot.schedule.{JobState, RetryPolicy}

final case class NotifyConfig(
    @contract[String](Contracts.isDuration) heartbeat: String = "15s",
    @contract[Int](_ > 0) subscriberCapacity: Int = 64,
    @contract[Int](_ > 0) maxActiveTriggers: Int = 256,
    @contract[String](Contracts.isDuration) defaultEvery: String = "15s",
    @contract[String](Contracts.isDuration) defaultCpu: String = "2s",
    @contract[String](Contracts.isDuration) defaultScriptTimeout: String = "30s",
    deliveryRetry: RetryPolicy = NotifyTriggerManager.defaultDeliveryRetry
) derives JsonModel, Validator

case class NotifyRequest(targetSessionId: String, message: String) derives JsonModel
case class Notification(id: String, targetSessionId: String, message: String, createdAt: String) derives JsonModel
case class NotifyReceipt(id: String, subscribers: Int) derives JsonModel
enum NotifyEventKind derives JsonModel:
  case ready, message, heartbeat
case class NotifyEvent(kind: NotifyEventKind, notification: Option[Notification] = None) derives JsonModel

case class NotifyWhenRequest(
    code: String,
    every: Option[String] = None,
    delay: Option[String] = None,
    once: Option[Boolean] = None,
    timeout: Option[String] = None,
    cpu: Option[String] = None,
    scriptTimeout: Option[String] = None
) derives JsonModel

case class NotifyAtRequest(
    at: String,
    message: String
) derives JsonModel

case class NotifyTrigger(
    id: String,
    sessionId: String,
    cwd: Option[String] = None,
    code: String,
    every: String,
    delay: String,
    once: Boolean,
    cpu: String,
    scriptTimeout: String,
    createdAt: String,
    expiresAt: Option[String] = None,
    nextRunAt: Option[String] = None,
    lastRunAt: Option[String] = None,
    lastFinishedAt: Option[String] = None,
    lastTriggeredAt: Option[String] = None,
    lastTaskId: Option[String] = None,
    lastError: Option[TaskError] = None,
    attempt: Int = 0,
    checkCount: Int = 0,
    triggerCount: Int = 0,
    status: String = "active"
) derives JsonModel

private[notify] case class StoredTrigger(
    id: String,
    sessionId: String,
    cwd: Option[String] = None,
    code: String,
    every: String,
    delay: String,
    once: Boolean,
    cpu: String,
    scriptTimeout: String,
    createdAt: String,
    firstRunAt: Option[String] = None,
    expiresAt: Option[String] = None,
    lastTriggeredAt: Option[String] = None,
    checkCount: Int = 0,
    triggerCount: Int = 0,
    status: String = "active",
    state: JobState = JobState()
) derives JsonModel

private[notify] case class NotifyFailure(code: String, message: String) extends RuntimeException(message)
