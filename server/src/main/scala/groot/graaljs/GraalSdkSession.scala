package groot.graaljs

import cats.effect.IO
import cats.effect.std.Dispatcher
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CancellationException, LinkedBlockingQueue}
import org.graalvm.polyglot.{Context, Value}
import org.graalvm.polyglot.proxy.ProxyExecutable
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

/** Guest Values are only touched by the task's execution thread. IO callbacks only enqueue immutable replies. */
private[graaljs] final class GraalSdkSession(sdk: GraalCommandSdk, dispatcher: Dispatcher[IO]):
  private given ExecutionContext = ExecutionContext.parasitic
  private case class Pending(command: String, resolve: Value, reject: Value, cancel: () => Future[Unit])
  private sealed trait Event
  private case class Reply(id: Long, result: Either[Throwable, String]) extends Event
  private case object Wake extends Event
  private val replies = new LinkedBlockingQueue[Event]()
  private val pending = mutable.Map.empty[Long, Pending]
  private var sequence = 0L
  private var bindings: GraalSdkBindings = null

  def install(context: Context): Unit =
    bindings = new GraalSdkBindings(context, sdk.tree, submit)
    bindings.install()

  private def submit(id: String, input: String, resolve: Value, reject: Value): Unit =
    require(pending.size < GraalSdkSession.maxPending, "At most 64 SDK calls may be pending per task")
    require(input.getBytes(UTF_8).length <= GraalCommandSdk.maxBytes, "SDK input exceeds 1 MiB")
    val parsed: IO[Option[io.circe.Json]] = if input.isEmpty then IO.pure(None) else IO.defer(IO.fromEither(io.circe.parser.parse(input))).map(Some(_))
    val action = parsed.flatMap(sdk.call(id, _)).map { result =>
      val json = result.noSpaces
      require(json.getBytes(UTF_8).length <= GraalCommandSdk.maxBytes, "SDK response exceeds 1 MiB")
      json
    }
    sequence += 1
    val callId = sequence
    // Deliver directly from IO after command finalizers. The Dispatcher cancellation token
    // still waits for cleanup, but replies no longer travel through a result Future/onComplete.
    def enqueue(result: Either[Throwable, String]): IO[Unit] = IO { replies.offer(Reply(callId, result)); () }
    val cancel = dispatcher.unsafeRunCancelable(action.attempt.flatMap(enqueue)
      .onCancel(enqueue(Left(new CancellationException(s"SDK call cancelled: $id")))))
    pending(callId) = Pending(id, resolve, reject, cancel)

  /** Blocks without consuming guest CPU while command IO is in progress. */
  def next(): Unit = replies.take() match
    case Reply(id, result) => pending.remove(id).foreach(call => bindings.deliver(call.command, call.resolve, call.reject, result))
    case Wake => ()

  def wake(): Unit = { replies.offer(Wake); () }

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
