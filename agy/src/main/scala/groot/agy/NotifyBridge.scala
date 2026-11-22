package groot.agy

import io.circe.jawn.parse
import java.io.File
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/**
 * Antigravity Sidecar bridge: subscribes to Groot Server's `/notify/subscribe` SSE stream
 * and forwards UUID-targeted notifications to Antigravity conversations via `agentapi send-message`.
 */
final class NotifyBridge(
    transport: HttpTransport,
    deliverFn: (String, String) => Either[String, Unit] = NotifyBridge.deliverViaAgentApi,
    sleepFn: Long => Unit = Thread.sleep,
    shouldContinue: () => Boolean = () => true,
    logErr: String => Unit = msg => System.err.println(s"[groot-agy notify-bridge] $msg")
):
  def runLoop(): Unit =
    var backoffMs = 500L
    while shouldContinue() do
      var sawReady = false
      try
        val resp = transport.send(
          HttpRequest(
            method = "GET",
            path = "/notify/subscribe",
            headers = Vector("Accept" -> "text/event-stream"),
            allowStreaming = true
          ),
          onEvent = raw => handleEvent(raw, () => { sawReady = true; backoffMs = 500L })
        )
        if !resp.isSuccess then
          logErr(s"subscribe failed (HTTP ${resp.status}): ${resp.body.take(300)}")
      catch
        case NonFatal(err) =>
          if shouldContinue() then logErr(s"stream error: ${Option(err.getMessage).getOrElse(err.toString)}")

      if shouldContinue() then
        sleepFn(backoffMs)
        backoffMs = if sawReady then 500L else math.min(backoffMs * 2L, 15000L)

  private[agy] def handleEvent(raw: String, onReady: () => Unit = () => ()): Unit =
    parse(raw) match
      case Left(err) =>
        logErr(s"invalid SSE JSON: ${err.message}")
      case Right(json) =>
        val cursor = json.hcursor
        cursor.get[String]("kind").toOption match
          case Some("ready") => onReady()
          case Some("heartbeat") => ()
          case Some("message") =>
            val notif = cursor.downField("notification")
            val target = notif.get[String]("targetSessionId").toOption.map(_.trim).getOrElse("")
            val message = notif.get[String]("message").toOption.getOrElse("")
            if NotifyBridge.isAntigravitySession(target) && message.nonEmpty then
              deliverWithRetry(target, message)
          case other =>
            logErr(s"unexpected notify event kind: ${other.getOrElse("<missing>")}")

  private def deliverWithRetry(conversationId: String, message: String): Unit =
    var attempt = 0
    var delivered = false
    while attempt < 3 && !delivered && shouldContinue() do
      deliverFn(conversationId, message) match
        case Right(_) => delivered = true
        case Left(err) =>
          logErr(s"deliver attempt ${attempt + 1} failed for $conversationId: $err")
          attempt += 1
          if attempt < 3 && shouldContinue() then sleepFn(250L * (1L << (attempt - 1)))

object NotifyBridge:
  private val uuidPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  def isAntigravitySession(sessionId: String): Boolean =
    uuidPattern.matches(sessionId)

  def resolveAgentApiCommand(): String =
    sys.env.get("ANTIGRAVITY_AGENTAPI_BIN").filter(_.nonEmpty)
      .orElse {
        val home = sys.props.getOrElse("user.home", "")
        val candidate = new File(s"$home/.gemini/antigravity/bin/agentapi")
        if candidate.isFile && candidate.canExecute then Some(candidate.getAbsolutePath) else None
      }
      .getOrElse("agentapi")

  def deliverViaAgentApi(conversationId: String, message: String): Either[String, Unit] =
    try
      val cmd = resolveAgentApiCommand()
      val pb = new ProcessBuilder(cmd, "send-message", conversationId, message)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val out = scala.io.Source.fromInputStream(proc.getInputStream, "UTF-8").mkString
      val finished = proc.waitFor(15L, TimeUnit.SECONDS)
      if !finished then
        proc.destroyForcibly()
        Left("agentapi send-message timed out after 15s")
      else if proc.exitValue() != 0 then
        Left(s"exit=${proc.exitValue()} output=${out.take(400)}")
      else
        Right(())
    catch
      case NonFatal(err) => Left(Option(err.getMessage).getOrElse(err.toString))
