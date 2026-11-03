package groot.wine

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.util.concurrent.CompletableFuture
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

private[wine] case class WineConfig(home: Path, runtime: Path, helper: Path):
  val binary: Path = runtime.resolve("usr/lib/x86_64-linux-gnu/wine/wine")
  val server: Path = runtime.resolve("usr/lib/x86_64-linux-gnu/wine/wineserver")
  val xvfb: Path = runtime.resolve("usr/bin/Xvfb")
  def check(): Unit =
    if !Files.isExecutable(binary) || !Files.isExecutable(server) then
      throw WineFailure("wine_unavailable", "Install Wine using the wine-install job or configure WINE_RUNTIME_ROOT")
  def env(prefix: Path): Map[String, String] = Map(
    "WINEPREFIX" -> prefix.toString, "WINEDEBUG" -> "-all", "WINESERVER" -> server.toString,
    "WINEDLLOVERRIDES" -> "winemenubuilder.exe=d;mscoree=d",
    "LD_LIBRARY_PATH" -> runtime.resolve("usr/lib/x86_64-linux-gnu").toString)

private[wine] object WineConfig:
  def default: WineConfig =
    val home = System.getProperty("user.home")
    WineConfig(Path.of(sys.env.getOrElse("WINE_HOME", s"$home/sprout-wine")),
      Path.of(sys.env.getOrElse("WINE_RUNTIME_ROOT", s"$home/.local/share/sprout-wine/runtime")),
      Path.of(sys.env.getOrElse("WINE_HELPER_ROOT", s"$home/.local/share/sprout-wine/helper")))

private[wine] case class WineEnvironment(prefix: Path, variables: Map[String, String])

private[wine] object WineRuntime:
  /** Cancellable blocking IO: cancellation closes the OS resource before waiting for the reader. */
  def async[A](action: => A)(cancel: IO[Unit]): IO[A] = IO.async { callback => IO {
    Thread.ofVirtual().name("wine-io").start(() => {
      try callback(Right(action)) catch case scala.util.control.NonFatal(error) => callback(Left(error))
    })
    Some(cancel)
  }}
  def builder(command: Vector[String], env: Map[String, String]): ProcessBuilder =
    val result = new ProcessBuilder(command.asJava)
    result.environment().putAll(env.asJava)
    // Windows helpers use a private X display, never inherit the user's Wayland session.
    result.environment().remove("WAYLAND_DISPLAY")
    result
  def terminate(process: Process): IO[Unit] = IO.uncancelable { _ =>
    IO.blocking { if process.isAlive then process.destroyForcibly(); () } *>
      IO.fromCompletableFuture(IO(process.onExit())).void.timeoutTo(5.seconds, IO.unit)
  }
  def command(command: Vector[String], env: Map[String, String], log: Path, timeout: FiniteDuration = 30.seconds): IO[Int] =
    Resource.make(IO.blocking {
      val child = builder(command, env).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile)).start()
      child.getOutputStream.close()
      child
    })(terminate).use(p => IO.fromCompletableFuture(IO(p.onExit())).map(_.exitValue()).timeoutTo(timeout,
      IO.raiseError(WineFailure("wine_timeout", s"Wine command timed out; see $log"))))
  def stopPrefix(config: WineConfig, prefix: Path, log: Path): IO[Unit] =
    command(Vector(config.server.toString, "-k"), config.env(prefix), log).void *>
      command(Vector(config.server.toString, "-w"), config.env(prefix), log).void

  def framework(config: WineConfig, env: WineEnvironment): IO[Unit] = IO.defer {
    val windows = env.prefix.resolve("drive_c/windows")
    val installed = Files.exists(windows.resolve("mono/mono-2.0/bin/mono.exe")) ||
      Files.exists(windows.resolve("Microsoft.NET/Framework64/v4.0.30319/clr.dll"))
    if installed then IO.unit else
      command(Vector(config.binary.toString, "msiexec.exe", "/i", WineValidation.windows(config.runtime.resolve("wine-mono-9.4.0-x86.msi").toString), "/qn", "/norestart"),
        env.variables, env.prefix.getParent.resolve("mono-install.log"), 120.seconds).flatMap(code =>
          IO.raiseWhen(code != 0)(WineFailure("wine_start_failed", s"Wine Mono installation exited $code; see mono-install.log")))
  }

  def start(config: WineConfig, store: WineStore, saved: SavedWine): Resource[IO, WineEnvironment] =
    val directory = store.instance(saved.instanceName)
    val prefix = directory.resolve("prefix")
    val log = directory.resolve("wine.log")
    val base = config.env(prefix) + ("WINEARCH" -> saved.arch)
    for
      _ <- Resource.eval(IO.blocking {
        config.check()
        if Files.isSymbolicLink(directory) || Files.isSymbolicLink(prefix) then throw WineFailure("wine_conflict", "Wine directories must not be symbolic links")
        Files.createDirectories(prefix)
        for (target, arch) <- Vector("system32" -> (if saved.arch == "win32" then "i686" else "x86_64"), "syswow64" -> "i686") do
          val destination = prefix.resolve("drive_c/windows").resolve(target)
          Files.createDirectories(destination)
          if !Files.exists(destination.resolve("zlib1.dll")) then
            Files.copy(config.runtime.resolve(s"usr/$arch-w64-mingw32/lib/zlib1.dll"), destination.resolve("zlib1.dll"))
      })
      display <- if saved.headless then for
        process <- Resource.make(IO.blocking {
          val p = builder(Vector(config.xvfb.toString, "-displayfd", "1", "-screen", "0", "1280x800x24", "-nolisten", "tcp"), base)
            .redirectError(ProcessBuilder.Redirect.appendTo(directory.resolve("xvfb.log").toFile)).start()
          p.getOutputStream.close()
          p
        })(terminate)
        _ <- Resource.eval(store.save(saved.copy(displayPid = Some(process.pid()), displayStartedAt = process.info().startInstant().toScala.map(_.toString))))
        number <- Resource.eval(async {
          val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
          val line = reader.readLine()
          if line == null || !line.matches("[0-9]{1,5}") then throw WineFailure("wine_unavailable", "Xvfb failed to allocate a display")
          ":" + line
        }(terminate(process)).timeout(10.seconds))
      yield number
      else Resource.eval(IO.fromOption(sys.env.get("DISPLAY").filter(_.nonEmpty).orElse(Option.when(Files.exists(Path.of("/tmp/.X11-unix/X0")))(":0")))(WineFailure("wine_unavailable", "No X11 display available")))
      env = base + ("DISPLAY" -> display)
      _ <- Resource.make(IO.unit)(_ => stopPrefix(config, prefix, log))
      boot <- Resource.eval(command(Vector(config.binary.toString, "wineboot.exe", "-u"), env, log, 90.seconds))
      _ <- Resource.eval(IO.raiseWhen(boot != 0)(WineFailure("wine_start_failed", s"wineboot exited $boot; see $log")))
      _ <- Resource.eval(command(Vector(config.server.toString, "-p"), env, log))
    yield WineEnvironment(prefix, env)
