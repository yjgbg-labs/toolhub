package groot.powershell

import cats.effect.{IO, Resource}
import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.Mutex
import cats.syntax.all.*
import groot.bash.{BashFailure, BashRequest, BashService}
import groot.graaljs.{InvalidGraalTask, Validation}
import java.nio.charset.StandardCharsets.{UTF_8, UTF_16LE}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** OpenSSH owns the idle timer; each call owns only its multiplexed session and payload file. */
final class PowerShellService private (
    directory: Path, target: String, idleSeconds: Long, ssh: String, mutex: Mutex[IO]
):
  import PowerShellService.*
  private val bash = new BashService
  private val socket = directory.resolve("control.sock")
  private val log = directory.resolve("ssh.log")
  private val base = Vector(ssh, "-T", "-S", socket.toString.replace("%", "%%"),
    "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=5",
    "-o", "ConnectionAttempts=1", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2")
  private def command(args: String*): String = (base ++ args).map(quote).mkString(" ")

  private def connect: IO[Unit] = mutex.lock.surround {
    val check = command("-O", "check", target)
    val start = command("-o", "ControlMaster=yes", "-o", s"ControlPersist=$idleSeconds", "-fN", target)
    bash(BashRequest(s"if ! $check >/dev/null 2>&1; then\n$start </dev/null >${quote(log.toString)} 2>&1 || { cat ${quote(log.toString)} >&2; exit 1; }\nfi", timeout = "10s"))
      .flatMap(result => if result.exitCode == 0 then IO.unit else
        IO.raiseError(PowerShellFailure("powershell_ssh", result.stderr.trim)))
  }

  def apply(script: String, timeout: String = "30s"): IO[PowerShellResult] = validate(script, timeout).flatMap { (payload, deadline) =>
    val run = Resource.make(IO.blocking {
      val file = Files.createTempFile(directory, "script-", ".ps1", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      try
        Files.write(file, payload)
        file
      catch
        case error: Throwable => Files.deleteIfExists(file); throw error
    })(file => IO.blocking { Files.deleteIfExists(file); () }).use { file =>
      val token = UUID.randomUUID().toString
      val remote = remoteCommand(bootstrap(payload.length, token))
      // If the master expires between the check and channel creation, OpenSSH establishes a new one.
      // Never retry an execution: a broken transport may have already performed remote side effects.
      val execute = command("-o", "ControlMaster=auto", "-o", s"ControlPersist=$idleSeconds", target, remote)
      connect *> Resource.makeCase(IO.unit) { (_, exit) =>
        if exit == ExitCase.Succeeded then IO.unit else cancelRemote(token)
      }.use { _ =>
        bash(BashRequest(s"$execute < ${quote(file.toString)}", timeout = timeout))
          .map(result => PowerShellResult(result.exitCode, result.stdout, result.stderr))
      }
    }
    run.timeoutTo(deadline, IO.raiseError(PowerShellFailure("powershell_timeout", s"PowerShell exceeded $timeout")))
  }.handleErrorWith {
    case error: BashFailure if error.code == "bash_limit" => IO.raiseError(PowerShellFailure("powershell_limit", error.getMessage))
    case error: BashFailure if error.code == "bash_timeout" => IO.raiseError(PowerShellFailure("powershell_timeout", error.getMessage))
    case error: BashFailure => IO.raiseError(PowerShellFailure("powershell_ssh", error.getMessage))
    case error => IO.raiseError(error)
  }

  private def cancelRemote(token: String): IO[Unit] =
    bash(BashRequest(command(target, remoteCommand(cancellation(token))), timeout = "5s")).attempt.flatMap {
      case Right(result) if result.exitCode == 0 => IO.unit
      case Right(result) => IO(scribe.warn(s"PowerShell cancellation cleanup failed for $token: ${result.stderr.trim}"))
      case Left(error) => IO(scribe.warn(s"PowerShell cancellation cleanup failed for $token: ${error.getMessage}"))
    }

  private def close: IO[Unit] =
    bash(BashRequest(command("-O", "exit", target), timeout = "5s")).attempt.flatMap {
      case Left(error) => IO(scribe.warn(s"Could not close PowerShell SSH master: ${error.getMessage}"))
      case Right(_) => IO.unit // No socket is normal after the idle timeout or when never used.
    } *> IO.blocking {
      val files = Files.list(directory)
      try files.iterator().asScala.foreach(Files.deleteIfExists(_)) finally files.close()
      Files.deleteIfExists(directory)
      ()
    }

object PowerShellService:
  private[powershell] val maxBytes = 64 * 1024
  private def quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  private def duration(value: String): FiniteDuration =
    try Validation.durationMs("timeout", value).millis
    catch case error: InvalidGraalTask => throw PowerShellFailure("invalid_powershell", error.getMessage)

  private def validate(script: String, timeout: String): IO[(Array[Byte], FiniteDuration)] = IO {
    val source = script.stripPrefix("\uFEFF")
    if source.trim.isEmpty || source.contains('\u0000') then throw PowerShellFailure("invalid_powershell", "script must be nonblank and contain no NUL")
    if source.getBytes(UTF_8).length > maxBytes then throw PowerShellFailure("powershell_limit", "PowerShell script exceeds 64 KiB")
    (("\uFEFF" + source + "\n").getBytes(UTF_8), duration(timeout))
  }

  // Windows OpenSSH may leave stdin open: read the exact byte count rather than waiting for EOF.
  // A UTF-8 BOM preserves Unicode under Windows PowerShell 5.1. The payload is never a command-line argument.
  private def remoteCommand(source: String): String =
    "powershell.exe -NoLogo -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand " +
      Base64.getEncoder.encodeToString(source.getBytes(UTF_16LE))

  private[powershell] def bootstrap(length: Int, token: String): String = """
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = [Console]::OutputEncoding
    $directory = Join-Path ([IO.Path]::GetTempPath()) 'groot-powershell-__TOKEN__'
    $path = Join-Path $directory 'script.ps1'
    $pidPath = Join-Path $directory 'process.json'
    $cancelPath = Join-Path $directory 'cancel'
    $code = 1
    try {
      [IO.Directory]::CreateDirectory($directory) | Out-Null
      if (Test-Path $cancelPath) { throw 'PowerShell request cancelled' }
      $identity = @{pid=$PID; started=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString()} | ConvertTo-Json -Compress
      [IO.File]::WriteAllText($pidPath + '.stage', $identity)
      [IO.File]::Move($pidPath + '.stage', $pidPath)
      if (Test-Path $cancelPath) { throw 'PowerShell request cancelled' }
      $inputStream = [Console]::OpenStandardInput()
      $bytes = New-Object byte[] __LENGTH__
      $offset = 0
      while ($offset -lt $bytes.Length) {
        $read = $inputStream.Read($bytes, $offset, $bytes.Length - $offset)
        if ($read -eq 0) { throw 'Unexpected end of PowerShell script input' }
        $offset += $read
      }
      [IO.File]::WriteAllBytes($path, $bytes)
      if (Test-Path $cancelPath) { throw 'PowerShell request cancelled' }
      $global:LASTEXITCODE = 0
      & $path
      $ok = $?
      if ($LASTEXITCODE -ne 0) { $code = $LASTEXITCODE } elseif ($ok) { $code = 0 }
    } catch {
      [Console]::Error.WriteLine($_.ToString())
      $code = 1
    } finally {
      Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
    }
    exit $code
    """.replace("__LENGTH__", length.toString).replace("__TOKEN__", token)

  // A marker closes the race with a remote bootstrap that has not published its PID yet.
  // Verify creation time before taskkill so a recycled Windows PID cannot select an unrelated process.
  private[powershell] def cancellation(token: String): String = """
    $ErrorActionPreference = 'Stop'
    $directory = Join-Path ([IO.Path]::GetTempPath()) 'groot-powershell-__TOKEN__'
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    [IO.File]::WriteAllText((Join-Path $directory 'cancel'), '')
    $pidPath = Join-Path $directory 'process.json'
    if (Test-Path $pidPath) {
      $identity = Get-Content -Raw $pidPath | ConvertFrom-Json
      $process = Get-Process -Id ([int]$identity.pid) -ErrorAction SilentlyContinue
      if ($null -ne $process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $identity.started) {
        & "$env:SystemRoot\System32\taskkill.exe" /PID $identity.pid /T /F | Out-Null
        if ($LASTEXITCODE -ne 0 -and -not $process.HasExited) { throw 'Cannot stop remote PowerShell process tree' }
      }
      Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
    }
    """.replace("__TOKEN__", token)

  def resource(
      home: Path = groot.schedule.Store.defaultHome.resolve("powershell"),
      target: String = sys.env.getOrElse("POWERSHELL_SSH_TARGET", "windows"),
      idle: String = sys.env.getOrElse("POWERSHELL_SSH_IDLE", "5m"),
      ssh: String = "/usr/bin/ssh"
  ): Resource[IO, PowerShellService] = for
    idleSeconds <- Resource.eval(IO {
      require(target.nonEmpty && !target.startsWith("-") && !target.exists(c => c.isWhitespace || c == '\u0000'), "Invalid PowerShell SSH target")
      val millis = duration(idle).toMillis
      require(millis >= 1000, "PowerShell SSH idle timeout must be at least one second")
      (millis + 999) / 1000
    })
    directory <- Resource.make(IO.blocking {
      Files.createDirectories(home)
      val directory = Files.createTempDirectory(home, "s-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
      if directory.resolve("control.sock").toString.getBytes(UTF_8).length >= 100 then
        Files.delete(directory)
        throw new IllegalArgumentException("PowerShell SSH control socket path is too long")
      directory
    })(directory => IO.blocking { Files.deleteIfExists(directory); () }.handleErrorWith(_ => IO.unit))
    mutex <- Resource.eval(Mutex[IO])
    service <- Resource.make(IO.pure(new PowerShellService(directory, target, idleSeconds, ssh, mutex)))(_.close)
  yield service
