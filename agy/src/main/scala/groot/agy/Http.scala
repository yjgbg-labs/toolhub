package groot.agy

import _root_.sttp.client4.*
import _root_.sttp.model.{Header, Method, Uri}
import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*
import scala.util.Using

final case class HttpRequest(
    method: String,
    path: String,
    query: Vector[(String, String)] = Vector.empty,
    headers: Vector[(String, String)] = Vector.empty,
    body: Option[String] = None,
    allowStreaming: Boolean = false
):
  def url(baseUrl: String): String =
    val suffix = if query.isEmpty then "" else query.map((k, v) => s"${Wire.encode(k)}=${Wire.encode(v)}").mkString("?", "&", "")
    baseUrl.stripSuffix("/") + path + suffix

final case class HttpResponse(status: Int, headers: Vector[(String, String)], body: String):
  def isSuccess: Boolean = status >= 200 && status < 300
  def mediaType: Option[String] = headers.collectFirst {
    case (name, value) if name.equalsIgnoreCase("content-type") => value.takeWhile(_ != ';').trim.toLowerCase
  }

object Wire:
  def encode(value: String): String =
    val hex = "0123456789ABCDEF"
    val result = new StringBuilder
    value.getBytes(UTF_8).foreach { byte =>
      val n = byte & 255
      if (n >= 65 && n <= 90) || (n >= 97 && n <= 122) || (n >= 48 && n <= 57) || "-._~".contains(n.toChar) then result.append(n.toChar)
      else result.append('%').append(hex(n >> 4)).append(hex(n & 15))
    }
    result.toString

object ServerSentEvents:
  def consume(input: InputStream, emit: String => Unit): Unit =
    val reader = new BufferedReader(new InputStreamReader(input, UTF_8))
    val data = new StringBuilder
    var hasData = false
    var firstLine = true
    var line = reader.readLine()
    while line != null do
      if firstLine then
        line = line.stripPrefix("\uFEFF")
        firstLine = false
      if line.isEmpty then
        if hasData then emit(data.toString)
        data.clear()
        hasData = false
      else if line == "data" || line.startsWith("data:") then
        if hasData then data.append('\n')
        data.append(if line == "data" then "" else line.drop(5).stripPrefix(" "))
        hasData = true
      line = reader.readLine()

trait HttpTransport:
  def send(request: HttpRequest, onEvent: String => Unit = _ => ()): HttpResponse

final class SttpHttpTransport(baseUrl: String, defaultHeaders: Vector[(String, String)] = Vector.empty, timeoutMs: Long = 30000L) extends HttpTransport:
  require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"), "Expected HTTP(S) base URL")

  def send(request: HttpRequest, onEvent: String => Unit = _ => ()): HttpResponse =
    val timeout = if request.allowStreaming then Duration.Zero else timeoutMs.millis
    val uri = Uri.unsafeParse(request.url(baseUrl)).querySegmentsEncoding(Wire.encode)
    val allHeaders = (defaultHeaders ++ request.headers).map((name, value) => Header(name, value))
    val initial = basicRequest.method(Method(request.method), uri)
      .headers(allHeaders*)
      .readTimeout(timeout)
      .followRedirects(false)
      .response(asInputStreamAlwaysUnsafe)
    // A fresh backend per call isolates libcurl handles across concurrent worker threads.
    Using.resource(DefaultSyncBackend()) { backend =>
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
    }
