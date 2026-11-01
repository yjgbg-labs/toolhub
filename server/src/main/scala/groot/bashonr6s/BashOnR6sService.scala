package groot.bashonr6s

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

/** Each SSH channel owns a Linux process group; only the transport is shared. */
final class BashOnR6sService private (
    directory: Path, target: String, idleSeconds: Long, ssh: String, mutex: Mutex[IO]
):
  import BashOnR6sService.*
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
      .flatMap(result => if result.exitCode == 0 then IO.unit else IO.raiseError(BashOnR6sFailure("bash_on_r6s_ssh", result.stderr.trim)))
  }

  def apply(script: String, timeout: String = "30s"): IO[BashOnR6sResult] = validate(script, timeout).flatMap { (payload, deadline) =>
    val run = Resource.make(IO.blocking {
      val file = Files.createTempFile(directory, "script-", ".bash", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
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
          .map(result => BashOnR6sResult(result.exitCode, result.stdout, result.stderr))
      }
    }
    run.timeoutTo(deadline, IO.raiseError(BashOnR6sFailure("bash_on_r6s_timeout", s"Bash on R6S exceeded $timeout")))
  }.handleErrorWith {
    case error: BashFailure if error.code == "bash_limit" => IO.raiseError(BashOnR6sFailure("bash_on_r6s_limit", error.getMessage))
    case error: BashFailure if error.code == "bash_timeout" => IO.raiseError(BashOnR6sFailure("bash_on_r6s_timeout", error.getMessage))
    case error: BashFailure => IO.raiseError(BashOnR6sFailure("bash_on_r6s_ssh", error.getMessage))
    case error => IO.raiseError(error)
  }

  private def cancelRemote(token: String): IO[Unit] =
    bash(BashRequest(command(target, remoteCommand(cancellation(token))), timeout = "5s")).attempt.flatMap {
      case Right(result) if result.exitCode == 0 => IO.unit
      case Right(result) => IO(scribe.warn(s"R6S Bash cancellation cleanup failed for $token: ${result.stderr.trim}"))
      case Left(error) => IO(scribe.warn(s"R6S Bash cancellation cleanup failed for $token: ${error.getMessage}"))
    }

  private def close: IO[Unit] =
    bash(BashRequest(command("-O", "exit", target), timeout = "5s")).attempt.flatMap {
      case Left(error) => IO(scribe.warn(s"Could not close R6S Bash SSH master: ${error.getMessage}"))
      case Right(_) => IO.unit
    } *> IO.blocking {
      val files = Files.list(directory)
      try files.iterator().asScala.foreach(Files.deleteIfExists(_)) finally files.close()
      Files.deleteIfExists(directory)
      ()
    }

object BashOnR6sService:
  private[bashonr6s] val maxBytes = 64 * 1024
  private def quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
  private def remoteCommand(source: String): String = "exec /usr/bin/env -u BASH_ENV /bin/bash --noprofile --norc -c " + quote(source)

  private def duration(value: String): FiniteDuration =
    try Validation.durationMs("timeout", value).millis
    catch case error: InvalidGraalTask => throw BashOnR6sFailure("invalid_bash_on_r6s", error.getMessage)

  private def validate(script: String, timeout: String): IO[(Array[Byte], FiniteDuration)] = IO {
    val source = script.stripPrefix("\uFEFF")
    if source.trim.isEmpty || source.contains('\u0000') then throw BashOnR6sFailure("invalid_bash_on_r6s", "script must be nonblank and contain no NUL")
    if source.getBytes(UTF_8).length > maxBytes then throw BashOnR6sFailure("bash_on_r6s_limit", "Bash script exceeds 64 KiB")
    ((source + "\n").getBytes(UTF_8), duration(timeout))
  }

  // sshd starts each non-PTY command in its own session. Keep the leader alive while Bash runs,
  // publish its identity atomically, and frame stdin by byte count rather than depending on EOF.
  private[bashonr6s] def bootstrap(length: Int, token: String): String = """
    umask 077
    token=__TOKEN__
    length=__LENGTH__
    directory=/tmp/sprout-bash-on-r6s-$token
    /bin/mkdir -p "$directory" || exit 1
    pgid=$(/bin/ps -p $$ -o pgid=)
    if [[ ${pgid//[[:space:]]/} != $$ ]]; then
      printf '%s\n' 'SSH session does not own an isolated process group' >&2
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
    /bin/dd bs=65536 iflag=count_bytes,fullblock count="$length" of="$directory/script.bash" status=none || exit 1
    if [[ $(/usr/bin/wc -c < "$directory/script.bash") -ne $length ]]; then
      printf '%s\n' 'Unexpected end of Bash script input' >&2
      exit 1
    fi
    [[ -f "$directory/cancel" ]] && exit 125
    /bin/bash --noprofile --norc "$directory/script.bash" < /dev/null
    code=$?
    exit $code
    """.replace("__LENGTH__", length.toString).replace("__TOKEN__", token)

  // The marker covers cancellation before PID publication. Match start time, request token and
  // group ownership before signalling, so an expired request cannot target a recycled PID.
  private[bashonr6s] def cancellation(token: String): String = """
    umask 077
    token=__TOKEN__
    directory=/tmp/sprout-bash-on-r6s-$token
    /bin/mkdir -p "$directory" || exit 1
    : > "$directory/cancel"
    if [[ -f "$directory/process" ]]; then
      { IFS= read -r pid; IFS= read -r started; } < "$directory/process"
      [[ $pid =~ ^[0-9]+$ && $pid -gt 1 ]] || exit 1
      current=$(LC_ALL=C /bin/ps -p "$pid" -o lstart=)
      pgid=$(/bin/ps -p "$pid" -o pgid=)
      args=$(/bin/ps -ww -p "$pid" -o command=)
      if [[ "$current" == "$started" && ${pgid//[[:space:]]/} == $pid && "$args" == *"$token"* ]]; then
        kill -TERM -- -$pid 2>/dev/null || true
        /bin/sleep 0.2
        kill -KILL -- -$pid 2>/dev/null || true
      fi
      /bin/rm -rf "$directory"
    fi
    """.replace("__TOKEN__", token)

  def resource(
      home: Path = groot.schedule.Store.defaultHome.resolve("bash-on-r6s"),
      target: String = sys.env.getOrElse("BASH_ON_R6S_SSH_TARGET", "router@100.64.0.7"),
      idle: String = sys.env.getOrElse("BASH_ON_R6S_SSH_IDLE", "5m"),
      ssh: String = "/usr/bin/ssh"
  ): Resource[IO, BashOnR6sService] = for
    idleSeconds <- Resource.eval(IO {
      require(target.nonEmpty && !target.startsWith("-") && !target.exists(c => c.isWhitespace || c == '\u0000'), "Invalid R6S SSH target")
      val millis = duration(idle).toMillis
      require(millis >= 1000, "R6S SSH idle timeout must be at least one second")
      (millis + 999) / 1000
    })
    directory <- Resource.make(IO.blocking {
      Files.createDirectories(home)
      val directory = Files.createTempDirectory(home, "s-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
      if directory.resolve("control.sock").toString.getBytes(UTF_8).length >= 100 then
        Files.delete(directory)
        throw new IllegalArgumentException("R6S SSH control socket path is too long")
      directory
    })(directory => IO.blocking { Files.deleteIfExists(directory); () }.handleErrorWith(_ => IO.unit))
    mutex <- Resource.eval(Mutex[IO])
    service <- Resource.make(IO.pure(new BashOnR6sService(directory, target, idleSeconds, ssh, mutex)))(_.close)
  yield service
