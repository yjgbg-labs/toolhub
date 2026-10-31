package groot.fetch

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.compression.{Compression, InflateParams}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import org.http4s.{Header, Headers, Method, Request, Response, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

/** Buffered HTTP fetch. All transport IO belongs to the task session and is cancelled with it. */
final class FetchService(client: Client[IO], timeout: FiniteDuration = 30.seconds):
  import FetchService.*

  def apply(input: FetchRequest): IO[FetchResponse] = IO.defer {
    val uri = parseUrl(input.url)
    val upper = input.method.toUpperCase(java.util.Locale.ROOT)
    val normalized = if Set("DELETE", "GET", "HEAD", "OPTIONS", "POST", "PUT").contains(upper) then upper else input.method
    val method = Method.fromString(normalized).fold(error => throw error, identity)
    if Set("CONNECT", "TRACE", "TRACK").contains(method.name.toUpperCase(java.util.Locale.ROOT)) then
      throw FetchFailure("TypeError", s"fetch() does not support method ${method.name}")
    if (method == Method.GET || method == Method.HEAD) && input.body.nonEmpty then
      throw FetchFailure("TypeError", "fetch() request with GET/HEAD method cannot have body")
    val body = input.body.map { text => input.bodyEncoding match
      case BodyEncoding.utf8 => text.getBytes(UTF_8)
      case BodyEncoding.base64 => Base64.getDecoder.decode(text)
    }
    if input.headers.size > 1024 then throw FetchFailure("TypeError", "fetch() accepts at most 1024 headers")
    val fields = input.headers.toVector.map { (rawName, rawValue) =>
      val name = rawName.toLowerCase(java.util.Locale.ROOT)
      val value = rawValue.dropWhile(c => c == ' ' || c == '\t' || c == '\r' || c == '\n')
        .reverse.dropWhile(c => c == ' ' || c == '\t' || c == '\r' || c == '\n').reverse
      if !name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || value.exists(c => c == 0 || c == '\r' || c == '\n' || c > 255) then
        throw FetchFailure("TypeError", "fetch() has an invalid header name or value")
      name -> value
    }.groupMap(_._1)(_._2).toVector.map((name, values) => name -> values.mkString(", "))
    val size = input.url.getBytes(UTF_8).length.toLong + input.method.length +
      fields.map((k, v) => k.length.toLong + v.getBytes(UTF_8).length).sum + body.fold(0)(_.length)
    if size > maxBytes then throw FetchFailure("RangeError", "fetch() request exceeds 1 MiB")
    val typed = if input.body.nonEmpty && input.bodyEncoding == BodyEncoding.utf8 && !fields.exists(_._1 == "content-type") then
      fields :+ ("content-type" -> "text/plain;charset=UTF-8") else fields
    // Framing is computed from the buffered body, including when callers reuse response headers.
    val headers = Headers(typed.filterNot { (name, _) =>
      name.equalsIgnoreCase("content-length") || name.equalsIgnoreCase("transfer-encoding")
    }.map { (name, value) => Header.Raw(CIString(name), value) })
    val request = Request[IO](method, uri, headers = headers)
    val encoded = body.fold(request)(bytes => request.withBodyStream(Stream.chunk(Chunk.array(bytes)))
      .putHeaders(Header.Raw(CIString("Content-Length"), bytes.length.toString)))
    val accepted = if header(encoded.headers, "accept").isDefined then encoded else encoded.putHeaders(Header.Raw(CIString("Accept"), "*/*"))
    val compressed = if header(accepted.headers, "accept-encoding").isDefined then accepted
      else accepted.putHeaders(Header.Raw(CIString("Accept-Encoding"), "gzip, deflate"))
    follow(compressed, 0)
  }.timeoutTo(timeout, IO.raiseError(FetchFailure("TimeoutError", "fetch() timed out")))
    .adaptError { case error if !error.isInstanceOf[FetchFailure] =>
      FetchFailure("TypeError", s"fetch failed: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
    }

  private def follow(request: Request[IO], redirects: Int): IO[FetchResponse] =
    client.run(request).use { response =>
      val location = header(response.headers, "location")
      if Set(301, 302, 303, 307, 308).contains(response.status.code) && location.nonEmpty then IO {
        if redirects >= 20 then throw FetchFailure("TypeError", "fetch() exceeded 20 redirects")
        val target = parseUrl(request.uri.resolve(Uri.unsafeFromString(location.get)).renderString)
        val rewrite = (Set(301, 302).contains(response.status.code) && request.method == Method.POST) ||
          (response.status.code == 303 && request.method != Method.GET && request.method != Method.HEAD)
        val next = if rewrite then request.withMethod(Method.GET).withEmptyBody else request
        val stripped = next.headers.headers.filterNot { h =>
          val name = h.name.toString.toLowerCase(java.util.Locale.ROOT)
          (rewrite && Set("content-encoding", "content-language", "content-location", "content-type").contains(name)) ||
            (origin(request.uri) != origin(target) && Set("authorization", "proxy-authorization", "cookie", "cookie2", "host").contains(name))
        }
        Left(next.withUri(target).withHeaders(Headers(stripped)))
      }
      else readBody(request, response).map { bytes =>
        val headers = response.headers.headers.groupMap(_.name.toString.toLowerCase(java.util.Locale.ROOT))(_.value)
          .toVector.sortBy(_._1).map { (name, values) => name -> values.mkString(", ") }
        Right(FetchResponse(request.uri.renderString, response.status.code, response.status.reason,
          response.status.isSuccess, redirects > 0, headers.toMap, Base64.getEncoder.encodeToString(bytes)))
      }
    }.flatMap {
      case Left(next) => follow(next, redirects + 1)
      case Right(result) => IO.pure(result)
    }

  private def readBody(request: Request[IO], response: Response[IO]): IO[Array[Byte]] =
    val empty = request.method == Method.HEAD || Set(204, 205, 304).contains(response.status.code)
    val bytes = if empty then Stream.empty.covary[IO] else
      header(response.headers, "content-encoding").map(_.toLowerCase(java.util.Locale.ROOT)) match
        case Some("gzip" | "x-gzip") => response.body.through(Compression[IO].gunzip()).flatMap(_.content)
        case Some("deflate") => response.body.through(Compression[IO].inflate(InflateParams()))
        case _ => response.body
    bytes.take(maxBytes.toLong + 1).compile.to(Array).flatMap { body =>
      if body.length > maxBytes then IO.raiseError(FetchFailure("RangeError", "fetch() response exceeds 1 MiB"))
      else IO.pure(body)
    }

object FetchService:
  val maxBytes = 1024 * 1024

  def resource: Resource[IO, FetchService] = EmberClientBuilder.default[IO].withTimeout(30.seconds).build.map(new FetchService(_))

  private def header(headers: Headers, name: String): Option[String] = headers.headers.find(_.name == CIString(name)).map(_.value)

  private def parseUrl(text: String): Uri =
    val uri = Uri.fromString(text).fold(_ => throw FetchFailure("TypeError", "fetch() URL is invalid"), identity)
    if !uri.scheme.exists(s => s == Uri.Scheme.http || s == Uri.Scheme.https) || uri.host.isEmpty then
      throw FetchFailure("TypeError", "fetch() requires an absolute HTTP or HTTPS URL")
    if uri.authority.exists(_.userInfo.nonEmpty) then throw FetchFailure("TypeError", "fetch() URL must not contain credentials")
    uri.copy(fragment = None)

  private def origin(uri: Uri): (Option[Uri.Scheme], Option[Uri.Host], Int) =
    (uri.scheme, uri.host, uri.port.getOrElse(if uri.scheme.contains(Uri.Scheme.https) then 443 else 80))
