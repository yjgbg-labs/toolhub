package sprout.cli

import _root_.sttp.client4.*
import _root_.sttp.model.{Method, Uri}
import scala.concurrent.duration.*
import scala.util.Using

/** Synchronous HTTP transport for the Native executable. Backend cleanup is caller-owned. */
final class HttpTransport(backend: SyncBackend, baseUrl: String, onEvent: String => Unit) extends (HttpRequest => HttpResponse):
  require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"), "Expected HTTP(S) base URL")

  def apply(request: HttpRequest): HttpResponse =
    // Native curl uses a total transfer timeout. Commands can return an unbounded
    // SSE body, discovered only from response headers; discovery remains bounded.
    val timeout = if request.allowStreaming then Duration.Zero else 30.seconds
    val initial = basicRequest.method(Method(request.method), Uri.unsafeParse(request.url(baseUrl)))
      .headers(request.headers.map((name, value) => _root_.sttp.model.Header(name, value))*)
      .readTimeout(timeout).followRedirects(false).response(asInputStreamAlwaysUnsafe)
    val response = request.body.fold(initial)(initial.body(_)).send(backend)
    val result = HttpResponse(response.code.code, response.headers.map(h => h.name -> h.value).toVector, "")
    Using.resource(response.body) { input =>
      if request.allowStreaming && response.isSuccess && result.mediaType.contains("text/event-stream") then
        ServerSentEvents.consume(input, onEvent)
        result
      else
        val charset = response.contentType.flatMap(value => _root_.sttp.model.MediaType.parse(value).toOption)
          .flatMap(_.charset).getOrElse("UTF-8")
        result.copy(body = scala.io.Source.fromInputStream(input, charset).mkString)
    }
