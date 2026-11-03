package groot.wine

import framework.model.JsonModel
import io.circe.Json
import java.nio.file.Path

case class WineError(code: String, message: String, stdout: String = "", stderr: String = "",
    durationMs: Long = 0, step: Option[Int] = None) derives JsonModel
case class WineFailure(error: WineError) extends RuntimeException(error.message)
object WineFailure:
  def apply(code: String, message: String): WineFailure = WineFailure(WineError(code, message))

case class WineExec(exe: String, args: Vector[String] = Vector.empty, cwd: Option[String] = None,
    env: Map[String, String] = Map.empty, stdinBase64: String = "", timeoutMs: Int = 30000,
    runtime: String = "wine", architecture: String = "x64") derives JsonModel
case class WineArgument(`type`: String, value: Json = Json.Null, ref: Option[String] = None,
    size: Option[Int] = None) derives JsonModel
case class WineStep(member: String = "", args: Vector[WineArgument] = Vector.empty, operation: String = "invoke",
    typeName: Option[String] = None, target: Option[String] = None, returnType: String = "void",
    callingConvention: String = "winapi", saveAs: Option[String] = None, size: Option[Int] = None) derives JsonModel
case class WineCall(kind: String, path: Option[String] = None, classId: Option[String] = None,
    architecture: String = "x64", runtime: String = "dotnet", member: String = "",
    args: Vector[WineArgument] = Vector.empty, operation: String = "invoke", typeName: Option[String] = None,
    target: Option[String] = None, returnType: String = "void", callingConvention: String = "winapi",
    steps: Vector[WineStep] = Vector.empty, cwd: Option[String] = None, env: Map[String, String] = Map.empty,
    timeoutMs: Int = 30000) derives JsonModel:
  def single: WineStep = WineStep(member, args, operation, typeName, target, returnType, callingConvention)

case class WineExecResult(exitCode: Int, stdout: String, stderr: String, durationMs: Long,
    stdoutTruncated: Boolean, stderrTruncated: Boolean) derives JsonModel
case class WineOutput(index: Int, base64: String) derives JsonModel
case class WineStepResult(value: Json, outputs: Vector[WineOutput] = Vector.empty) derives JsonModel
case class WineCallResult(value: Json, outputs: Vector[WineOutput], steps: Vector[WineStepResult],
    stdout: String, stderr: String, durationMs: Long, stdoutTruncated: Boolean, stderrTruncated: Boolean) derives JsonModel

private[wine] object WineValidation:
  val maxBytes = 1024 * 1024
  def require(condition: Boolean, message: String): Unit =
    if !condition then throw WineFailure("invalid_wine", message)
  def windows(value: String): String =
    require(value.nonEmpty && !value.contains('\u0000') && value.length <= 32768, "Invalid Windows path")
    if value.startsWith("/") then "Z:" + Path.of(value).toAbsolutePath.normalize().toString.replace('/', '\\') else value
