package groot.wine

import framework.model.JsonModel
import io.circe.Json
import java.nio.file.{Files, Path}

case class WineFailure(code: String, message: String) extends RuntimeException(message)
case class WineInstance(instanceName: String, status: String, arch: String, headless: Boolean, prefixPath: String) derives JsonModel
case class WineDll(instanceName: String, dllName: String, kind: String, architecture: String, runtime: String, status: String,
    path: Option[String], classId: Option[String], logPath: String) derives JsonModel
case class WineTask(id: String, instanceName: String, state: String, exe: String, createdAt: String,
    finishedAt: Option[String], exitCode: Option[Long], error: Option[String], stdoutPath: String, stderrPath: String) derives JsonModel:
  def terminal: Boolean = state != "running"
case class WineExec(exe: String, args: Vector[String] = Vector.empty, cwd: Option[String] = None,
    env: Map[String, String] = Map.empty, stdinBase64: String = "", timeoutMs: Int = 300000,
    architecture: String = "x64") derives JsonModel
case class WineLoad(kind: String, path: Option[String] = None, classId: Option[String] = None,
    architecture: String = "x64", runtime: String = "dotnet") derives JsonModel
case class WineArgument(`type`: String, value: Json = Json.Null, size: Option[Int] = None) derives JsonModel
case class WineCall(member: String = "", args: Vector[WineArgument] = Vector.empty, operation: String = "invoke",
    typeName: Option[String] = None, target: Option[String] = None, returnType: String = "void",
    callingConvention: String = "winapi") derives JsonModel
private[wine] case class SavedWine(instanceName: String, arch: String, headless: Boolean,
    displayPid: Option[Long] = None, displayStartedAt: Option[String] = None) derives JsonModel

private[wine] object WineValidation:
  def name(value: String): Unit =
    if !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") then throw WineFailure("invalid_wine", "Names must be 1–64 ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit")
  def timeout(ms: Int): Unit =
    if ms < 1 || ms > 86400000 then throw WineFailure("invalid_wine", "timeoutMs must be between 1 and 86400000")
  def architecture(value: String): Unit =
    if !Set("x64", "x86").contains(value) then throw WineFailure("invalid_wine", "architecture must be x64 or x86")
  def windows(value: String): String =
    if value.isEmpty || value.contains('\u0000') || value.length > 32768 then throw WineFailure("invalid_wine", "Invalid Windows path")
    if value.startsWith("/") then "Z:" + Path.of(value).toAbsolutePath.normalize().toString.replace('/', '\\') else value
  def local(value: String): Path =
    val p = Path.of(value)
    if !p.isAbsolute || !Files.exists(p) then throw WineFailure("invalid_wine", "Local path must be absolute and exist")
    p
