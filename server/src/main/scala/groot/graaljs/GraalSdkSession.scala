package groot.graaljs

import cats.effect.IO
import cats.effect.std.Dispatcher
import framework.runtime.CommandSdk
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.LinkedBlockingQueue
import org.graalvm.polyglot.{Context, Value}
import org.graalvm.polyglot.proxy.ProxyExecutable
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

/** Guest Values are only touched by the task's execution thread. IO callbacks only enqueue immutable replies. */
private[graaljs] final class GraalSdkSession(sdk: CommandSdk, dispatcher: Dispatcher[IO], fetch: GraalFetch):
  private given ExecutionContext = ExecutionContext.parasitic
  private case class Pending(cancel: () => Future[Unit])
  private case class Reply(id: Long, deliver: () => Unit)
  private val replies = new LinkedBlockingQueue[Option[Reply]]()
  private val pending = mutable.Map.empty[Long, Pending]
  private var sequence = 0L
  private var bindings: GraalSdkBindings = null
  private var fetchBindings: GraalFetchBindings = null

  def install(context: Context): Unit =
    bindings = new GraalSdkBindings(context, sdk.commands, submit)
    bindings.install()
    fetchBindings = new GraalFetchBindings(context, (input, resolve, reject) =>
      enqueue(fetch(input))(result => fetchBindings.deliver(resolve, reject, result)))
    fetchBindings.install()

  private def submit(id: String, input: String, resolve: Value, reject: Value): Unit =
    require(input.getBytes(UTF_8).length <= CommandSdk.maxBytes, "SDK input exceeds 1 MiB")
    val action = IO.fromEither(io.circe.parser.parse(input)).flatMap(sdk.call(id, _)).map { result =>
      val json = result.noSpaces
      require(json.getBytes(UTF_8).length <= CommandSdk.maxBytes, "SDK response exceeds 1 MiB")
      json
    }
    enqueue(action)(result => bindings.deliver(id, resolve, reject, result))

  private def enqueue[A](action: IO[A])(deliver: Either[Throwable, A] => Unit): Unit =
    require(pending.size < GraalSdkSession.maxPending, "At most 64 SDK/fetch calls may be pending per task")
    sequence += 1
    val callId = sequence
    val (result, cancel) = dispatcher.unsafeToFutureCancelable(action)
    pending(callId) = Pending(cancel)
    result.onComplete(value => replies.offer(Some(Reply(callId, () => deliver(value.toEither)))))

  /** Blocks without consuming guest CPU while command IO is in progress. */
  def next(): Unit = replies.take().foreach { reply =>
    pending.remove(reply.id).foreach(_ => reply.deliver())
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
