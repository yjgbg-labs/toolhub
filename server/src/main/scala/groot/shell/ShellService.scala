package groot.shell

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import framework.model.JsonModel
import groot.bash.{BashFailure, BashRequest, BashService}
import groot.bashonr6s.{BashOnR6sFailure, BashOnR6sService}
import groot.graaljs.{InvalidGraalTask, Validation}
import groot.powershell.{PowerShellFailure, PowerShellService}
import groot.zshonmac.{ZshOnMacFailure, ZshOnMacService}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}

/** Registry changes are atomic. Executions lease their environment, independently of later deletion/recreation. */
final class ShellService private (home: Path, ssh: String, mutex: Mutex[IO]):
  import ShellService.*
  private class Entry(val environment: ShellEnvironment, val run: Run, val release: IO[Unit], val closed: Deferred[IO, Unit]):
    var users = 0
    var retired = false
  private var entries = Map.empty[String, Entry]
  private var retired = Set.empty[Entry]
  private var stopping = false

  private def serial[A](action: => IO[A]): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.defer(action)))
  private def ensureOpen(): Unit = if stopping then throw ShellFailure("invalid_shell", "Shell service is shutting down")
  private def find(name: String): Entry = entries.getOrElse(name, throw ShellFailure("shell_not_found", s"No shell environment named $name"))

  private def create(environment: ShellEnvironment): IO[Entry] = for
    allocated <- backend(home.resolve("ssh"), environment, ssh).allocated
    (run, release) = allocated
    closed <- Deferred[IO, Unit]
  yield new Entry(environment, run, release, closed)

  private def save(values: Vector[ShellEnvironment]): IO[Unit] = IO.blocking {
    val path = home.resolve("environments.json")
    val temporary = Files.createTempFile(home, ".environments-", ".tmp")
    try
      Files.writeString(temporary, JsonModel[ShellRegistry].encoder(ShellRegistry(1, values.sortBy(_.name))).spaces2 + "\n")
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temporary)
  }

  private def initialize(initial: Vector[ShellEnvironment]): IO[Unit] = serial {
    for
      values <- IO.blocking {
        val path = home.resolve("environments.json")
        val values = if Files.exists(path) then
          val registry = io.circe.parser.parse(Files.readString(path)).flatMap(JsonModel[ShellRegistry].decoder.decodeJson)
            .fold(error => throw ShellFailure("invalid_shell", s"Cannot read $path: ${error.getMessage}"), identity)
          if registry.version != 1 then throw ShellFailure("invalid_shell", s"Unsupported shell registry version: ${registry.version}")
          registry.environments
        else initial
        values.foreach(validateEnvironment)
        if values.map(_.name).distinct.size != values.size then throw ShellFailure("invalid_shell", "Duplicate shell environment names")
        values
      }
      _ <- values.traverse_(value => create(value).flatMap(entry => IO { entries += value.name -> entry }))
      _ <- save(values)
    yield ()
  }

  def list: IO[Vector[ShellEnvironment]] = serial(IO { ensureOpen(); entries.valuesIterator.map(_.environment).toVector.sortBy(_.name) })

  def add(environment: ShellEnvironment): IO[ShellEnvironment] = serial {
    for
      _ <- IO {
        ensureOpen()
        validateEnvironment(environment)
        if entries.contains(environment.name) then throw ShellFailure("shell_exists", s"Shell environment already exists: ${environment.name}")
      }
      entry <- create(environment)
      _ <- save(entries.valuesIterator.map(_.environment).toVector :+ environment).onError { case _ => entry.release }
      _ <- IO { entries += environment.name -> entry }
    yield environment
  }

  private def closeIfIdle(entry: Entry): IO[Unit] =
    if entry.retired && entry.users == 0 then
      entry.release.guarantee(entry.closed.complete(()).void *> IO { retired -= entry })
    else IO.unit

  def delete(name: String): IO[Unit] = serial {
    for
      entry <- IO { ensureOpen(); find(name) }
      _ <- save(entries.valuesIterator.filterNot(_ eq entry).map(_.environment).toVector)
      _ <- IO { entries -= name; entry.retired = true; retired += entry }
      _ <- closeIfIdle(entry)
    yield ()
  }

  def execute(request: ShellRequest): IO[ShellResult] =
    Resource.make(serial(IO {
      ensureOpen()
      validateScript(request)
      val entry = find(request.env)
      if request.cwd.nonEmpty && entry.environment.target.nonEmpty then throw ShellFailure("invalid_shell", "cwd is supported for local Bash only; use cd or Set-Location in remote scripts")
      entry.users += 1
      entry
    }))(entry => serial(IO { entry.users -= 1 } *> IO.defer(closeIfIdle(entry))))
      .use(_.run(request)).adaptError {
        case error: BashFailure => failure(error.code, error.getMessage)
        case error: BashOnR6sFailure => failure(error.code, error.getMessage)
        case error: ZshOnMacFailure => failure(error.code, error.getMessage)
        case error: PowerShellFailure => failure(error.code, error.getMessage)
      }

  private def close: IO[Unit] = serial {
    IO {
      stopping = true
      val all = (entries.values.toSet ++ retired).toVector
      entries = Map.empty
      all.foreach { entry => entry.retired = true; retired += entry }
      all
    }.flatTap(_.traverse_(closeIfIdle))
  }.flatMap(_.traverse_(_.closed.get))

object ShellService:
  private type Run = ShellRequest => IO[ShellResult]
  private val maxBytes = 64 * 1024

  private def duration(value: String): Long =
    try Validation.durationMs("timeout", value)
    catch case error: InvalidGraalTask => throw ShellFailure("invalid_shell", error.getMessage)

  private def validateEnvironment(environment: ShellEnvironment): Unit =
    if environment.name.trim.isEmpty || environment.name.contains('\u0000') || environment.name.codePointCount(0, environment.name.length) > 128 then
      throw ShellFailure("invalid_shell", "Environment name must be nonblank, without NUL bytes, and at most 128 characters")
    environment.target.foreach { target =>
      if target.isEmpty || target.startsWith("-") || target.exists(c => c.isWhitespace || c == '\u0000') then
        throw ShellFailure("invalid_shell", "Invalid SSH target")
    }
    if environment.target.isEmpty && environment.shell != ShellKind.bash then
      throw ShellFailure("invalid_shell", "Local environments use Bash; Zsh and PowerShell require an SSH target")
    if duration(environment.idle) < 1000 then throw ShellFailure("invalid_shell", "SSH idle timeout must be at least one second")

  private def validateScript(request: ShellRequest): Unit =
    if request.script.stripPrefix("\uFEFF").trim.isEmpty || request.script.contains('\u0000') then
      throw ShellFailure("invalid_shell", "script must be nonblank and contain no NUL")
    if request.script.getBytes(UTF_8).length > maxBytes then throw ShellFailure("shell_limit", "Shell script exceeds 64 KiB")
    duration(request.timeout)
    ()

  private def failure(code: String, message: String): ShellFailure =
    val normalized = if code.endsWith("_limit") then "shell_limit"
      else if code.endsWith("_timeout") then "shell_timeout"
      else if code.startsWith("invalid_") then "invalid_shell"
      else "shell_ssh"
    ShellFailure(normalized, message)

  private def backend(home: Path, environment: ShellEnvironment, ssh: String): Resource[IO, Run] =
    environment.target match
      case None => Resource.pure[IO, Run] { request =>
        new BashService().apply(BashRequest(request.script, request.cwd, request.timeout)).map(r => ShellResult(r.exitCode, r.stdout, r.stderr))
      }
      case Some(target) => environment.shell match
        case ShellKind.bash => BashOnR6sService.resource(home, target, environment.idle, ssh).map { service =>
          request => service(request.script, request.timeout).map(r => ShellResult(r.exitCode, r.stdout, r.stderr))
        }
        case ShellKind.zsh => ZshOnMacService.resource(home, target, environment.idle, ssh).map { service =>
          request => service(request.script, request.timeout).map(r => ShellResult(r.exitCode, r.stdout, r.stderr))
        }
        case ShellKind.powershell => PowerShellService.resource(home, target, environment.idle, ssh).map { service =>
          request => service(request.script, request.timeout).map(r => ShellResult(r.exitCode, r.stdout, r.stderr))
        }

  /** Import the previous deployment targets once. Persisted changes, including deletions, win on later starts. */
  def defaults: Vector[ShellEnvironment] = Vector(
    ShellEnvironment("local"),
    ShellEnvironment("windows", ShellKind.powershell, Some(sys.env.getOrElse("POWERSHELL_SSH_TARGET", "windows")), sys.env.getOrElse("POWERSHELL_SSH_IDLE", "5m")),
    ShellEnvironment("mac", ShellKind.zsh, Some(sys.env.getOrElse("ZSH_ON_MAC_SSH_TARGET", "weichenglong@100.64.0.5")), sys.env.getOrElse("ZSH_ON_MAC_SSH_IDLE", "5m")),
    ShellEnvironment("r6s", ShellKind.bash, Some(sys.env.getOrElse("BASH_ON_R6S_SSH_TARGET", "router@100.64.0.7")), sys.env.getOrElse("BASH_ON_R6S_SSH_IDLE", "5m"))
  )

  def resource(home: Path = groot.schedule.Store.defaultHome.resolve("shell"), initial: Vector[ShellEnvironment] = defaults,
      ssh: String = "/usr/bin/ssh"): Resource[IO, ShellService] = for
    directory <- Resource.eval(IO(home.toAbsolutePath.normalize()))
    _ <- Resource.make(IO.blocking {
      Files.createDirectories(directory)
      val channel = FileChannel.open(directory.resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        if channel.tryLock() == null then throw new IllegalStateException(s"A shell service already owns $directory")
        channel
      catch
        case error: Throwable =>
          channel.close()
          throw error
    })(channel => IO.blocking(channel.close()))
    mutex <- Resource.eval(Mutex[IO])
    service <- Resource.make(IO(new ShellService(directory, ssh, mutex)))(_.close)
    _ <- Resource.eval(service.initialize(initial))
  yield service
