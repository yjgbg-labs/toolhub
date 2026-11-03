package groot.chrome

import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.{CompletableFuture, CompletionStage, ConcurrentHashMap}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** One multiplexed browser WebSocket; replies and cancellation never create reader threads. */
private[chrome] final class Cdp private (sendGate: Semaphore[IO]) extends WebSocket.Listener:
  private val sequence = new AtomicLong()
  private val pending = new ConcurrentHashMap[Long, Either[Throwable, Json] => Unit]()
  private val closed = new AtomicReference[Throwable]()
  private val attached = new ConcurrentHashMap[String, (String, String)]()
  private val buffer = new StringBuilder
  @volatile private var socket: WebSocket = null
  def isOpen: Boolean = closed.get() == null
  def hasSession(id: String): Boolean = attached.containsKey(id)
  def childFrames(parent: String): Vector[String] = attached.entrySet().asScala.toVector.collect {
    case entry if entry.getValue == (parent, "iframe") => entry.getKey
  }

  private def fail(error: Throwable): Unit =
    if closed.compareAndSet(null, error) then
      pending.keySet().asScala.foreach { id => Option(pending.remove(id)).foreach(_(Left(error))) }

  override def onOpen(ws: WebSocket): Unit =
    socket = ws
    if closed.get() != null then ws.abort() else ws.request(1)

  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    try
      if buffer.length + data.length > 32 * 1024 * 1024 then
        throw ChromeFailure("chrome_limit", "CDP message exceeds 32 MiB")
      buffer.append(data)
      if last then
        val json = parse(buffer.toString).fold(throw _, identity)
        buffer.clear()
        json.hcursor.get[Long]("id").toOption.foreach { id =>
          Option(pending.remove(id)).foreach { callback =>
            val error = ChromeJson.field(json, "error")
            callback(if error.isNull then Right(ChromeJson.field(json, "result"))
              else Left(ChromeFailure("cdp_error", ChromeJson.text(error, "message"))))
          }
        }
        val params = ChromeJson.field(json, "params")
        ChromeJson.text(json, "method") match
          case "Target.attachedToTarget" =>
            attached.put(ChromeJson.text(params, "sessionId"),
              (ChromeJson.text(json, "sessionId"), ChromeJson.text(ChromeJson.field(params, "targetInfo"), "type")))
          case "Target.detachedFromTarget" =>
            def detach(id: String): Unit =
              attached.remove(id)
              attached.entrySet().asScala.filter(_.getValue._1 == id).map(_.getKey).toVector.foreach(detach)
            detach(ChromeJson.text(params, "sessionId"))
          case _ => ()
      ws.request(1)
    catch case scala.util.control.NonFatal(error) =>
      fail(error)
      ws.abort()
    CompletableFuture.completedFuture(null)

  override def onClose(ws: WebSocket, status: Int, reason: String): CompletionStage[?] =
    fail(ChromeFailure("chrome_disconnected", s"Chrome connection closed ($status): $reason"))
    CompletableFuture.completedFuture(null)
  override def onError(ws: WebSocket, error: Throwable): Unit =
    fail(ChromeFailure("chrome_disconnected", Option(error.getMessage).getOrElse("Chrome connection failed")))

  def call(method: String, params: Json = Json.obj(), session: Option[String] = None): IO[Json] = IO.defer {
    val id = sequence.incrementAndGet()
    val message = Json.obj("id" -> Json.fromLong(id), "method" -> Json.fromString(method), "params" -> params)
      .deepMerge(session.fold(Json.obj())(s => Json.obj("sessionId" -> Json.fromString(s)))).noSpaces
    IO.async[Json] { callback =>
      IO {
        pending.put(id, callback)
        Option(closed.get()).foreach { error => Option(pending.remove(id)).foreach(_(Left(error))) }
      } *> sendGate.permit.use { _ =>
        if closed.get() != null then IO.unit
        else IO.fromCompletableFuture(IO(socket.sendText(message, true))).void
          .handleErrorWith(error => IO { Option(pending.remove(id)).foreach(_(Left(error))) })
      }.as(Some(IO { pending.remove(id); () }))
    }.timeoutTo(125.seconds, IO.raiseError(ChromeFailure("chrome_timeout", s"CDP command timed out: $method")))
  }

  def close: IO[Unit] = IO {
    fail(ChromeFailure("chrome_disconnected", "Chrome connection released"))
    if socket != null then socket.abort()
  }

private[chrome] object Cdp:
  def connect(client: HttpClient, uri: URI): Resource[IO, Cdp] = for
    gate <- Resource.eval(Semaphore[IO](1))
    cdp <- Resource.make(IO(new Cdp(gate)))(_.close)
    _ <- Resource.eval(IO.fromCompletableFuture(IO(client.newWebSocketBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).buildAsync(uri, cdp))))
  yield cdp
