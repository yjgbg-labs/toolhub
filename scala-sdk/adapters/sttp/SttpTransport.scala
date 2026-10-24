//> using dep com.softwaremill.sttp.client4::core::4.1.0
package sprout.sdk.sttp

import _root_.sttp.client4.*
import _root_.sttp.model.{Header, Method, Uri}
import scala.concurrent.duration.*
import sprout.sdk.*

/** Works with JVM HttpClient, JS Fetch and Native Curl backends. Backend is caller-owned. */
final class SttpTransport[F[_]](
    backend: Backend[F],
    baseUrl: String,
    headers: Vector[(String, String)] = Vector.empty,
    timeout: FiniteDuration = 30.seconds
) extends (HttpRequest => F[HttpResponse]):
  require(timeout > Duration.Zero, "timeout must be positive")
  require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"), "Expected HTTP(S) base URL")

  def apply(request: HttpRequest): F[HttpResponse] =
    val initial = basicRequest.method(Method(request.method), Uri.unsafeParse(request.url(baseUrl)))
      .headers((headers ++ request.headers).map((name, value) => Header(name, value))*)
      .readTimeout(timeout).followRedirects(false).response(asStringAlways)
    val outgoing = request.body.fold(initial)(initial.body(_))
    backend.monad.map(outgoing.send(backend)) { response =>
      HttpResponse(response.code.code, response.headers.map(h => h.name -> h.value).toVector, response.body)
    }

/** Useful for Identity/Try/Future and callers already using an sttp effect backend. */
final class SttpExecutor[F[_]](backend: Backend[F], transport: SttpTransport[F]) extends Executor[F]:
  def execute[A](call: Call[A]): F[A] =
    val F = backend.monad
    F.handleError(F.flatMap(transport(call.request))(response => call.decode(response).fold(F.error, F.unit))) {
      case error => F.error(ClientError.fromThrowable(error))
    }
