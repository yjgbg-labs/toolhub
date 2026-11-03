package groot.chrome

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.model.JsonModel
import io.circe.parser.parse
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Using

private[chrome] final class ChromeStore(val home: Path):
  def directory(name: String): Path =
    ChromeValidation.name(name)
    home.resolve("instances").resolve(name)
  def profile(name: String): Path = directory(name).resolve("profile")
  def prepare(name: String): IO[Unit] = IO.blocking {
    val dir = directory(name)
    if Files.isSymbolicLink(dir) || Files.isSymbolicLink(profile(name)) then
      throw ChromeFailure("chrome_conflict", "Instance and profile directories must not be symbolic links")
    Files.createDirectories(profile(name))
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
  }
  def save(value: SavedInstance): IO[Unit] = IO.blocking {
    val temporary = Files.createTempFile(directory(value.instanceName), ".instance-", ".json")
    try
      Files.writeString(temporary, JsonModel[SavedInstance].encoder(value).noSpaces)
      Files.move(temporary, directory(value.instanceName).resolve("instance.json"),
        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temporary)
    ()
  }
  def load: IO[Vector[SavedInstance]] = IO.blocking {
    Using.resource(Files.list(home.resolve("instances"))) { paths => paths.iterator().asScala.toVector
      .filter(path => Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(path.resolve("instance.json")))
      .map { path =>
        val state = parse(Files.readString(path.resolve("instance.json")))
          .flatMap(JsonModel[SavedInstance].decoder.decodeJson).fold(throw _, identity)
        if directory(state.instanceName) != path then throw ChromeFailure("chrome_conflict", s"Invalid instance metadata: $path")
        state
      }
    }
  }
  def remove(name: String): IO[Unit] = IO.blocking {
    val dir = directory(name)
    if Files.exists(dir, LinkOption.NOFOLLOW_LINKS) then
      Using.resource(Files.walk(dir))(_.iterator().asScala.toVector.reverse.foreach(Files.delete))
  }
  def endpoint(name: String, process: ProcessHandle): IO[URI] =
    def poll: IO[URI] = IO.blocking {
      if !process.isAlive then throw ChromeFailure("chrome_start_failed", s"Chromium exited; see ${directory(name).resolve("chrome.log")}")
      val path = profile(name).resolve("DevToolsActivePort")
      if !Files.exists(path) then None
      else
        val lines = Files.readAllLines(path).asScala
        if lines.size < 2 then None
        else
          val port = lines(0).toInt
          if port < 1 || port > 65535 || !lines(1).matches("/devtools/browser/[A-Za-z0-9-]+") then
            throw ChromeFailure("chrome_start_failed", "Invalid Chrome debugging endpoint")
          Some(URI.create(s"ws://127.0.0.1:$port${lines(1)}"))
    }.flatMap(_.fold(IO.sleep(100.millis) *> poll)(IO.pure))
    poll.timeoutTo(20.seconds, IO.raiseError(ChromeFailure("chrome_timeout", s"Chromium startup timed out; see ${directory(name).resolve("chrome.log")}")))

  def owned(value: SavedInstance): Option[ProcessHandle] = for
    pid <- value.pid
    process <- ProcessHandle.of(pid).toScala.filter(_.isAlive)
    started <- process.info().startInstant().toScala
    if value.startedAt.contains(started.toString)
    expected = s"--user-data-dir=${profile(value.instanceName)}"
    // Chromium's process-title rewrite can collapse argv into a single string.
    if process.info().arguments().toScala.exists(_.contains(expected)) || {
      try (" " + Files.readString(Path.of(s"/proc/$pid/cmdline")).replace('\u0000', ' ') + " ").contains(" " + expected + " ")
      catch case _: java.io.IOException => false
    }
  yield process

private[chrome] object ChromeStore:
  def defaultHome: Path = Path.of(sys.env.getOrElse("CHROME_HOME", s"${System.getProperty("user.home")}/groot-chrome")).toAbsolutePath.normalize()
  def resource(home: Path): Resource[IO, ChromeStore] = Resource.make(IO.blocking {
    Files.createDirectories(home.resolve("instances"))
    Files.createDirectories(home.resolve("screenshots"))
    Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
    val channel = FileChannel.open(home.resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try
      if channel.tryLock() == null then throw ChromeFailure("chrome_conflict", s"Another Chrome service owns $home")
      channel
    catch case error: Throwable =>
      channel.close()
      throw error
  })(channel => IO.blocking(channel.close())).as(new ChromeStore(home))

  def binary: String = sys.env.get("CHROME_BINARY").filter(_.nonEmpty).getOrElse {
    Vector("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/snap/bin/chromium", "/usr/bin/chromium", "/usr/bin/chromium-browser")
      .find(path => Files.isExecutable(Path.of(path))).getOrElse(throw ChromeFailure("chrome_unavailable", "Install Chromium or set CHROME_BINARY"))
  }

  def launch(executable: String, store: ChromeStore, state: SavedInstance): IO[ProcessHandle] = IO.blocking {
    val profile = store.profile(state.instanceName)
    Files.deleteIfExists(profile.resolve("DevToolsActivePort"))
    val args = Vector(executable, s"--user-data-dir=$profile", "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
      "--no-first-run", "--no-default-browser-check", "--disable-session-crashed-bubble", "--window-size=1280,800") ++
      (if state.headless then Vector("--headless=new") else if state.kiosk then Vector("--kiosk") else Vector.empty) ++ Vector("about:blank")
    val builder = new ProcessBuilder(new java.util.ArrayList(args.asJava)).redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(store.directory(state.instanceName).resolve("chrome.log").toFile))
    if !state.headless then
      val env = builder.environment()
      def option(key: String, platform: String) = sys.env.get(key).filter(_.nonEmpty).map(platform -> _)
      val chosen = option("CHROME_WAYLAND_DISPLAY", "wayland").orElse(option("CHROME_DISPLAY", "x11"))
        .orElse(option("DISPLAY", "x11")).orElse(option("WAYLAND_DISPLAY", "wayland"))
        .orElse(Option.when(Files.exists(Path.of("/tmp/.X11-unix/X0")))(("x11", ":0")))
        .orElse(sys.env.get("XDG_RUNTIME_DIR").flatMap { dir =>
          Using.resource(Files.list(Path.of(dir)))(_.iterator().asScala.map(_.getFileName.toString)
            .filter(_.matches("wayland-[0-9]+")).toVector.sorted.headOption.map("wayland" -> _))
        }).getOrElse(throw ChromeFailure("chrome_unavailable", "No graphical session found; use headless=true or configure CHROME_DISPLAY/CHROME_WAYLAND_DISPLAY"))
      env.put(if chosen._1 == "wayland" then "WAYLAND_DISPLAY" else "DISPLAY", chosen._2)
      builder.command().add(1, s"--ozone-platform=${chosen._1}")
    val process = builder.start()
    process.getOutputStream.close()
    process.toHandle
  }

  def terminate(process: ProcessHandle): IO[Unit] = IO.uncancelable { _ =>
    IO.blocking(Using.resource(process.descendants())(_.iterator().asScala.toVector) :+ process).flatMap { all =>
      IO.blocking(all.reverse.foreach(p => if p.isAlive then p.destroy())) *>
        IO.fromCompletableFuture(IO(process.onExit())).void.timeoutTo(3.seconds, IO.unit) *>
        IO.blocking(all.reverse.foreach(p => if p.isAlive then p.destroyForcibly())).void
    }
  }
