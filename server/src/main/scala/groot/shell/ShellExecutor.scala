package groot.shell

import cats.effect.{IO, Resource}
import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.Mutex
import cats.syntax.all.*
import groot.graaljs.{InvalidGraalTask, Validation}
import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, InvalidPathException, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** One execution pipeline; only the remote command, payload and cleanup scripts depend on the shell. */
private[shell] final class ShellExecutor private (environment: ShellEnvironment, connection: Option[ShellExecutor.Connection]):
  import ShellExecutor.*
  import ShellScripts.quote

  def execute(request: ShellRequest): IO[ShellResult] = connection match
    case None => Process.run(request.script, request.timeout, request.cwd)
    case Some(ssh) => remote(ssh, request)

  private def connect(ssh: Connection): IO[Unit] = ssh.mutex.lock.surround {
    val check = ssh.command("-O", "check", ssh.target)
    val start = ssh.command("-o", "ControlMaster=yes", "-o", s"ControlPersist=${ssh.idleSeconds}", "-fN", ssh.target)
    val log = quote(ssh.directory.resolve("ssh.log").toString)
    Process.run(s"if ! $check >/dev/null 2>&1; then\n$start </dev/null >$log 2>&1 || { cat $log >&2; exit 1; }\nfi", "10s")
      .flatMap(result => if result.exitCode == 0 then IO.unit else IO.raiseError(ShellFailure("shell_ssh", result.stderr.trim)))
  }

  private def remote(ssh: Connection, request: ShellRequest): IO[ShellResult] = IO {
    (ShellScripts.payload(environment.shell, request.script), duration(request.timeout))
  }.flatMap { (payload, deadline) =>
    val run = Resource.make(IO.blocking {
      val file = Files.createTempFile(ssh.directory, "script-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      try
        Files.write(file, payload)
        file
      catch
        case error: Throwable =>
          Files.deleteIfExists(file)
          throw error
    })(file => IO.blocking { Files.deleteIfExists(file); () }).use { file =>
      val token = UUID.randomUUID().toString
      val bootstrap = ShellScripts.command(environment.shell, ShellScripts.bootstrap(environment.shell, payload.length, token))
      // Reconnect if the idle timer wins the race, but never retry a possibly executed script.
      val command = ssh.command("-o", "ControlMaster=auto", "-o", s"ControlPersist=${ssh.idleSeconds}", ssh.target, bootstrap)
      connect(ssh) *> Resource.makeCase(IO.unit) { (_, exit) =>
        if exit == ExitCase.Succeeded then IO.unit else cancelRemote(ssh, token)
      }.use(_ => Process.run(s"$command < ${quote(file.toString)}", request.timeout))
    }
    run.timeoutTo(deadline, IO.raiseError(ShellFailure("shell_timeout", s"${environment.shell} exceeded ${request.timeout}")))
  }

  private def cancelRemote(ssh: Connection, token: String): IO[Unit] =
    val script = ShellScripts.command(environment.shell, ShellScripts.cancellation(environment.shell, token))
    Process.run(ssh.command(ssh.target, script), "5s").attempt.flatMap {
      case Right(result) if result.exitCode == 0 => IO.unit
      case Right(result) => IO(scribe.warn(s"Shell cancellation cleanup failed for $token: ${result.stderr.trim}"))
      case Left(error) => IO(scribe.warn(s"Shell cancellation cleanup failed for $token: ${error.getMessage}"))
    }

  private def close: IO[Unit] = connection.traverse_ { ssh =>
    Process.run(ssh.command("-O", "exit", ssh.target), "5s").attempt.flatMap {
      case Left(error) => IO(scribe.warn(s"Could not close Shell SSH master: ${error.getMessage}"))
      case Right(_) => IO.unit // No socket is normal after the idle timeout or when never used.
    } *> IO.blocking {
      val files = Files.list(ssh.directory)
      try files.iterator().asScala.foreach(Files.deleteIfExists(_)) finally files.close()
      Files.deleteIfExists(ssh.directory)
      ()
    }
  }

private[shell] object ShellExecutor:
  val maxBytes = 64 * 1024

  def duration(value: String): FiniteDuration =
    try Validation.durationMs("timeout", value).millis
    catch case error: InvalidGraalTask => throw ShellFailure("invalid_shell", error.getMessage)

  private case class Connection(directory: Path, target: String, idleSeconds: Long, executable: String, mutex: Mutex[IO]):
    private val base = Vector(executable, "-T", "-S", directory.resolve("control.sock").toString.replace("%", "%%"),
      "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=5",
      "-o", "ConnectionAttempts=1", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2")
    def command(args: String*): String = (base ++ args).map(ShellScripts.quote).mkString(" ")

  def resource(home: Path, environment: ShellEnvironment, ssh: String): Resource[IO, ShellExecutor] = for
    connection <- environment.target.traverse { target => for
      idleSeconds <- Resource.eval(IO {
        val millis = duration(environment.idle).toMillis
        require(millis >= 1000, "SSH idle timeout must be at least one second")
        (millis + 999) / 1000
      })
      directory <- Resource.make(IO.blocking {
        Files.createDirectories(home)
        val directory = Files.createTempDirectory(home, "s-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        if directory.resolve("control.sock").toString.getBytes(UTF_8).length >= 100 then
          Files.delete(directory)
          throw new IllegalArgumentException("SSH control socket path is too long")
        directory
      })(directory => IO.blocking { Files.deleteIfExists(directory); () }.handleErrorWith(_ => IO.unit))
      mutex <- Resource.eval(Mutex[IO])
    yield Connection(directory, target, idleSeconds, ssh, mutex) }
    executor <- Resource.make(IO.pure(new ShellExecutor(environment, connection)))(_.close)
  yield executor

  /** Both local scripts and SSH channels use the same bounded, cancellable host process runner. */
  private object Process:
    def run(script: String, timeout: String, cwd: Option[String] = None): IO[ShellResult] = IO.blocking {
      if script.trim.isEmpty || script.contains('\u0000') then throw ShellFailure("invalid_shell", "script must be nonblank and contain no NUL")
      if script.getBytes(UTF_8).length > maxBytes then throw ShellFailure("shell_limit", "Shell script exceeds 64 KiB")
      val directory = cwd.map { value =>
        val path = try Path.of(value) catch case _: InvalidPathException => throw ShellFailure("invalid_shell", "Invalid cwd")
        if !path.isAbsolute || !Files.isDirectory(path) then throw ShellFailure("invalid_shell", "cwd must be an existing absolute directory")
        path
      }
      (directory, duration(timeout))
    }.flatMap { (directory, deadline) =>
      Resource.makeCase(IO.blocking {
        val builder = new ProcessBuilder("/usr/bin/setsid", "--", "/bin/bash", "--noprofile", "--norc", "-c", script)
          .directory(directory.map(_.toFile).orNull)
        // Noninteractive startup must not execute an inherited BASH_ENV script before the supplied script.
        builder.environment().remove("BASH_ENV")
        builder.start()
      }) { (child, exit) =>
        (if exit == ExitCase.Succeeded then IO.unit else stop(child)).guarantee(IO.blocking {
          List(child.getOutputStream, child.getInputStream, child.getErrorStream).foreach(stream =>
            try stream.close() catch case NonFatal(_) => ())
        })
      }.use { child =>
        val execute = for
          _ <- IO.blocking(child.getOutputStream.close())
          stdout <- capture(child.getInputStream)
          stderr <- capture(child.getErrorStream)
          result <- (await(child.onExit()).map(_.exitValue()), await(stdout), await(stderr)).parMapN(ShellResult.apply)
        yield result
        execute.timeoutTo(deadline, IO.raiseError(ShellFailure("shell_timeout", s"Shell exceeded $timeout")))
      }
    }

    // Pipe reads cannot reliably be interrupted; cancellation kills the process group to unblock them.
    private def capture(input: InputStream): IO[CompletableFuture[String]] = IO {
      val result = new CompletableFuture[String]()
      Thread.ofPlatform().daemon().name("shell-output").start(() => {
        try
          val output = new ByteArrayOutputStream()
          val buffer = new Array[Byte](8192)
          var count = input.read(buffer)
          while count >= 0 do
            if output.size() + count > maxBytes then throw ShellFailure("shell_limit", "Shell output exceeds 64 KiB per stream")
            output.write(buffer, 0, count)
            count = input.read(buffer)
          result.complete(output.toString(UTF_8))
        catch case NonFatal(error) => result.completeExceptionally(error)
        ()
      })
      result
    }

    private def await[A](future: CompletableFuture[A]): IO[A] = IO.async { callback => IO {
      future.whenComplete { (value, error) => callback(if error == null then Right(value) else Left(error)) }
      Some(IO.unit) // Resource cleanup owns the actual process.
    } }

    private def signal(pid: Long, name: String): IO[Unit] = IO.blocking {
      val command = new ProcessBuilder("/bin/kill", s"-$name", "--", s"-$pid")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
      command.getOutputStream.close()
      if !command.waitFor(1, TimeUnit.SECONDS) then command.destroyForcibly()
      () // ESRCH is normal when the process group has already exited.
    }

    private def stop(child: java.lang.Process): IO[Unit] = for
      descendants <- IO.blocking {
        val stream = child.descendants()
        try stream.iterator().asScala.toVector.reverse finally stream.close()
      }
      _ <- signal(child.pid(), "TERM")
      _ <- IO.blocking { descendants.foreach(_.destroy()); child.toHandle.destroy(); () }
      _ <- IO.sleep(200.millis)
      // Signal even after the leader exits: grandchildren may still own its pipes/group.
      _ <- signal(child.pid(), "KILL")
      _ <- IO.blocking {
        descendants.filter(_.isAlive).foreach(_.destroyForcibly())
        if child.isAlive then child.toHandle.destroyForcibly()
        child.waitFor()
        ()
      }
    yield ()
