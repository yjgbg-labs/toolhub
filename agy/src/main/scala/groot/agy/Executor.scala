package groot.agy

import io.circe.{Json, JsonObject}
import java.util.concurrent.atomic.AtomicReference

final case class ExecutionResult(
    taskId: String,
    state: String,
    output: String,
    isError: Boolean,
    errorCode: Option[String],
    errorMessage: Option[String],
    logPath: Option[String],
    cpuTimeMs: Option[Double],
    elapsedTimeMs: Option[Double]
)

object Executor:
  val defaultTimeout = "30s"
  val defaultCpu = "5s"
  val maxOutputChars = 65536
  private val durationPattern = "^\\d+(ms|s|m|h|d)$".r

  def wrapScript(script: String, context: Option[Json]): String =
    val ctxLiteral = context.filter(_.isObject).map(_.noSpaces).getOrElse("{}")
    s"""const ctx = Object.freeze($ctxLiteral);
       |const __groot_res = await (async () => {
       |$script
       |})();
       |if (__groot_res !== undefined) {
       |  console.log(typeof __groot_res === "string" ? __groot_res : JSON.stringify(__groot_res));
       |}""".stripMargin

  def normalizeDuration(name: String, value: Option[String], defaultValue: String): String =
    val text = value.map(_.trim).filter(_.nonEmpty).getOrElse(defaultValue)
    require(durationPattern.matches(text), s"$name must be a positive integer followed by ms, s, m, h or d (e.g. 30s)")
    text

  def formatResult(result: ExecutionResult): String =
    val sb = new StringBuilder
    if result.output.nonEmpty then
      sb.append(result.output)
    if result.isError then
      if sb.nonEmpty && !sb.endsWith("\n") then sb.append('\n')
      val code = result.errorCode.getOrElse(result.state)
      val msg = result.errorMessage.getOrElse(s"Task ended in state ${result.state}")
      sb.append(s"[error] $code: $msg")
    else if sb.isEmpty then
      sb.append("(completed with no output)")
    sb.toString

final class Executor(transport: HttpTransport, defaultCpu: String = Executor.defaultCpu, maxOutputChars: Int = Executor.maxOutputChars):
  def run(
      script: String,
      timeout: Option[String] = None,
      cpu: Option[String] = None,
      context: Option[Json] = None,
      onTaskStarted: String => Unit = _ => ()
  ): ExecutionResult =
    require(script.trim.nonEmpty, "graalScript must not be blank")
    val effectiveTimeout = Executor.normalizeDuration("timeout", timeout, Executor.defaultTimeout)
    val effectiveCpu = Executor.normalizeDuration("cpu", cpu, defaultCpu)
    val wrapped = Executor.wrapScript(script, context)

    val startResp = transport.send(HttpRequest(
      method = "POST",
      path = "/graaljs/start",
      query = Vector("cpu" -> effectiveCpu, "timeout" -> effectiveTimeout),
      headers = Vector("Content-Type" -> "text/plain", "Accept" -> "application/json"),
      body = Some(wrapped)
    ))
    if !startResp.isSuccess then
      throw new RuntimeException(s"graaljs.start failed (HTTP ${startResp.status}): ${startResp.body}")

    val startedTask = Catalog.parse(startResp.body)
    val taskId = Catalog.text(startedTask, "id")
    onTaskStarted(taskId)

    val output = new StringBuilder
    var truncated = false
    val followResp = transport.send(
      HttpRequest(
        method = "GET",
        path = "/graaljs/logsFollow",
        query = Vector("id" -> taskId),
        headers = Vector("Accept" -> "text/event-stream"),
        allowStreaming = true
      ),
      onEvent = line => {
        if !truncated then
          if output.length + line.length + 1 <= maxOutputChars then
            if output.nonEmpty then output.append('\n')
            output.append(line)
          else
            val remaining = maxOutputChars - output.length
            if remaining > 1 then
              if output.nonEmpty then output.append('\n')
              output.append(line.take(remaining - 1))
            truncated = true
      }
    )
    if !followResp.isSuccess then
      throw new RuntimeException(s"graaljs.logsFollow failed (HTTP ${followResp.status}): ${followResp.body}")

    val statusResp = transport.send(HttpRequest(
      method = "GET",
      path = "/graaljs/status",
      query = Vector("id" -> taskId),
      headers = Vector("Accept" -> "application/json")
    ))
    if !statusResp.isSuccess then
      throw new RuntimeException(s"graaljs.status failed (HTTP ${statusResp.status}): ${statusResp.body}")

    val task = Catalog.parse(statusResp.body)
    val state = Catalog.text(task, "state")
    val logPath = Catalog.field(task, "logPath").flatMap(_.asString)
    val errorObj = Catalog.field(task, "error").flatMap(_.asObject)
    val errorCode = errorObj.flatMap(_("code")).flatMap(_.asString)
    val errorMessage = errorObj.flatMap(_("message")).flatMap(_.asString)
    val cpuMs = Catalog.field(task, "cpuTimeMs").flatMap(_.asNumber).map(_.toDouble)
    val elapsedMs = Catalog.field(task, "elapsedTimeMs").flatMap(_.asNumber).map(_.toDouble)

    // Strip the trailing server-appended `[graaljs] state=...` summary line so output stays clean.
    val cleaned = stripSummaryTrailer(output.toString)
    val finalOutput =
      if truncated then
        val hint = logPath.map(p => s"\n...[output truncated at $maxOutputChars chars; full log: $p]").getOrElse(s"\n...[output truncated at $maxOutputChars chars]")
        cleaned + hint
      else cleaned

    ExecutionResult(
      taskId = taskId,
      state = state,
      output = finalOutput,
      isError = state != "succeeded",
      errorCode = errorCode,
      errorMessage = errorMessage,
      logPath = logPath,
      cpuTimeMs = cpuMs,
      elapsedTimeMs = elapsedMs
    )

  def cancel(taskId: String): Unit =
    scala.util.Try {
      transport.send(HttpRequest(
        method = "POST",
        path = "/graaljs/cancel",
        query = Vector("id" -> taskId),
        headers = Vector("Accept" -> "application/json")
      ))
    }
    ()

  private def stripSummaryTrailer(text: String): String =
    val lines = text.split("\n", -1).toVector
    var end = lines.size
    while end > 0 && lines(end - 1).trim.isEmpty do end -= 1
    if end > 0 && lines(end - 1).startsWith("[graaljs] state=") then
      end -= 1
      if end > 0 && lines(end - 1).startsWith("[graaljs] ") then end -= 1
      while end > 0 && lines(end - 1).trim.isEmpty do end -= 1
    lines.take(end).mkString("\n")
