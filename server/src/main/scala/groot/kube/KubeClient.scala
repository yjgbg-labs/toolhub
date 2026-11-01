package groot.kube

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, CompletionStage, Flow}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

/** One context revision owns one pool. A caller holds its lease until the body is consumed. */
private[kube] final class KubeClient(val config: ContextConfig, private val http: HttpClient):
  val discovery = new Discovery(this)
  def close: IO[Unit] = IO.blocking(http.close())

  def send(method: String, path: String, query: Vector[(String, String)] = Vector.empty,
      body: Option[Json] = None, contentType: String = "application/json", maxBytes: Int = KubeClient.maxBytes): IO[String] = IO.defer {
    KubeClient.relativePath(path)
    val suffix = query.map((k, v) => s"${KubeClient.encode(k)}=${KubeClient.encode(v)}").mkString("&")
    val url = config.server.stripSuffix("/") + path + (if suffix.isEmpty then "" else (if path.contains('?') then "&" else "?") + suffix)
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(30))
      .header("Accept", "application/json").header("Accept-Encoding", "identity")
    config.token.foreach(token => builder.header("Authorization", s"Bearer $token"))
    val publisher = body.fold(HttpRequest.BodyPublishers.noBody()) { value =>
      val bytes = value.noSpaces.getBytes(UTF_8)
      KubeFailure.require(bytes.length <= KubeClient.maxBytes, "Request exceeds 768 KiB")
      builder.header("Content-Type", contentType)
      HttpRequest.BodyPublishers.ofByteArray(bytes)
    }
    // The JDK exchange is cancelled with the fiber, including before headers or while awaiting body chunks.
    val handler: HttpResponse.BodyHandler[Array[Byte]] = _ => new KubeClient.BoundedBody(maxBytes)
    val request = builder.method(method, publisher).build()
    val exchange = IO.async[HttpResponse[Array[Byte]]] { callback => IO {
      val future = http.sendAsync(request, handler)
      future.whenComplete { (value, error) => callback(if error == null then Right(value) else Left(error)) }
      // HttpClient only attempts to abort the transport when mayInterruptIfRunning is true.
      Some(IO { future.cancel(true); () })
    } }
    exchange.flatMap { response =>
      val text = new String(response.body(), UTF_8)
      if response.statusCode() >= 200 && response.statusCode() < 300 then IO.pure(text)
      else IO.raiseError(KubeFailure(response.statusCode(), io.circe.parser.parse(text).getOrElse(
        KubeFailure.local(response.statusCode(), "UpstreamError", text).body)))
    }
  }.timeoutTo(30.seconds, IO.raiseError(KubeFailure.local(504, "Timeout", "Kubernetes request exceeded 30 seconds")))
    .adaptError { case error if !error.isInstanceOf[KubeFailure] =>
      // Transport exceptions can include request details. Do not reflect credentials or PEM values.
      Iterator.iterate(error)(_.getCause).takeWhile(_ != null).collectFirst {
        case failure: KubeFailure => failure
        case _: java.net.http.HttpTimeoutException => KubeFailure.local(504, "Timeout", "Kubernetes HTTP request timed out")
      }
        .getOrElse(KubeFailure.local(502, "ConnectionFailed", s"Kubernetes connection failed (${error.getClass.getSimpleName})"))
    }

  def json(method: String, path: String, query: Vector[(String, String)] = Vector.empty,
      body: Option[Json] = None, contentType: String = "application/json"): IO[Json] =
    send(method, path, query, body, contentType).flatMap(text => IO.fromEither(io.circe.parser.parse(text)
      .left.map(_ => KubeFailure.local(502, "InvalidResponse", "Apiserver returned invalid JSON"))))

private[kube] object KubeClient:
  val maxBytes = 768 * 1024
  private class BoundedBody(limit: Int) extends HttpResponse.BodySubscriber[Array[Byte]]:
    private val result = new CompletableFuture[Array[Byte]]()
    private val output = new ByteArrayOutputStream()
    private var subscription: Flow.Subscription = null
    def getBody(): CompletionStage[Array[Byte]] = result
    def onSubscribe(value: Flow.Subscription): Unit =
      subscription = value
      value.request(1)
    def onNext(buffers: java.util.List[ByteBuffer]): Unit =
      val size = buffers.asScala.map(_.remaining().toLong).sum
      if output.size().toLong + size > limit then
        subscription.cancel()
        result.completeExceptionally(KubeFailure.local(413, "ResponseTooLarge", "Kubernetes response exceeds the limit; use limit/continue or smaller log limits"))
      else
        buffers.asScala.foreach { buffer =>
          val bytes = new Array[Byte](buffer.remaining())
          buffer.get(bytes)
          output.write(bytes)
        }
        subscription.request(1)
    def onError(error: Throwable): Unit = { result.completeExceptionally(error); () }
    def onComplete(): Unit = { result.complete(output.toByteArray); () }
  def encode(value: String): String = URLEncoder.encode(value, UTF_8).replace("+", "%20")
  def segment(value: String): String =
    KubeFailure.require(value.nonEmpty && value != "." && value != ".." && !value.exists(c => c == '/' || c == '\\' || Character.isISOControl(c)), "Invalid resource path segment")
    encode(value)
  def relativePath(path: String): Unit =
    val uri = try URI.create(path) catch case _: IllegalArgumentException => throw KubeFailure.local(400, "Invalid", "Invalid apiserver relative path")
    KubeFailure.require(path.startsWith("/") && !path.startsWith("//") && uri.getScheme == null && uri.getRawAuthority == null && uri.getFragment == null,
      "path must be an apiserver-relative absolute path without a fragment")
    KubeFailure.require(!Option(uri.getPath).getOrElse("").split('/').exists(p => p == "." || p == "..") && !path.contains('\\'), "Invalid path traversal")

  def create(config: ContextConfig): IO[KubeClient] = IO.blocking {
    val uri = URI.create(config.server)
    KubeFailure.require(Set("http", "https").contains(uri.getScheme) && uri.getHost != null && uri.getUserInfo == null &&
      uri.getQuery == null && uri.getFragment == null && (uri.getPath == "" || uri.getPath == "/"), "server must be an HTTP(S) origin without credentials, path, query or fragment")
    KubeFailure.require(config.namespace.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?") && config.namespace.length <= 63, "Invalid default namespace")
    KubeFailure.require(config.clientCertificate.isDefined == config.clientKey.isDefined, "clientCertificate and clientKey must be supplied together")
    config.token.foreach(value => KubeFailure.require(value.nonEmpty && value.forall(c => c > ' ' && c <= '~'), "Invalid bearer token"))
    val tls = KubeTls.context(config)
    new KubeClient(config, HttpClient.newBuilder().sslContext(tls).connectTimeout(java.time.Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER).build())
  }.adaptError { case error if !error.isInstanceOf[KubeFailure] => KubeFailure.local(400, "Invalid", s"Invalid context TLS or server configuration (${error.getClass.getSimpleName})") }
