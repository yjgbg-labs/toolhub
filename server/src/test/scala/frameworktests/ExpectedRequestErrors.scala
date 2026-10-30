package frameworktests

import cats.effect.{IO, Resource}
import java.util.concurrent.{ConcurrentLinkedQueue, Semaphore}
import munit.Assertions
import scala.jdk.CollectionConverters.*
import scribe.{Level, Logger, LogRecord, Priority}
import scribe.modify.LogModifier
import scribe.throwable.TraceLoggableMessage

private[frameworktests] object ExpectedRequestErrors extends Assertions:
  private val loggerName = "framework.runtime.Application"
  // Scribe configuration is global; serialize the negative-test capture scopes.
  private val lock = new Semaphore(1)

  def apply[A](expected: String*)(action: IO[A]): IO[A] =
    Resource.make(IO.blocking(lock.acquire()))(_ => IO(lock.release())).use { _ =>
      val captured = new ConcurrentLinkedQueue[String]
      case class Capture(id: String = "expected-request-errors") extends LogModifier:
        val priority = Priority.Normal
        def withId(value: String): LogModifier = copy(id = value)
        def apply(record: LogRecord): Option[LogRecord] =
          val message = record.messages.collectFirst {
            case TraceLoggableMessage(error: IllegalArgumentException) if expected.contains(error.getMessage) => error.getMessage
          }
          if record.level == Level.Error && record.className == loggerName &&
              record.messages.headOption.exists(_.value == "Request failed") && message.nonEmpty then
            captured.add(message.get)
            None
          else Some(record)

      val capture = Capture()
      Resource.make(IO(Logger(loggerName).withModifier(capture).replace())) { _ =>
        IO(Logger(loggerName).withoutModifier(capture).replace()).void
      }.use { _ =>
        action.map { result =>
          assertEquals(captured.iterator().asScala.toList.sorted, expected.toList.sorted)
          result
        }
      }
    }
