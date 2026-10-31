package groot.graaljs

import cats.effect.IO
import cats.effect.std.Dispatcher
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.LinkedBlockingQueue
import org.graalvm.polyglot.{Context, Value}
import org.graalvm.polyglot.proxy.ProxyExecutable
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

/** Guest Values are only touched by the task's execution thread. IO callbacks only enqueue immutable replies. */
private[graaljs] final class GraalSdkSession(sdk: GraalCommandSdk, dispatcher: Dispatcher[IO]):
  private given ExecutionContext = ExecutionContext.parasitic
  private case class Pending(command: String, resolve: Value, reject: Value, cancel: () => Future[Unit])
  private case class Reply(id: Long, result: Either[Throwable, String])
  private val replies = new LinkedBlockingQueue[Option[Reply]]()
  private val pending = mutable.Map.empty[Long, Pending]
  private var sequence = 0L
  private var bindings: GraalSdkBindings = null

  def install(context: Context): Unit =
    bindings = new GraalSdkBindings(context, sdk.commands, submit)
    bindings.install()

  private def submit(id: String, input: String, resolve: Value, reject: Value): Unit =
    require(pending.size < GraalSdkSession.maxPending, "At most 64 SDK calls may be pending per task")
    require(input.getBytes(UTF_8).length <= GraalCommandSdk.maxBytes, "SDK input exceeds 1 MiB")
    val parsed: IO[Option[io.circe.Json]] = if input.isEmpty then IO.pure(None) else IO.fromEither(io.circe.parser.parse(input)).map(Some(_))
    val action = parsed.flatMap(sdk.call(id, _)).map { result =>
      val json = result.noSpaces
      require(json.getBytes(UTF_8).length <= GraalCommandSdk.maxBytes, "SDK response exceeds 1 MiB")
      json
    }
    sequence += 1
    val callId = sequence
    val (result, cancel) = dispatcher.unsafeToFutureCancelable(action)
    pending(callId) = Pending(id, resolve, reject, cancel)
    result.onComplete(value => replies.offer(Some(Reply(callId, value.toEither))))

  /** Blocks without consuming guest CPU while command IO is in progress. */
  def next(): Unit = replies.take().foreach { reply =>
    pending.remove(reply.id).foreach { call =>
      bindings.deliver(call.command, call.resolve, call.reject, reply.result)
    }
  }

  def wake(): Unit = { replies.offer(None); () }

  /** Finish command finalizers before the task relinquishes its memory reservation. */
  def close(): Unit =
    val cancellations = pending.values.toVector.map(_.cancel())
    pending.clear()
    Await.result(Future.sequence(cancellations), Duration.Inf)
    replies.clear()

private[graaljs] object GraalSdkSession:
  val maxPending = 64
  def callback(run: Seq[Value] => AnyRef): ProxyExecutable = new ProxyExecutable:
    def execute(arguments: Value*): AnyRef = run(arguments)
