package groot.notify

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.{Mutex, Queue}
import cats.syntax.all.*
import fs2.Stream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import scala.concurrent.duration.*

/** Live-only fanout. A bounded queue belongs to each connected subscriber, never to an offline session. */
final class NotifyService private (mutex: Mutex[IO], stopped: Deferred[IO, Unit], capacity: Int, heartbeat: FiniteDuration):
  private type Subscribers = Map[String, Queue[IO, Notification]]
  private var sessions = Map.empty[String, Subscribers]
  private var closed = false

  private def serial[A](action: => IO[A]): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.defer(action)))
  private def ensureOpen(): Unit =
    if closed then throw NotifyFailure("notify_closed", "Notification service is shutting down")

  def send(request: NotifyRequest): IO[NotifyReceipt] = serial {
    for
      _ <- IO {
        ensureOpen()
        NotifyService.validateSession(request.targetSessionId)
        if request.message.trim.isEmpty || request.display.trim.isEmpty then
          throw NotifyFailure("invalid_notify", "message and display must be nonblank")
        if request.message.getBytes(UTF_8).length > 65536 || request.display.getBytes(UTF_8).length > 4096 then
          throw NotifyFailure("notify_limit", "message is limited to 64 KiB and display to 4 KiB UTF-8")
      }
      subscribers <- IO {
        val values = sessions.getOrElse(request.targetSessionId, Map.empty).values.toVector
        if values.isEmpty then throw NotifyFailure("notify_offline", s"No online subscriber for ${request.targetSessionId}")
        values
      }
      sizes <- subscribers.traverse(_.size)
      // All writers hold the mutex; readers can only free slots. Reject before publishing to anyone.
      _ <- IO.raiseWhen(sizes.exists(_ >= capacity))(NotifyFailure("notify_busy", "A subscriber is not keeping up; retry later"))
      now <- IO.realTimeInstant
      message <- IO(Notification(UUID.randomUUID().toString, request.targetSessionId, request.message, request.display, now.toString))
      _ <- subscribers.traverse_(_.offer(message))
    yield NotifyReceipt(message.id, subscribers.size)
  }

  def subscribe(sessionId: String): IO[Stream[IO, NotifyEvent]] = IO {
    NotifyService.validateSession(sessionId)
    val registration = Resource.make(serial {
      for
        _ <- IO(ensureOpen())
        queue <- Queue.bounded[IO, Notification](capacity)
        id <- IO(UUID.randomUUID().toString)
        _ <- IO { sessions += sessionId -> (sessions.getOrElse(sessionId, Map.empty) + (id -> queue)) }
      yield (id, queue)
    }) { (id, _) => serial(IO {
      val remaining = sessions.getOrElse(sessionId, Map.empty) - id
      if remaining.isEmpty then sessions -= sessionId else sessions += sessionId -> remaining
    }) }
    Stream.resource(registration).flatMap { (_, queue) =>
      Stream.emit(NotifyEvent(NotifyEventKind.ready)) ++
        Stream.repeatEval(queue.take).map(value => NotifyEvent(NotifyEventKind.message, Some(value)))
          .merge(Stream.awakeEvery[IO](heartbeat).as(NotifyEvent(NotifyEventKind.heartbeat)))
    }.interruptWhen(stopped.get.attempt)
  }

  private def close: IO[Unit] = serial(IO { closed = true; sessions = Map.empty } *> stopped.complete(()).void)

object NotifyService:
  private def validateSession(value: String): Unit =
    if !value.matches("ses[A-Za-z0-9_-]{1,125}") then
      throw NotifyFailure("invalid_notify", "Session ID must start with ses and contain at most 128 letters, digits, underscores or hyphens")

  def resource(capacity: Int = 64, heartbeat: FiniteDuration = 15.seconds): Resource[IO, NotifyService] = for
    _ <- Resource.eval(IO(require(capacity > 0 && heartbeat > Duration.Zero)))
    mutex <- Resource.eval(Mutex[IO])
    stopped <- Resource.eval(Deferred[IO, Unit])
    service <- Resource.make(IO(new NotifyService(mutex, stopped, capacity, heartbeat)))(_.close)
  yield service
