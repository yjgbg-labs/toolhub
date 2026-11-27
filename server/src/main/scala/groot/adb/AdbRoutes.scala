package groot.adb

import framework.http.ErrorBody
import framework.model.JsonModel
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import io.circe.Json

object AdbRoutes:
  private def enumCodec[A: JsonModel]: ParamCodec[A] = ParamCodec.scalar { value =>
    JsonModel[A].decoder.decodeJson(Json.fromString(value)).left.map(_.message)
  }
  private given ParamCodec[ScreenAction] = enumCodec[ScreenAction]
  private given ParamCodec[RebootMode] = enumCodec[RebootMode]

  def register(service: AdbService)(using CommandsScope): Unit = commands("adb") {
    description("Control Android and Android TV devices over native ADB TCP wire protocol without external adb binaries or local shells")
    exposeOn(cli, codemode, graaljs)

    val invalid = response.json[ErrorBody](400) { description("Invalid ADB target, keycode, path or request parameter") }
    val unauth = response.json[ErrorBody](401) { description("Device has not authorized Groot's ADB RSA key yet") }
    val forbidden = response.json[ErrorBody](403) { description("Remote device permission denied or read-only target") }
    val missing = response.json[ErrorBody](404) { description("Local or remote file path not found") }
    val large = response.json[ErrorBody](413) { description("Command output or file transfer exceeds configured byte limit") }
    val upstream = response.json[ErrorBody](502) { description("ADB daemon unreachable, protocol error or remote command failure") }
    val timedOut = response.json[ErrorBody](504) { description("ADB TCP connection, authorization or stream timed out") }

    recover { case e: AdbFailure =>
      val b = ErrorBody(e.code, e.getMessage)
      e.code match
        case "invalid_adb" | "invalid_adb_key" => invalid(b)
        case "adb_unauthorized"                => unauth(b)
        case "adb_forbidden"                   => forbidden(b)
        case "adb_not_found"                   => missing(b)
        case "adb_limit"                       => large(b)
        case "adb_timeout"                     => timedOut(b)
        case _                                 => upstream(b)
    }

    command[AdbDevicesResult]("devices") {
      description("List configured ADB target aliases, active pooled TCP connections and RSA public key fingerprint")
      handle { service.devices }
    }

    command[ConnectResult]("connect") {
      description("Connect or reconnect to an ADB TCP daemon (configured alias or host[:port], default port 5555) and perform CNXN/AUTH handshake")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val waitForAuth = param[Boolean]("waitForAuth", "Wait up to authTimeout for on-screen USB/network debugging approval") { default(false) }
      handle { service.connect(ConnectRequest(target.value, waitForAuth.value)) }
    }

    command[DisconnectResult]("disconnect") {
      description("Close a pooled ADB TCP connection by target/alias or close all pooled connections")
      val target = param[Option[String]]("target", "Target alias or host[:port]; required unless all=true")
      val all = param[Boolean]("all", "Close every pooled ADB TCP connection") { default(false) }
      handle { service.disconnect(target.value, all.value) }
    }

    command[DeviceInfo]("info") {
      description("Inspect live Android device metadata, OS/SDK version, screen ON/OFF state, wakefulness and uptime")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      handle { service.info(target.value) }
    }

    command[ExecResult]("exec") {
      description("Execute a remote command over an ADB 'shell:' stream directly on the device (zero local shell spawning)")
      val input = body[ExecRequest]("Required target, remote command string and optional per-call timeout")
      handle { service.exec(input.value) }
    }

    command[KeyEventResult]("keyevent") {
      description("Send one or more Android keyevents by name (e.g. SLEEP, WAKEUP, HOME, BACK, DPAD_UP, VOLUME_UP) or numeric keycode")
      val input = body[KeyEventRequest]("Required target, key names or numeric keycodes (1..32), optional longPress, and allowPowerOff guard for KEYCODE_POWER (26)")
      handle { service.keyevent(input.value) }
    }

    command[InputResult]("input") {
      description("Send touch tap, swipe gesture or escaped single-line text input to an Android device")
      val input = body[InputRequest]("Required target, input kind (tap, swipe, text), and coordinates/duration or text payload")
      handle { service.input(input.value) }
    }

    command[ScreenResult]("screen") {
      description("Query or switch Android / TV screen display state safely using WAKEUP (224) and SLEEP (223)")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val mode = param[ScreenAction]("mode", "Screen action: status, on, off, or toggle") { default(ScreenAction.status) }
      handle { service.screen(ScreenRequest(target.value, mode.value)) }
    }

    command[ScreencapResult]("screencap") {
      description("Capture a bit-exact PNG screenshot from the device via binary ADB sync transfer and save to disk and/or return Base64")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val outputPath = param[Option[String]]("outputPath", "Optional local destination file path")
      val includeBase64 = param[Boolean]("includeBase64", "Include PNG bytes as Base64 in response") { default(false) }
      handle { service.screencap(ScreencapRequest(target.value, outputPath.value, includeBase64.value)) }
    }

    command[SyncStatResult]("stat") {
      description("Stat a remote path on the device using the native binary ADB sync STAT protocol")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val remotePath = param[String]("remotePath", "Absolute path on the Android device starting with '/'") { minLength(1) }
      handle { service.stat(target.value, remotePath.value) }
    }

    command[SyncListResult]("ls") {
      description("List directory entries on the device using the native binary ADB sync LIST protocol")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val remotePath = param[String]("remotePath", "Absolute directory path on the Android device starting with '/'") { minLength(1) }
      handle { service.ls(target.value, remotePath.value) }
    }

    command[PushResult]("push") {
      description("Upload a local file to the Android device using the native binary ADB sync SEND protocol")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val localPath = param[String]("localPath", "Local source file path") { minLength(1) }
      val remotePath = param[String]("remotePath", "Absolute remote destination path starting with '/'") { minLength(1) }
      val mode = param[Option[Int]]("mode", "Optional POSIX file mode in decimal (default 420 / 0644)")
      handle { service.push(PushRequest(target.value, localPath.value, remotePath.value, mode.value)) }
    }

    command[PullResult]("pull") {
      description("Download a remote file from the Android device using the native binary ADB sync RECV protocol (to disk or inline Base64 <= 256 KiB)")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val remotePath = param[String]("remotePath", "Absolute remote file path starting with '/'") { minLength(1) }
      val localPath = param[Option[String]]("localPath", "Optional local destination file path (required when > 256 KiB)")
      handle { service.pull(PullRequest(target.value, remotePath.value, localPath.value)) }
    }

    command[InstallResult]("install") {
      description("Push a local APK via native ADB sync and install it via Package Manager")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val apkPath = param[String]("apkPath", "Local APK file path") { minLength(1) }
      val replace = param[Boolean]("replace", "Reinstall existing app keeping its data (-r)") { default(true) }
      val allowDowngrade = param[Boolean]("allowDowngrade", "Allow version code downgrade (-d)") { default(false) }
      val grantPermissions = param[Boolean]("grantPermissions", "Grant all runtime permissions (-g)") { default(false) }
      handle { service.install(InstallRequest(target.value, apkPath.value, replace.value, allowDowngrade.value, grantPermissions.value)) }
    }

    command[UninstallResult]("uninstall") {
      description("Uninstall an Android package from the target device")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val packageName = param[String]("packageName", "Android package name (e.g. com.example.app)") { minLength(3) }
      val keepData = param[Boolean]("keepData", "Keep app data and cache directories (-k)") { default(false) }
      handle { service.uninstall(UninstallRequest(target.value, packageName.value, keepData.value)) }
    }

    command[RebootResult]("reboot") {
      description("Reboot the target Android device into system, recovery or bootloader mode via native ADB 'reboot:' service")
      val target = param[String]("target", "Target alias or host[:port]") { minLength(1) }
      val mode = param[RebootMode]("mode", "Reboot mode: system, recovery, or bootloader") { default(RebootMode.system) }
      val confirm = param[Boolean]("confirm", "Must be set to true to execute reboot") { default(false) }
      handle { service.reboot(RebootRequest(target.value, mode.value, confirm.value)) }
    }
  }
