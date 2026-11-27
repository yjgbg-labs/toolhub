package groot.adb

import framework.model.JsonModel
import framework.validation.*

object AdbValidation:
  def isValidEndpoint(raw: String): Boolean =
    raw != null && parseHostPort(raw).isRight

  def isValidAliasOrEndpoint(raw: String): Boolean =
    if raw == null then false
    else
      val t = raw.trim
      t.nonEmpty && (t.matches("[A-Za-z0-9._-]+") || parseHostPort(t).isRight)

  def parseHostPort(raw: String, defaultPort: Int = 5555): Either[String, (String, Int)] =
    val s = Option(raw).map(_.trim).getOrElse("")
    if s.isEmpty then Left("target must not be empty")
    else if s.startsWith("[") then
      val closeIdx = s.indexOf(']')
      if closeIdx <= 1 then Left(s"Invalid IPv6 target '$s'")
      else
        val host = s.substring(1, closeIdx)
        val rest = s.substring(closeIdx + 1)
        if rest.isEmpty then validateHostAndPort(host, defaultPort)
        else if rest.startsWith(":") then
          rest.substring(1).toIntOption match
            case Some(p) => validateHostAndPort(host, p)
            case None    => Left(s"Invalid port in target '$s'")
        else Left(s"Invalid IPv6 target '$s'")
    else
      val firstColon = s.indexOf(':')
      val lastColon = s.lastIndexOf(':')
      if firstColon < 0 then validateHostAndPort(s, defaultPort)
      else if firstColon == lastColon then
        val host = s.substring(0, firstColon).trim
        val portStr = s.substring(firstColon + 1).trim
        portStr.toIntOption match
          case Some(p) => validateHostAndPort(host, p)
          case None    => Left(s"Invalid port in target '$s'")
      else
        // Bare IPv6 literal without port
        validateHostAndPort(s, defaultPort)

  private def validateHostAndPort(host: String, port: Int): Either[String, (String, Int)] =
    if !Contracts.isHost(host) then Left(s"Invalid host '$host'")
    else if port < 1 || port > 65535 then Left(s"Port out of range 1..65535: $port")
    else Right((host, port))

final case class AdbTargetEndpoint(
    @contract[String](AdbValidation.isValidEndpoint, "must be a valid host or host:port (1..65535)")
    address: String
) derives JsonModel, Validator

@contract[AdbConfig](
  cfg =>
    cfg.targets.forall { (alias, addr) =>
      alias.matches("[A-Za-z0-9._-]+") && AdbValidation.isValidEndpoint(addr)
    },
  "adb.targets entries must map alphanumeric alias names to valid host or host:port endpoints"
)
final case class AdbConfig(
    targets: Map[String, String] = Map.empty,
    @contract[String](s => s != null && !s.contains('\u0000'), "must not contain NUL bytes")
    keyFile: String = sys.env.getOrElse("ADB_KEY_FILE", "").trim,
    @duration connectTimeout: String = "5s",
    @duration authTimeout: String = "20s",
    @duration commandTimeout: String = "30s",
    @duration idleTimeout: String = "30s",
    @range(4096L, 67108864L) maxOutputBytes: Int = 4 * 1024 * 1024,
    @range(4096L, 536870912L) maxTransferBytes: Long = 256L * 1024 * 1024
) derives JsonModel, Validator

final case class AdbFailure(code: String, message: String) extends RuntimeException(message)

enum ScreenAction derives JsonModel:
  case status, on, off, toggle

enum InputKind derives JsonModel:
  case tap, swipe, text

enum RebootMode derives JsonModel:
  case system, recovery, bootloader

final case class AdbDeviceSession(
    address: String,
    alias: Option[String] = None,
    connected: Boolean,
    banner: Option[String] = None,
    maxPayload: Option[Int] = None,
    connectedAt: Option[String] = None,
    lastUsedAt: Option[String] = None
) derives JsonModel

final case class AdbDevicesResult(
    keyPath: String,
    publicKeyFingerprint: String,
    devices: Vector[AdbDeviceSession]
) derives JsonModel

final case class DeviceInfo(
    address: String,
    alias: Option[String] = None,
    banner: String,
    protocolVersion: Int,
    maxPayload: Int,
    manufacturer: Option[String] = None,
    brand: Option[String] = None,
    model: Option[String] = None,
    device: Option[String] = None,
    product: Option[String] = None,
    serial: Option[String] = None,
    androidVersion: Option[String] = None,
    sdkInt: Option[Int] = None,
    screenState: String,
    wakefulness: Option[String] = None,
    uptime: Option[String] = None
) derives JsonModel

final case class ExecRequest(
    target: String,
    command: String,
    timeout: Option[String] = None
) derives JsonModel

final case class ExecResult(
    address: String,
    command: String,
    output: String,
    outputBytes: Int,
    truncated: Boolean,
    durationMs: Long
) derives JsonModel

final case class KeyEventRequest(
    target: String,
    keys: Vector[String],
    longPress: Boolean = false,
    allowPowerOff: Boolean = false
) derives JsonModel

final case class KeyEventResult(
    address: String,
    resolvedKeyCodes: Vector[Int],
    longPress: Boolean,
    durationMs: Long
) derives JsonModel

final case class InputRequest(
    target: String,
    kind: InputKind,
    x: Option[Int] = None,
    y: Option[Int] = None,
    x2: Option[Int] = None,
    y2: Option[Int] = None,
    durationMs: Option[Int] = None,
    text: Option[String] = None
) derives JsonModel

final case class InputResult(
    address: String,
    kind: InputKind,
    durationMs: Long
) derives JsonModel

final case class ScreenRequest(
    target: String,
    mode: ScreenAction = ScreenAction.status
) derives JsonModel

final case class ScreenResult(
    address: String,
    previousState: String,
    currentState: String,
    changed: Boolean,
    wakefulness: Option[String] = None
) derives JsonModel

final case class ScreencapRequest(
    target: String,
    outputPath: Option[String] = None,
    includeBase64: Boolean = false
) derives JsonModel

final case class ScreencapResult(
    address: String,
    sizeBytes: Int,
    savedPath: Option[String] = None,
    pngBase64: Option[String] = None,
    width: Option[Int] = None,
    height: Option[Int] = None
) derives JsonModel

final case class SyncStatResult(
    address: String,
    remotePath: String,
    exists: Boolean,
    mode: Int,
    sizeBytes: Long,
    mtimeEpochSeconds: Long,
    isDirectory: Boolean,
    isRegularFile: Boolean,
    isSymlink: Boolean
) derives JsonModel

final case class SyncDirEntry(
    name: String,
    mode: Int,
    sizeBytes: Long,
    mtimeEpochSeconds: Long,
    isDirectory: Boolean,
    isRegularFile: Boolean,
    isSymlink: Boolean
) derives JsonModel

final case class SyncListResult(
    address: String,
    remotePath: String,
    entries: Vector[SyncDirEntry]
) derives JsonModel

final case class PushRequest(
    target: String,
    localPath: String,
    remotePath: String,
    mode: Option[Int] = None
) derives JsonModel

final case class PushResult(
    address: String,
    localPath: String,
    remotePath: String,
    sizeBytes: Long,
    durationMs: Long
) derives JsonModel

final case class PullRequest(
    target: String,
    remotePath: String,
    localPath: Option[String] = None
) derives JsonModel

final case class PullResult(
    address: String,
    remotePath: String,
    savedPath: Option[String] = None,
    sizeBytes: Long,
    contentBase64: Option[String] = None,
    durationMs: Long
) derives JsonModel

final case class InstallRequest(
    target: String,
    apkPath: String,
    replace: Boolean = true,
    allowDowngrade: Boolean = false,
    grantPermissions: Boolean = false
) derives JsonModel

final case class InstallResult(
    address: String,
    apkPath: String,
    sizeBytes: Long,
    output: String,
    durationMs: Long
) derives JsonModel

final case class UninstallRequest(
    target: String,
    packageName: String,
    keepData: Boolean = false
) derives JsonModel

final case class UninstallResult(
    address: String,
    packageName: String,
    keepData: Boolean,
    output: String,
    durationMs: Long
) derives JsonModel

final case class RebootRequest(
    target: String,
    mode: RebootMode = RebootMode.system,
    confirm: Boolean = false
) derives JsonModel

final case class RebootResult(
    address: String,
    mode: RebootMode,
    accepted: Boolean
) derives JsonModel
