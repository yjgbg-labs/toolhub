package groot.wine

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

private[wine] case class WineConfig(home: Path, runtime: Path, helper: Path):
  val binary: Path = runtime.resolve("usr/lib/x86_64-linux-gnu/wine/wine")
  val server: Path = runtime.resolve("usr/lib/x86_64-linux-gnu/wine/wineserver")
  val xvfb: Path = runtime.resolve("usr/bin/Xvfb")
  def env(prefix: Path): Map[String, String] = Map(
    "WINEPREFIX" -> prefix.toString, "WINEARCH" -> "win64", "WINEDEBUG" -> "-all", "WINESERVER" -> server.toString,
    "WINEDLLOVERRIDES" -> "winemenubuilder.exe=d;mscoree=d",
    "LD_LIBRARY_PATH" -> runtime.resolve("usr/lib/x86_64-linux-gnu").toString)
private[wine] object WineConfig:
  def default: WineConfig =
    val home = System.getProperty("user.home")
    WineConfig(Path.of(sys.env.getOrElse("WINE_HOME", s"$home/groot-wine")),
      Path.of(sys.env.getOrElse("WINE_RUNTIME_ROOT", s"$home/.local/share/groot-wine/runtime")),
      Path.of(sys.env.getOrElse("WINE_HELPER_ROOT", s"$home/.local/share/groot-wine/helper")))
private[wine] case class WineEnvironment(prefix: Path, variables: Map[String, String])

private[wine] object WineRuntime:
  def builder(command: Vector[String], env: Map[String, String]): ProcessBuilder =
    val result = new ProcessBuilder(command.asJava)
    result.environment().putAll(env.asJava)
    result.environment().remove("WAYLAND_DISPLAY")
    result
  def terminate(process: Process): IO[Unit] = IO.uncancelable { _ =>
    IO.blocking { if process.isAlive then process.destroyForcibly(); () } *>
      IO.fromCompletableFuture(IO(process.onExit())).void.timeoutTo(5.seconds, IO.unit)
  }
  def command(args: Vector[String], env: Map[String, String], log: Path, timeout: FiniteDuration = 30.seconds): IO[Int] =
    Resource.make(IO.blocking {
      val child = builder(args, env).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile)).start()
      child.getOutputStream.close()
      child
    })(terminate).use(p => IO.fromCompletableFuture(IO(p.onExit())).map(_.exitValue()).timeoutTo(timeout,
      IO.raiseError(WineFailure("wine_timeout", s"Wine setup timed out; see $log"))))
  def stopPrefix(config: WineConfig): IO[Unit] =
    val env = config.env(config.home.resolve("prefix"))
    command(Vector(config.server.toString, "-k"), env, config.home.resolve("wine.log")).void *>
      command(Vector(config.server.toString, "-w"), env, config.home.resolve("wine.log")).void

  def framework(config: WineConfig, env: WineEnvironment, architecture: String): IO[Unit] = IO.defer {
    val windows = env.prefix.resolve("drive_c/windows")
    val framework = if architecture == "x64" then "Framework64" else "Framework"
    val installed = Files.exists(windows.resolve("mono/mono-2.0/bin/mono.exe")) ||
      Files.exists(windows.resolve(s"Microsoft.NET/$framework/v4.0.30319/clr.dll"))
    if installed then IO.unit else
      command(Vector(config.binary.toString, "msiexec.exe", "/i", WineValidation.windows(config.runtime.resolve("wine-mono-9.4.0-x86.msi").toString), "/qn", "/norestart"),
        env.variables, config.home.resolve("mono-install.log"), 120.seconds).flatMap(code =>
          IO.raiseWhen(code != 0)(WineFailure("wine_start_failed", s"Wine Mono installation exited $code; see mono-install.log")))
  }

  /** One automatically managed, persistent prefix; initialized lazily and shared by short-lived calls. */
  def resource(config: WineConfig): Resource[IO, WineEnvironment] =
    val prefix = config.home.resolve("prefix")
    val log = config.home.resolve("wine.log")
    val base = config.env(prefix)
    for
      _ <- Resource.eval(IO.blocking {
        if !Files.isExecutable(config.binary) || !Files.isExecutable(config.server) || !Files.isExecutable(config.xvfb) then
          throw WineFailure("wine_unavailable", "Install Wine using wine-install or configure WINE_RUNTIME_ROOT")
        Files.createDirectories(config.home)
        Files.createDirectories(prefix)
        for (target, arch) <- Vector("system32" -> "x86_64", "syswow64" -> "i686") do
          val destination = prefix.resolve("drive_c/windows").resolve(target)
          Files.createDirectories(destination)
          if !Files.exists(destination.resolve("zlib1.dll")) then
            Files.copy(config.runtime.resolve(s"usr/$arch-w64-mingw32/lib/zlib1.dll"), destination.resolve("zlib1.dll"))
      })
      _ <- Resource.fromAutoCloseable(IO.blocking {
        val file = FileChannel.open(config.home.resolve("executor.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try
          if file.tryLock() == null then throw WineFailure("wine_unavailable", "Another service owns this Wine environment")
          file
        catch case error: Throwable =>
          file.close()
          throw error
      })
      _ <- Resource.eval(stopPrefix(config))
      display <- Resource.make(IO.blocking {
        val p = builder(Vector(config.xvfb.toString, "-displayfd", "1", "-screen", "0", "1280x800x24", "-nolisten", "tcp"), base)
          .redirectError(ProcessBuilder.Redirect.appendTo(config.home.resolve("xvfb.log").toFile)).start()
        p.getOutputStream.close()
        p
      })(terminate)
      number <- Resource.eval(IO.async[String] { callback => IO {
        Thread.ofVirtual().name("wine-display").start(() => {
          try
            val reader = new java.io.BufferedReader(new java.io.InputStreamReader(display.getInputStream))
            val line = reader.readLine()
            if line == null || !line.matches("[0-9]{1,5}") then throw WineFailure("wine_unavailable", "Xvfb did not allocate a display")
            callback(Right(":" + line))
          catch case scala.util.control.NonFatal(error) => callback(Left(error))
        })
        Some(terminate(display))
      }}.timeout(10.seconds))
      env = base + ("DISPLAY" -> number)
      _ <- Resource.make(IO.unit)(_ => stopPrefix(config))
      server <- Resource.eval(command(Vector(config.server.toString, "-p"), env, log))
      _ <- Resource.eval(IO.raiseWhen(server != 0)(WineFailure("wine_start_failed", s"wineserver exited $server; see $log")))
      boot <- Resource.eval(command(Vector(config.binary.toString, "wineboot.exe", "-u"), env, log, 90.seconds))
      _ <- Resource.eval(IO.raiseWhen(boot != 0)(WineFailure("wine_start_failed", s"wineboot exited $boot; see $log")))
    yield WineEnvironment(prefix, env)
