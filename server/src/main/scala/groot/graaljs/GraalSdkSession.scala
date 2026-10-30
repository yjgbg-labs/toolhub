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
import scala.util.Using

/** Guest Values are only touched by the task's execution thread. IO callbacks only enqueue immutable replies. */
private[graaljs] final class GraalSdkSession(sdk: CommandSdk, dispatcher: Dispatcher[IO]):
  private given ExecutionContext = ExecutionContext.parasitic
  private case class Pending(resolve: Value, reject: Value, cancel: () => Future[Unit])
  private case class Reply(id: Long, result: Either[Throwable, String])
  private val replies = new LinkedBlockingQueue[Option[Reply]]()
  private val pending = mutable.Map.empty[Long, Pending]
  private var sequence = 0L

  def install(context: Context): Unit =
    val send = GraalSdkSession.callback { arguments =>
      require(arguments.length == 4 && arguments(0).isString && arguments(1).isString &&
        arguments(2).canExecute && arguments(3).canExecute, "Invalid SDK invocation")
      require(pending.size < GraalSdkSession.maxPending, "At most 64 SDK calls may be pending per task")
      val id = arguments(0).asString()
      val input = arguments(1).asString()
      require(input.getBytes(UTF_8).length <= CommandSdk.maxBytes, "SDK input exceeds 1 MiB")
      val action = IO.fromEither(io.circe.parser.parse(input)).flatMap(sdk.call(id, _)).map { result =>
        val json = result.noSpaces
        require(json.getBytes(UTF_8).length <= CommandSdk.maxBytes, "SDK response exceeds 1 MiB")
        json
      }
      sequence += 1
      val callId = sequence
      // ISOLATED host access scopes callback arguments; retain only these two guest functions until delivery.
      arguments(2).pin()
      arguments(3).pin()
      val (result, cancel) = dispatcher.unsafeToFutureCancelable(action)
      pending(callId) = Pending(arguments(2), arguments(3), cancel)
      result.onComplete(value => replies.offer(Some(Reply(callId, value.toEither))))
      null
    }
    context.eval("js", GraalSdkSession.bootstrap).execute(sdk.commands.noSpaces, send)
    ()

  /** Blocks without consuming guest CPU while command IO is in progress. */
  def next(): Unit = replies.take().foreach { reply =>
    pending.remove(reply.id).foreach { call =>
      reply.result match
        case Right(json) => call.resolve.execute(json)
        case Left(error) => call.reject.execute(Option(error.getMessage).getOrElse(error.getClass.getSimpleName).take(8192))
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

  private val bootstrap: String = Using.resource(getClass.getResourceAsStream("/graaljs-sdk.js")) { stream =>
    require(stream != null, "Missing GraalJS command SDK resource")
    new String(stream.readAllBytes(), UTF_8)
  }
