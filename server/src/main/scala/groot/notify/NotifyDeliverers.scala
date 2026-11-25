package groot.notify

import cats.effect.IO
import framework.validation.Contracts
import io.circe.Json
import io.circe.parser.parse
import java.io.{ByteArrayOutputStream, IOException}
import java.net.{ConnectException, URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Duration as JDuration
import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Using

/** Composable server-side notification delivery strategy keyed by targetSessionId pattern. */
type NotifyDeliverer = PartialFunction[Notification, IO[String]]

object NotifyDeliverers:
  val openCodeSessionPattern = "^ses[A-Za-z0-9_-]{1,125}$".r
  val antigravitySessionPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r
  val descriptionLimit = 120

  private final case class OpenCodeEndpoint(baseUrl: String, password: Option[String])
  private final case class AntigravityDaemon(httpPort: Int, csrfToken: String, modifiedMillis: Long)

  def defaultIsProcessAlive(pid: Long): Boolean =
    pid <= 0L || ProcessHandle.of(pid).toScala.exists(_.isAlive)

  /** PartialFunction handling OpenCode sessions (`ses...`) via `POST /api/session/:sessionID/synthetic`. */
  def openCode(
      httpClient: HttpClient,
      config: IO[NotifyConfig],
      isProcessAlive: Long => Boolean = defaultIsProcessAlive
  ): NotifyDeliverer =
    case notification if openCodeSessionPattern.matches(notification.targetSessionId) =>
      config.flatMap { cfg =>
        val timeout = toJavaDuration(cfg.deliveryTimeout)
        val serviceFile = resolveOpenCodeServiceFile(cfg.openCodeServiceFile)
        IO.blocking {
          val endpoint = readOpenCodeEndpoint(serviceFile, isProcessAlive)
          sendOpenCodeSynthetic(httpClient, endpoint, notification, timeout)
          "opencode"
        }
      }

  /** PartialFunction handling Antigravity conversations (UUID) via Connect-RPC `SendAgentMessage`. */
  def antigravity(
      httpClient: HttpClient,
      config: IO[NotifyConfig],
      isProcessAlive: Long => Boolean = defaultIsProcessAlive
  ): NotifyDeliverer =
    case notification if antigravitySessionPattern.matches(notification.targetSessionId) =>
      config.flatMap { cfg =>
        val timeout = toJavaDuration(cfg.deliveryTimeout)
        val daemonDir = resolveAntigravityDaemonDir(cfg.antigravityDaemonDir)
        IO.blocking {
          val daemons = readAntigravityDaemons(daemonDir, isProcessAlive)
          sendAntigravityMessage(httpClient, daemons, notification, timeout)
          "antigravity"
        }
      }

  /** Default combined PartialFunction dispatching OpenCode and Antigravity sessions via `orElse`. */
  def default(
      httpClient: HttpClient,
      config: IO[NotifyConfig],
      isProcessAlive: Long => Boolean = defaultIsProcessAlive
  ): NotifyDeliverer =
    openCode(httpClient, config, isProcessAlive)
      .orElse(antigravity(httpClient, config, isProcessAlive))

  def combine(deliverers: Seq[NotifyDeliverer]): NotifyDeliverer =
    deliverers.foldLeft(PartialFunction.empty[Notification, IO[String]])(_ orElse _)

  private[notify] def truncateDescription(message: String, limit: Int = descriptionLimit): String =
    val cpCount = message.codePointCount(0, message.length)
    if cpCount <= limit then message
    else
      val endIndex = message.offsetByCodePoints(0, limit - 3)
      message.substring(0, endIndex) + "..."

  private[notify] def resolveOpenCodeServiceFile(configured: String): Path =
    val trimmed = configured.trim
    if trimmed.nonEmpty then Path.of(trimmed).toAbsolutePath.normalize()
    else
      val stateBase = sys.env.get("XDG_STATE_HOME").map(_.trim).filter(_.nonEmpty)
        .map(Path.of(_))
        .getOrElse(Path.of(sys.props.getOrElse("user.home", "")).resolve(".local/state"))
      stateBase.resolve("opencode/service.json").toAbsolutePath.normalize()

  private[notify] def resolveAntigravityDaemonDir(configured: String): Path =
    val trimmed = configured.trim
    if trimmed.nonEmpty then Path.of(trimmed).toAbsolutePath.normalize()
    else Path.of(sys.props.getOrElse("user.home", "")).resolve(".gemini/antigravity/daemon").toAbsolutePath.normalize()

  private[notify] def encodeSendAgentMessageProto(
      conversationId: String,
      message: String,
      title: Option[String] = None
  ): Array[Byte] =
    val out = new ByteArrayOutputStream()
    writeLengthDelimited(out, fieldNumber = 1, message.getBytes(UTF_8))
    writeLengthDelimited(out, fieldNumber = 2, conversationId.getBytes(UTF_8))
    title.map(_.trim).filter(_.nonEmpty).foreach { t =>
      writeLengthDelimited(out, fieldNumber = 5, t.getBytes(UTF_8))
    }
    out.toByteArray

  private def writeLengthDelimited(out: ByteArrayOutputStream, fieldNumber: Int, bytes: Array[Byte]): Unit =
    writeVarint(out, (fieldNumber << 3) | 2)
    writeVarint(out, bytes.length)
    out.write(bytes)

  private def writeVarint(out: ByteArrayOutputStream, value: Int): Unit =
    var v = value
    while (v & ~0x7f) != 0 do
      out.write((v & 0x7f) | 0x80)
      v >>>= 7
    out.write(v)

  private def toJavaDuration(raw: String): JDuration =
    JDuration.ofMillis(math.max(1L, Contracts.parseDuration(raw).toMillis))

  private def readOpenCodeEndpoint(serviceFile: Path, isProcessAlive: Long => Boolean): OpenCodeEndpoint =
    if !Files.isRegularFile(serviceFile) then
      throw NotifyFailure("notify_offline", s"OpenCode service is offline (missing $serviceFile)")
    val raw =
      try Files.readString(serviceFile, UTF_8)
      catch case err: IOException => throw NotifyFailure("notify_offline", s"Failed to read OpenCode service file: ${err.getMessage}")
    val json = parse(raw).getOrElse(
      throw NotifyFailure("notify_offline", s"Malformed OpenCode service discovery JSON in $serviceFile")
    )
    val cursor = json.hcursor
    val pidOpt = cursor.get[Long]("pid").toOption
    if pidOpt.exists(pid => pid > 0L && !isProcessAlive(pid)) then
      throw NotifyFailure("notify_offline", s"OpenCode service process ${pidOpt.get} is not running")
    val url = cursor.get[String]("url").toOption.map(_.trim.stripSuffix("/")).filter(_.nonEmpty).getOrElse(
      throw NotifyFailure("notify_offline", s"OpenCode service file $serviceFile is missing 'url'")
    )
    val password = cursor.get[String]("password").toOption.filter(_.nonEmpty)
    OpenCodeEndpoint(url, password)

  private def sendOpenCodeSynthetic(
      httpClient: HttpClient,
      endpoint: OpenCodeEndpoint,
      notification: Notification,
      timeout: JDuration
  ): Unit =
    val encodedSession = URLEncoder.encode(notification.targetSessionId, UTF_8).replace("+", "%20")
    val targetUrl = s"${endpoint.baseUrl}/api/session/$encodedSession/synthetic"
    val body = Json.obj(
      "id" -> Json.fromString(s"msg_notify_${notification.id.replace("-", "")}"),
      "text" -> Json.fromString(notification.message),
      "description" -> Json.fromString(truncateDescription(notification.message)),
      "delivery" -> Json.fromString("steer"),
      "resume" -> Json.True,
      "metadata" -> Json.obj(
        "groot" -> Json.obj(
          "notificationId" -> Json.fromString(notification.id),
          "createdAt" -> Json.fromString(notification.createdAt)
        )
      )
    ).noSpaces

    val reqBuilder = HttpRequest.newBuilder(URI.create(targetUrl))
      .timeout(timeout)
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
    endpoint.password.foreach { pwd =>
      val encoded = Base64.getEncoder.encodeToString(s"opencode:$pwd".getBytes(UTF_8))
      reqBuilder.header("Authorization", s"Basic $encoded")
    }
    val req =
      if targetUrl.startsWith("http://") then reqBuilder.version(HttpClient.Version.HTTP_1_1).build()
      else reqBuilder.build()

    val resp =
      try httpClient.send(req, HttpResponse.BodyHandlers.ofString(UTF_8))
      catch
        case err: ConnectException =>
          throw NotifyFailure("notify_offline", s"OpenCode service unreachable at ${endpoint.baseUrl}: ${Option(err.getMessage).getOrElse("connection refused")}")
        case _: HttpTimeoutException =>
          throw NotifyFailure("delivery_error", s"OpenCode synthetic delivery timed out after ${timeout.toMillis}ms")
        case err: IOException =>
          throw NotifyFailure("notify_offline", s"OpenCode transport error: ${Option(err.getMessage).getOrElse(err.toString)}")

    val status = resp.statusCode()
    if (status >= 200 && status < 300) || status == 409 then ()
    else if status == 404 then
      throw NotifyFailure("notify_not_found", s"OpenCode session '${notification.targetSessionId}' not found")
    else
      throw NotifyFailure("delivery_error", s"OpenCode synthetic delivery failed (HTTP $status): ${resp.body().take(300)}")

  private def readAntigravityDaemons(daemonDir: Path, isProcessAlive: Long => Boolean): Vector[AntigravityDaemon] =
    if !Files.isDirectory(daemonDir) then
      throw NotifyFailure("notify_offline", s"Antigravity daemon directory not found: $daemonDir")
    val entries =
      try
        Using.resource(Files.list(daemonDir)) { stream =>
          stream.iterator().asScala.toVector.filter { p =>
            val name = p.getFileName.toString
            Files.isRegularFile(p) && name.startsWith("ls_") && name.endsWith(".json")
          }
        }
      catch
        case err: IOException =>
          throw NotifyFailure("notify_offline", s"Failed to scan Antigravity daemon directory: ${err.getMessage}")

    val candidates = entries.flatMap { file =>
      scala.util.Try {
        val mtime = Files.getLastModifiedTime(file).toMillis
        val raw = Files.readString(file, UTF_8)
        parse(raw).toOption.flatMap { json =>
          val c = json.hcursor
          val pidAlive = c.get[Long]("pid").toOption.forall(pid => pid <= 0L || isProcessAlive(pid))
          val portOpt = c.get[Int]("httpPort").toOption.filter(p => p > 0 && p <= 65535)
          val tokenOpt = c.get[String]("csrfToken").toOption.map(_.trim).filter(_.nonEmpty)
          for
            port <- portOpt if pidAlive
            token <- tokenOpt
          yield AntigravityDaemon(port, token, mtime)
        }
      }.toOption.flatten
    }.sortBy(d => -d.modifiedMillis)

    if candidates.isEmpty then
      throw NotifyFailure("notify_offline", "No running Antigravity language_server daemon found")
    candidates

  private def sendAntigravityMessage(
      httpClient: HttpClient,
      daemons: Vector[AntigravityDaemon],
      notification: Notification,
      timeout: JDuration
  ): Unit =
    val protoBytes = encodeSendAgentMessageProto(notification.targetSessionId, notification.message)
    var bestError: Option[NotifyFailure] = None
    var delivered = false
    val iter = daemons.iterator

    while iter.hasNext && !delivered do
      val daemon = iter.next()
      val targetUrl = s"http://127.0.0.1:${daemon.httpPort}/exa.language_server_pb.LanguageServerService/SendAgentMessage"
      val req = HttpRequest.newBuilder(URI.create(targetUrl))
        .version(HttpClient.Version.HTTP_1_1)
        .timeout(timeout)
        .header("Content-Type", "application/proto")
        .header("Connect-Protocol-Version", "1")
        .header("x-codeium-csrf-token", daemon.csrfToken)
        .POST(HttpRequest.BodyPublishers.ofByteArray(protoBytes))
        .build()

      try
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
        val status = resp.statusCode()
        if status >= 200 && status < 300 then
          delivered = true
        else if status == 404 then
          val notFound = NotifyFailure("notify_not_found", s"Antigravity conversation '${notification.targetSessionId}' not found")
          bestError = Some(bestError.filter(_.code == "delivery_error").getOrElse(notFound))
        else
          val detail = new String(resp.body(), UTF_8).take(300)
          bestError = Some(NotifyFailure("delivery_error", s"Antigravity SendAgentMessage failed (HTTP $status): $detail"))
      catch
        case _: ConnectException =>
          if bestError.isEmpty then
            bestError = Some(NotifyFailure("notify_offline", s"Antigravity daemon unreachable on port ${daemon.httpPort}"))
        case _: HttpTimeoutException =>
          bestError = Some(NotifyFailure("delivery_error", s"Antigravity SendAgentMessage timed out after ${timeout.toMillis}ms"))
        case err: IOException =>
          if bestError.isEmpty then
            bestError = Some(NotifyFailure("notify_offline", s"Antigravity transport error: ${Option(err.getMessage).getOrElse(err.toString)}"))

    if !delivered then
      throw bestError.getOrElse(NotifyFailure("notify_offline", "No reachable Antigravity language_server daemon"))
