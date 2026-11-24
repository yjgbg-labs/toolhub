package groot.telegram

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.validation.Contracts
import io.circe.Json
import io.circe.parser.parse as parseJson
import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{InetSocketAddress, ProxySelector, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Duration as JDuration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

final class TelegramClient private (
    config: IO[TelegramConfig],
    cachedClient: AtomicReference[(String, HttpClient)]
):
  import TelegramClient.*

  private def clientFor(proxyUrl: String): IO[HttpClient] = IO {
    val normalized = proxyUrl.trim
    val fast = cachedClient.get()
    if fast != null && fast._1 == normalized then fast._2
    else
      cachedClient.synchronized {
        val current = cachedClient.get()
        if current != null && current._1 == normalized then current._2
        else
          val builder = HttpClient.newBuilder()
            .connectTimeout(JDuration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
          if normalized.nonEmpty then
            val uri = URI.create(normalized)
            val host = Option(uri.getHost).filter(_.nonEmpty).getOrElse(
              throw TelegramFailure("invalid_telegram", s"Proxy URL has no host: $normalized")
            )
            val port =
              if uri.getPort > 0 then uri.getPort
              else if "https".equalsIgnoreCase(uri.getScheme) then 443
              else 80
            builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)))
          val built = builder.build()
          val prev = cachedClient.getAndSet((normalized, built))
          if prev != null && (prev._2 ne built) then
            try prev._2.shutdown()
            catch case _: Throwable => ()
          built
      }
  }

  private[telegram] def shutdown: IO[Unit] = IO.blocking {
    cachedClient.synchronized {
      val prev = cachedClient.getAndSet(null)
      if prev != null then
        try prev._2.shutdownNow()
        catch case _: Throwable => ()
    }
  }

  def callJson(
      token: String,
      method: String,
      payload: Json = Json.obj(),
      extraTimeoutSeconds: Int = 0
  ): IO[Json] =
    validateMethod(method) *> config.flatMap { cfg =>
      val cleanToken = sanitizeToken(token)
      val url = s"${cfg.apiBase.trim.stripSuffix("/")}/bot$cleanToken/$method"
      val timeoutMs = math.max(
        Contracts.parseDuration(cfg.timeout).toMillis,
        (extraTimeoutSeconds.toLong + 10L) * 1000L
      )
      val bodyBytes = payload.noSpaces.getBytes(UTF_8)
      val makeReq = () =>
        val b = HttpRequest.newBuilder(URI.create(url))
          .timeout(JDuration.ofMillis(timeoutMs))
          .header("Content-Type", "application/json; charset=utf-8")
          .header("Accept", "application/json")
          .header("User-Agent", "groot-telegram/1.0")
          .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
        if url.startsWith("http://") then b.version(HttpClient.Version.HTTP_1_1).build() else b.build()
      executeEnvelope(cfg, cleanToken, method, makeReq, timeoutMs, attempt = 0)
    }

  def callMultipart(
      token: String,
      method: String,
      fields: Vector[(String, String)],
      fileField: String,
      filePath: Path,
      filenameOverride: Option[String]
  ): IO[Json] =
    validateMethod(method) *> config.flatMap { cfg =>
      val cleanToken = sanitizeToken(token)
      for
        normalized <- IO.blocking(filePath.toAbsolutePath.normalize())
        exists <- IO.blocking(Files.isRegularFile(normalized))
        _ <- IO.raiseUnless(exists)(
          TelegramFailure("telegram_not_found", s"Local upload file not found: $normalized")
        )
        size <- IO.blocking(Files.size(normalized))
        _ <- IO.raiseWhen(size > cfg.maxUploadBytes.toLong)(
          TelegramFailure(
            "telegram_limit",
            s"File $normalized (${Contracts.formatBytes(size)}) exceeds maxUploadBytes (${Contracts.formatBytes(cfg.maxUploadBytes.toLong)})"
          )
        )
        fileBytes <- IO.blocking(Files.readAllBytes(normalized))
        mime <- IO.blocking(Option(Files.probeContentType(normalized)).getOrElse("application/octet-stream"))
        boundary = s"----grootTg${UUID.randomUUID().toString.replace("-", "")}"
        fileName = filenameOverride.map(_.trim).filter(_.nonEmpty)
          .getOrElse(Option(normalized.getFileName).map(_.toString).getOrElse("upload.bin"))
        multipartBytes = encodeMultipart(boundary, fields, fileField, fileName, mime, fileBytes)
        url = s"${cfg.apiBase.trim.stripSuffix("/")}/bot$cleanToken/$method"
        timeoutMs = math.max(Contracts.parseDuration(cfg.timeout).toMillis, 60000L)
        makeReq = () => {
          val b = HttpRequest.newBuilder(URI.create(url))
            .timeout(JDuration.ofMillis(timeoutMs))
            .header("Content-Type", s"multipart/form-data; boundary=$boundary")
            .header("Accept", "application/json")
            .header("User-Agent", "groot-telegram/1.0")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBytes))
          if url.startsWith("http://") then b.version(HttpClient.Version.HTTP_1_1).build() else b.build()
        }
        res <- executeEnvelope(cfg, cleanToken, method, makeReq, timeoutMs, attempt = 0)
      yield res
    }

  def downloadFile(token: String, telegramFilePath: String): IO[Array[Byte]] =
    val cleanRel = telegramFilePath.trim.stripPrefix("/")
    if cleanRel.isEmpty || cleanRel.contains("..") || cleanRel.contains('\u0000') then
      IO.raiseError(TelegramFailure("invalid_telegram", s"Invalid Telegram file path: $telegramFilePath"))
    else
      config.flatMap { cfg =>
        val cleanToken = sanitizeToken(token)
        val url = s"${cfg.apiBase.trim.stripSuffix("/")}/file/bot$cleanToken/$cleanRel"
        val timeoutMs = math.max(Contracts.parseDuration(cfg.timeout).toMillis, 60000L)
        clientFor(cfg.proxyUrl).flatMap { httpClient =>
          val reqBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(JDuration.ofMillis(timeoutMs))
            .header("User-Agent", "groot-telegram/1.0")
            .GET()
          val req = if url.startsWith("http://") then reqBuilder.version(HttpClient.Version.HTTP_1_1).build() else reqBuilder.build()
          sendBounded(httpClient, req, cfg.maxUploadBytes, cleanToken, timeoutMs).flatMap { (status, bytes) =>
            if status >= 200 && status < 300 then IO.pure(bytes)
            else if status == 404 then
              IO.raiseError(TelegramFailure("telegram_not_found", s"Telegram file not found: $cleanRel"))
            else if status == 401 then
              IO.raiseError(TelegramFailure("telegram_unauthenticated", "Invalid Telegram bot token"))
            else
              IO.raiseError(
                TelegramFailure("telegram_upstream", s"Telegram file download failed with HTTP $status")
              )
          }
        }
      }

  private def executeEnvelope(
      cfg: TelegramConfig,
      token: String,
      method: String,
      makeReq: () => HttpRequest,
      timeoutMs: Long,
      attempt: Int
  ): IO[Json] =
    clientFor(cfg.proxyUrl).flatMap { httpClient =>
      sendBounded(httpClient, makeReq(), cfg.maxResponseBytes, token, timeoutMs).flatMap { (status, bytes) =>
        val bodyText = new String(bytes, UTF_8)
        parseJson(bodyText) match
          case Left(_) =>
            mapHttpError(status, method, bodyText.take(240), retryAfter = None)
          case Right(json) =>
            val c = json.hcursor
            val ok = c.get[Boolean]("ok").getOrElse(false)
            if ok then IO.pure(c.downField("result").focus.getOrElse(Json.Null))
            else
              val errCode = c.get[Int]("error_code").getOrElse(status)
              val desc = redact(
                c.get[String]("description").getOrElse(s"HTTP $status from Telegram $method"),
                token
              )
              val retryAfter = c.downField("parameters").get[Int]("retry_after").toOption.filter(_ > 0)
              if errCode == 429 && attempt < 2 && retryAfter.exists(_.toLong * 1000L <= cfg.maxRateLimitWaitMs.toLong) then
                val waitMs = retryAfter.get.toLong * 1000L + 250L
                IO.sleep(waitMs.millis) *> executeEnvelope(cfg, token, method, makeReq, timeoutMs, attempt + 1)
              else mapHttpError(errCode, method, desc, retryAfter)
      }
    }

  private def sendBounded(
      httpClient: HttpClient,
      req: HttpRequest,
      maxBytes: Int,
      token: String,
      timeoutMs: Long
  ): IO[(Int, Array[Byte])] =
    IO.fromCompletableFuture(IO(httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())))
      .timeoutTo(
        (timeoutMs + 2000L).millis,
        IO.raiseError(TelegramFailure("telegram_timeout", s"Telegram request timed out after ${timeoutMs}ms"))
      )
      .adaptError {
        case e: TelegramFailure => e
        case _: HttpTimeoutException | _: TimeoutException =>
          TelegramFailure("telegram_timeout", s"Telegram request timed out after ${timeoutMs}ms")
        case e: Throwable =>
          TelegramFailure(
            "telegram_upstream",
            s"Telegram transport failed: ${redact(Option(e.getMessage).getOrElse(e.getClass.getSimpleName), token)}"
          )
      }
      .flatMap { resp =>
        IO.blocking(readUpTo(resp.body(), maxBytes)).map(bytes => (resp.statusCode(), bytes))
      }

object TelegramClient:
  private val methodPattern = "^[A-Za-z][A-Za-z0-9_]{1,63}$".r
  private val tokenPattern = "^\\d+:[A-Za-z0-9_-]{20,}$".r

  def resource(config: IO[TelegramConfig] = IO.pure(TelegramConfig())): Resource[IO, TelegramClient] =
    Resource.make(
      IO(new TelegramClient(config, new AtomicReference[(String, HttpClient)](null)))
    )(_.shutdown)

  def sanitizeToken(raw: String): String =
    val trimmed = Option(raw).map(_.trim).getOrElse("")
    val stripped = if trimmed.startsWith("bot") && trimmed.contains(':') && trimmed.drop(3).headOption.exists(_.isDigit) then
      trimmed.drop(3)
    else trimmed
    if stripped.isEmpty then
      throw TelegramFailure(
        "telegram_unauthenticated",
        "Not logged in to Telegram; run 'groot-cli telegram login --token ...'"
      )
    if !tokenPattern.matches(stripped) then
      throw TelegramFailure("invalid_telegram", "Malformed Telegram bot token (expected '<bot_id>:<secret>')")
    stripped

  def extractBotId(token: String): Option[Long] =
    token.takeWhile(_ != ':').toLongOption

  private def validateMethod(method: String): IO[Unit] =
    if method != null && methodPattern.matches(method.trim) then IO.unit
    else IO.raiseError(TelegramFailure("invalid_telegram", s"Invalid Telegram Bot API method name: '$method'"))

  private def redact(message: String, token: String): String =
    if token.nonEmpty then message.replace(token, "<redacted>") else message

  private def readUpTo(in: InputStream, maxBytes: Int): Array[Byte] =
    try
      val out = new ByteArrayOutputStream(math.min(maxBytes, 8192))
      val buf = new Array[Byte](8192)
      var total = 0L
      var n = in.read(buf)
      while n != -1 do
        total += n.toLong
        if total > maxBytes.toLong then
          throw TelegramFailure(
            "telegram_limit",
            s"Telegram response exceeded limit of ${Contracts.formatBytes(maxBytes.toLong)}"
          )
        out.write(buf, 0, n)
        n = in.read(buf)
      out.toByteArray
    finally in.close()

  private def encodeMultipart(
      boundary: String,
      fields: Vector[(String, String)],
      fileField: String,
      fileName: String,
      mimeType: String,
      fileBytes: Array[Byte]
  ): Array[Byte] =
    val out = new ByteArrayOutputStream(fileBytes.length + 1024)
    val crlf = "\r\n".getBytes(UTF_8)
    fields.foreach { (name, value) =>
      out.write(s"--$boundary\r\n".getBytes(UTF_8))
      out.write(s"""Content-Disposition: form-data; name="${escapeQuotes(name)}"\r\n\r\n""".getBytes(UTF_8))
      out.write(value.getBytes(UTF_8))
      out.write(crlf)
    }
    out.write(s"--$boundary\r\n".getBytes(UTF_8))
    out.write(
      s"""Content-Disposition: form-data; name="${escapeQuotes(fileField)}"; filename="${escapeQuotes(fileName)}"\r\n""".getBytes(UTF_8)
    )
    out.write(s"Content-Type: $mimeType\r\n\r\n".getBytes(UTF_8))
    out.write(fileBytes)
    out.write(crlf)
    out.write(s"--$boundary--\r\n".getBytes(UTF_8))
    out.toByteArray

  private def escapeQuotes(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")

  private def mapHttpError(status: Int, method: String, desc: String, retryAfter: Option[Int]): IO[Nothing] =
    val cleanDesc = if desc.trim.nonEmpty then desc.trim else s"HTTP $status"
    val failure = status match
      case 400 => TelegramFailure("invalid_telegram", s"Telegram $method failed: $cleanDesc")
      case 401 => TelegramFailure("telegram_unauthenticated", s"Telegram authentication failed: $cleanDesc")
      case 403 => TelegramFailure("telegram_forbidden", s"Telegram access denied: $cleanDesc")
      case 404 => TelegramFailure("telegram_not_found", s"Telegram resource or method not found: $cleanDesc")
      case 409 => TelegramFailure("telegram_conflict", s"Telegram conflict (e.g. active webhook vs getUpdates): $cleanDesc")
      case 413 => TelegramFailure("telegram_limit", s"Telegram payload too large: $cleanDesc")
      case 429 =>
        val suffix = retryAfter.map(s => s" (retry after ${s}s)").getOrElse("")
        TelegramFailure("telegram_rate_limited", s"Telegram rate limit exceeded: $cleanDesc$suffix")
      case 504 => TelegramFailure("telegram_timeout", s"Telegram gateway timeout: $cleanDesc")
      case _   => TelegramFailure("telegram_upstream", s"Telegram $method failed ($status): $cleanDesc")
    IO.raiseError(failure)
