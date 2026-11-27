package groot.adb

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object AdbRoutes:
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
      description("List configured ADB target aliases, active pooled TCP connections, default target and RSA public key fingerprint")
      handle { service.devices }
    }

    command[ConnectResult]("connect") {
      description("Connect or reconnect to an ADB TCP daemon (host[:port] or configured alias, default port 5555) and perform CNXN/AUTH handshake")
      val input = body[ConnectRequest]("Optional target (alias or host[:port]), waitForAuth to wait for on-screen dialog, and setDefault flag")
      handle { service.connect(input.value) }
    }

    command[DisconnectResult]("disconnect") {
      description("Close a pooled ADB TCP connection by target/alias or close all pooled connections")
      val target = param[Option[String]]("target", "Target alias or host[:port]; defaults to active default device when all=false")
      val all = param[Boolean]("all", "Close every pooled ADB TCP connection") { default(false) }
      handle { service.disconnect(target.value, all.value) }
    }

    command[DeviceInfo]("info") {
      description("Inspect live Android device metadata, OS/SDK version, screen ON/OFF state, wakefulness and uptime")
      val target = param[Option[String]]("target", "Optional target alias or host[:port]")
      handle { service.info(target.value) }
    }

    command[ExecResult]("exec") {
      description("Execute a remote command over an ADB 'shell:' stream directly on the device (zero local shell spawning)")
      val input = body[ExecRequest]("Remote command string, optional target and optional per-call timeout")
      handle { service.exec(input.value) }
    }

    command[KeyEventResult]("keyevent") {
      description("Send one or more Android keyevents by name (e.g. SLEEP, WAKEUP, HOME, BACK, DPAD_UP, VOLUME_UP) or numeric keycode")
      val input = body[KeyEventRequest]("Key names or numeric keycodes (1..32), optional longPress, allowPowerOff guard for KEYCODE_POWER (26), and target")
      handle { service.keyevent(input.value) }
    }

    command[InputResult]("input") {
      description("Send touch tap, swipe gesture or escaped single-line text input to an Android device")
      val input = body[InputRequest]("Input kind (tap, swipe, text), coordinates/duration or text payload, and optional target")
      handle { service.input(input.value) }
    }

    command[ScreenResult]("screen") {
      description("Query or switch Android / TV screen display state safely using WAKEUP (224) and SLEEP (223)")
      val input = body[ScreenRequest]("Screen action (status, on, off, toggle) and optional target")
      handle { service.screen(input.value) }
    }

    command[ScreencapResult]("screencap") {
      description("Capture a bit-exact PNG screenshot from the device via binary ADB sync transfer and save to disk and/or return Base64")
      val input = body[ScreencapRequest]("Destination outputPath and/or includeBase64=true, plus optional target")
      handle { service.screencap(input.value) }
    }

    command[SyncStatResult]("stat") {
      description("Stat a remote path on the device using the native binary ADB sync STAT protocol")
      val remotePath = param[String]("remotePath", "Absolute path on the Android device starting with '/'") { minLength(1) }
      val target = param[Option[String]]("target", "Optional target alias or host[:port]")
      handle { service.stat(remotePath.value, target.value) }
    }

    command[SyncListResult]("ls") {
      description("List directory entries on the device using the native binary ADB sync LIST protocol")
      val remotePath = param[String]("remotePath", "Absolute directory path on the Android device starting with '/'") { minLength(1) }
      val target = param[Option[String]]("target", "Optional target alias or host[:port]")
      handle { service.ls(remotePath.value, target.value) }
    }

    command[PushResult]("push") {
      description("Upload a local file to the Android device using the native binary ADB sync SEND protocol")
      val input = body[PushRequest]("Local source file path, absolute remote destination path, optional POSIX mode (default 420 / 0644) and target")
      handle { service.push(input.value) }
    }

    command[PullResult]("pull") {
      description("Download a remote file from the Android device using the native binary ADB sync RECV protocol (to disk or inline Base64 <= 256 KiB)")
      val input = body[PullRequest]("Absolute remote file path, optional local destination file path, and optional target")
      handle { service.pull(input.value) }
    }

    command[InstallResult]("install") {
      description("Push a local APK via native ADB sync and install it via Package Manager")
      val input = body[InstallRequest]("Local APK path, optional target, replace (-r), allowDowngrade (-d) and grantPermissions (-g) flags")
      handle { service.install(input.value) }
    }

    command[UninstallResult]("uninstall") {
      description("Uninstall an Android package from the target device")
      val input = body[UninstallRequest]("Android package name, optional keepData (-k) flag and optional target")
      handle { service.uninstall(input.value) }
    }

    command[RebootResult]("reboot") {
      description("Reboot the target Android device into system, recovery or bootloader mode via native ADB 'reboot:' service")
      val input = body[RebootRequest]("Reboot mode (system, recovery, bootloader), confirm=true safety flag, and optional target")
      handle { service.reboot(input.value) }
    }
  }
