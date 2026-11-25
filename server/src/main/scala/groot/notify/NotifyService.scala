package groot.notify

import cats.effect.{IO, Ref, Resource}
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration
import java.util.UUID

/** Server-side session notification router backed by PartialFunction deliverers. */
final class NotifyService private (
    closedRef: Ref[IO, Boolean],
    val deliverer: NotifyDeliverer
):
  def isSupportedSession(sessionId: String): Boolean =
    deliverer.isDefinedAt(Notification("00000000-0000-0000-0000-000000000000", sessionId, "probe", "1970-01-01T00:00:00Z"))

  def validateTargetSession(sessionId: String): Unit =
    NotifyService.validateSession(sessionId)
    if !isSupportedSession(sessionId) then
      throw NotifyFailure(
        "invalid_notify",
        s"Unsupported targetSessionId '$sessionId': expected OpenCode session (ses...) or Antigravity conversation UUID"
      )

  def send(request: NotifyRequest): IO[NotifyReceipt] =
    for
      closed <- closedRef.get
      _ <- IO {
        if closed then throw NotifyFailure("notify_closed", "Notification service is shutting down")
        validateTargetSession(request.targetSessionId)
        if request.message.trim.isEmpty then
          throw NotifyFailure("invalid_notify", "message must be nonblank")
        if request.message.getBytes(UTF_8).length > NotifyService.maxMessageBytes then
          throw NotifyFailure("notify_limit", "message is limited to 64 KiB UTF-8")
      }
      now <- IO.realTimeInstant
      notification = Notification(UUID.randomUUID().toString, request.targetSessionId, request.message, now.toString)
      target <- deliverer.lift(notification) match
        case Some(effect) => effect
        case None =>
          IO.raiseError(
            NotifyFailure(
              "invalid_notify",
              s"Unsupported targetSessionId '${request.targetSessionId}': expected OpenCode session (ses...) or Antigravity conversation UUID"
            )
          )
    yield NotifyReceipt(notification.id, target)

  private def close: IO[Unit] = closedRef.set(true)

object NotifyService:
  val maxMessageBytes: Int = 65536

  private[notify] def validateSession(value: String): Unit =
    if !value.matches("[A-Za-z0-9_.:-]{1,128}") then
      throw NotifyFailure("invalid_notify", "Session ID must be 1-128 characters of letters, digits, underscores, hyphens, dots or colons")

  def withDeliverer(deliverer: NotifyDeliverer): Resource[IO, NotifyService] =
    for
      closedRef <- Resource.eval(Ref.of[IO, Boolean](false))
      service <- Resource.make(IO(new NotifyService(closedRef, deliverer)))(_.close)
    yield service

  def resource(
      config: IO[NotifyConfig] = IO.pure(NotifyConfig()),
      isProcessAlive: Long => Boolean = NotifyDeliverers.defaultIsProcessAlive
  ): Resource[IO, NotifyService] =
    for
      httpClient <- Resource.fromAutoCloseable(
        IO(
          HttpClient.newBuilder()
            .connectTimeout(JDuration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        )
      )
      service <- withDeliverer(NotifyDeliverers.default(httpClient, config, isProcessAlive))
    yield service
