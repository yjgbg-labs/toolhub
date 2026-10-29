package sprout.cli

import _root_.sttp.client4.*
import _root_.sttp.model.{Method, Uri}
import scala.concurrent.duration.*

/** Synchronous HTTP transport for the Native executable. Backend cleanup is caller-owned. */
final class HttpTransport(backend: SyncBackend, baseUrl: String) extends (HttpRequest => HttpResponse):
  require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"), "Expected HTTP(S) base URL")

  def apply(request: HttpRequest): HttpResponse =
    val initial = basicRequest.method(Method(request.method), Uri.unsafeParse(request.url(baseUrl)))
      .headers(request.headers.map((name, value) => _root_.sttp.model.Header(name, value))*)
      .readTimeout(30.seconds).followRedirects(false).response(asStringAlways)
    val response = request.body.fold(initial)(initial.body(_)).send(backend)
    HttpResponse(response.code.code, response.headers.map(h => h.name -> h.value).toVector, response.body)
