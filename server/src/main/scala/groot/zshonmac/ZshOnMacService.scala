package groot.zshonmac

import cats.effect.{IO, Resource}
import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.Mutex
import cats.syntax.all.*
import groot.bash.{BashFailure, BashRequest, BashService}
import groot.graaljs.{InvalidGraalTask, Validation}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Each OpenSSH channel gets an isolated macOS process group; only the transport is shared. */
final class ZshOnMacService private (
    directory: Path, target: String, idleSeconds: Long, ssh: String, mutex: Mutex[IO]
):
  import ZshOnMacService.*
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
      .flatMap(result => if result.exitCode == 0 then IO.unit else IO.raiseError(ZshOnMacFailure("zsh_on_mac_ssh", result.stderr.trim)))
  }

  def apply(script: String, timeout: String = "30s"): IO[ZshOnMacResult] = validate(script, timeout).flatMap { (payload, deadline) =>
    val run = Resource.make(IO.blocking {
      val file = Files.createTempFile(directory, "script-", ".zsh", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      try
        Files.write(file, payload)
        file
      catch
        case error: Throwable => Files.deleteIfExists(file); throw error
    })(file => IO.blocking { Files.deleteIfExists(file); () }).use { file =>
      val token = UUID.randomUUID().toString
      val remote = remoteCommand(bootstrap(payload.length, token))
      // Reconnect if the idle timer wins the race, but never retry a possibly executed script.
      val execute = command("-o", "ControlMaster=auto", "-o", s"ControlPersist=$idleSeconds", target, remote)
      connect *> Resource.makeCase(IO.unit) { (_, exit) =>
        if exit == ExitCase.Succeeded then IO.unit else cancelRemote(token)
      }.use { _ =>
        bash(BashRequest(s"$execute < ${quote(file.toString)}", timeout = timeout))
          .map(result => ZshOnMacResult(result.exitCode, result.stdout, result.stderr))
      }
    }
    run.timeoutTo(deadline, IO.raiseError(ZshOnMacFailure("zsh_on_mac_timeout", s"Zsh on Mac exceeded $timeout")))
  }.handleErrorWith {
    case error: BashFailure if error.code == "bash_limit" => IO.raiseError(ZshOnMacFailure("zsh_on_mac_limit", error.getMessage))
    case error: BashFailure if error.code == "bash_timeout" => IO.raiseError(ZshOnMacFailure("zsh_on_mac_timeout", error.getMessage))
    case error: BashFailure => IO.raiseError(ZshOnMacFailure("zsh_on_mac_ssh", error.getMessage))
    case error => IO.raiseError(error)
  }

  private def cancelRemote(token: String): IO[Unit] =
    bash(BashRequest(command(target, remoteCommand(cancellation(token))), timeout = "5s")).attempt.flatMap {
      case Right(result) if result.exitCode == 0 => IO.unit
      case Right(result) => IO(scribe.warn(s"Mac zsh cancellation cleanup failed for $token: ${result.stderr.trim}"))
      case Left(error) => IO(scribe.warn(s"Mac zsh cancellation cleanup failed for $token: ${error.getMessage}"))
    }

  private def close: IO[Unit] =
    bash(BashRequest(command("-O", "exit", target), timeout = "5s")).attempt.flatMap {
      case Left(error) => IO(scribe.warn(s"Could not close Mac zsh SSH master: ${error.getMessage}"))
      case Right(_) => IO.unit
    } *> IO.blocking {
      val files = Files.list(directory)
      try files.iterator().asScala.foreach(Files.deleteIfExists(_)) finally files.close()
      Files.deleteIfExists(directory)
      ()
    }

object ZshOnMacService:
  private[zshonmac] val maxBytes = 64 * 1024
  private def quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
  private def remoteCommand(source: String): String = "exec /bin/zsh -f -c " + quote(source)

  private def duration(value: String): FiniteDuration =
    try Validation.durationMs("timeout", value).millis
    catch case error: InvalidGraalTask => throw ZshOnMacFailure("invalid_zsh_on_mac", error.getMessage)

  private def validate(script: String, timeout: String): IO[(Array[Byte], FiniteDuration)] = IO {
    val source = script.stripPrefix("\uFEFF")
    if source.trim.isEmpty || source.contains('\u0000') then throw ZshOnMacFailure("invalid_zsh_on_mac", "script must be nonblank and contain no NUL")
    if source.getBytes(UTF_8).length > maxBytes then throw ZshOnMacFailure("zsh_on_mac_limit", "Zsh script exceeds 64 KiB")
    ((source + "\n").getBytes(UTF_8), duration(timeout))
  }

  // sshd starts each non-PTY command in its own session. Keep the leader alive while zsh runs,
  // publish its identity atomically, and read an exact byte count rather than depending on SSH EOF.
  private[zshonmac] def bootstrap(length: Int, token: String): String = """
    umask 077
    token=__TOKEN__
    length=__LENGTH__
    directory=$(/usr/bin/getconf DARWIN_USER_TEMP_DIR)/groot-zsh-on-mac-$token
    /bin/mkdir -p "$directory" || exit 1
    pgid=$(/bin/ps -p $$ -o pgid=)
    if [[ ${pgid//[[:space:]]/} != $$ ]]; then
      print -u2 -- 'SSH session does not own an isolated process group'
      /bin/rm -rf "$directory"
      exit 1
    fi
    trap '/bin/rm -rf "$directory"' EXIT
    trap 'kill -KILL -- -$$' HUP INT TERM
    [[ -f "$directory/cancel" ]] && exit 125
    started=$(LC_ALL=C /bin/ps -p $$ -o lstart=)
    printf '%s\n%s\n' "$$" "$started" > "$directory/process.stage"
    /bin/mv "$directory/process.stage" "$directory/process" || exit 1
    [[ -f "$directory/cancel" ]] && exit 125
    /bin/dd bs=1 count=$length of="$directory/script.zsh" 2>/dev/null || exit 1
    if [[ $(/usr/bin/wc -c < "$directory/script.zsh") -ne $length ]]; then
      print -u2 -- 'Unexpected end of zsh script input'
      exit 1
    fi
    [[ -f "$directory/cancel" ]] && exit 125
    /bin/zsh -f "$directory/script.zsh" < /dev/null
    code=$?
    exit $code
    """.replace("__LENGTH__", length.toString).replace("__TOKEN__", token)

  // The marker covers cancellation before PID publication. Match start time, request token and
  // group ownership before signalling, so an expired request cannot target a recycled PID.
  private[zshonmac] def cancellation(token: String): String = """
    umask 077
    token=__TOKEN__
    directory=$(/usr/bin/getconf DARWIN_USER_TEMP_DIR)/groot-zsh-on-mac-$token
    /bin/mkdir -p "$directory" || exit 1
    : > "$directory/cancel"
    if [[ -f "$directory/process" ]]; then
      { IFS= read -r pid; IFS= read -r started; } < "$directory/process"
      [[ $pid == <-> && $pid -gt 1 ]] || exit 1
      current=$(LC_ALL=C /bin/ps -p $pid -o lstart=)
      pgid=$(/bin/ps -p $pid -o pgid=)
      args=$(/bin/ps -ww -p $pid -o command=)
      if [[ "$current" == "$started" && ${pgid//[[:space:]]/} == $pid && "$args" == *"$token"* ]]; then
        kill -TERM -- -$pid 2>/dev/null || true
        /bin/sleep 0.2
        kill -KILL -- -$pid 2>/dev/null || true
      fi
      /bin/rm -rf "$directory"
    fi
    """.replace("__TOKEN__", token)

  def resource(
      home: Path = groot.schedule.Store.defaultHome.resolve("zsh-on-mac"),
      target: String = sys.env.getOrElse("ZSH_ON_MAC_SSH_TARGET", "weichenglong@100.64.0.5"),
      idle: String = sys.env.getOrElse("ZSH_ON_MAC_SSH_IDLE", "5m"),
      ssh: String = "/usr/bin/ssh"
  ): Resource[IO, ZshOnMacService] = for
    idleSeconds <- Resource.eval(IO {
      require(target.nonEmpty && !target.startsWith("-") && !target.exists(c => c.isWhitespace || c == '\u0000'), "Invalid Mac SSH target")
      val millis = duration(idle).toMillis
      require(millis >= 1000, "Mac SSH idle timeout must be at least one second")
      (millis + 999) / 1000
    })
    directory <- Resource.make(IO.blocking {
      Files.createDirectories(home)
      val directory = Files.createTempDirectory(home, "s-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
      if directory.resolve("control.sock").toString.getBytes(UTF_8).length >= 100 then
        Files.delete(directory)
        throw new IllegalArgumentException("Mac SSH control socket path is too long")
      directory
    })(directory => IO.blocking { Files.deleteIfExists(directory); () }.handleErrorWith(_ => IO.unit))
    mutex <- Resource.eval(Mutex[IO])
    service <- Resource.make(IO.pure(new ZshOnMacService(directory, target, idleSeconds, ssh, mutex)))(_.close)
  yield service
