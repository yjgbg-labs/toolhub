package groot.wine

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.{Semaphore, Supervisor}
import cats.syntax.all.*
import framework.model.JsonModel
import framework.rpc.{PlainText, SSE}
import fs2.{Chunk, Stream}
import io.circe.Json
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

final class WineService private (config: WineConfig, store: WineStore, entries: Ref[IO, Map[String, WineService.Entry]],
    runs: Ref[IO, Map[String, WineService.Run]], lifecycle: Semaphore[IO], supervisor: Supervisor[IO]):
  import WineService.*
  private def entry(name: String): IO[Entry] = IO(WineValidation.name(name)) *> entries.get.flatMap(_.get(name)
    .liftTo[IO](WineFailure("wine_not_found", s"Unknown instance: $name")))
  private def info(e: Entry): WineInstance = WineInstance(e.saved.instanceName, if e.live.nonEmpty then "running" else "stopped", e.saved.arch, e.saved.headless,
    store.instance(e.saved.instanceName).resolve("prefix").toString)
  private def live(e: Entry): IO[WineEnvironment] = IO.fromOption(e.live.map(_._1))(WineFailure("wine_stopped", "Start the Wine instance first"))
  private def payload[A: JsonModel](op: String, value: A): Json = JsonModel[A].encoder(value).deepMerge(Json.obj("op" -> Json.fromString(op)))
  def list: IO[Vector[WineInstance]] = entries.get.flatMap(_.values.toVector.traverse(e => e.gate.permit.use(_ => IO(info(e))))).map(_.sortBy(_.instanceName))

  def start(name: String, arch: String, headless: Boolean): IO[WineInstance] = IO {
    WineValidation.name(name)
    if !Set("win32", "win64").contains(arch) then throw WineFailure("invalid_wine", "arch must be win32 or win64")
  } *> lifecycle.permit.use { _ => for
    existing <- entries.get.map(_.get(name))
    e <- existing.fold(for
      gate <- Semaphore[IO](1)
      value = new Entry(SavedWine(name, arch, headless), gate)
      _ <- store.save(value.saved)
      _ <- entries.update(_ + (name -> value))
    yield value)(IO.pure)
    result <- e.gate.permit.use { _ => IO.uncancelable { poll => for
      _ <- IO.raiseWhen(e.saved.arch != arch || e.saved.headless != headless)(WineFailure("wine_conflict", "Instance architecture/display mode is fixed; use a new instance name"))
      _ <- if e.live.nonEmpty then IO.unit else poll(WineRuntime.start(config, store, e.saved).allocated).flatMap(value => IO { e.live = Some(value) })
    yield info(e) } }
  yield result }

  private def stopEntry(e: Entry): IO[WineInstance] = IO.uncancelable { _ => for
    active <- runs.get.map(_.values.filter(_.initial.instanceName == e.saved.instanceName).toVector)
    _ <- active.traverse_(_.cancel.complete("cancelled").void)
    _ <- active.traverse_(_.done.get)
    sessions <- IO { val all = e.sessions.values.toVector; e.sessions = Map.empty; all }
    _ <- sessions.parTraverse_(_._2)
    owned <- IO { val value = e.live; e.live = None; value }
    _ <- owned.traverse_(_._2)
    _ <- store.save(e.saved.copy(displayPid = None, displayStartedAt = None))
  yield info(e) }
  def stop(name: String): IO[WineInstance] = lifecycle.permit.use(_ => entry(name).flatMap(e => e.gate.permit.use(_ => stopEntry(e))))
  def purge(name: String): IO[Boolean] = IO(WineValidation.name(name)) *> lifecycle.permit.use { _ =>
    entries.get.map(_.get(name)).flatMap {
      case None => IO.pure(false)
      case Some(e) => e.gate.permit.use(_ => stopEntry(e) *> store.remove(store.instance(name)) *> entries.update(_ - name).as(true))
    }
  }

  def load(name: String, dllName: String, request: WineLoad): IO[WineDll] = IO {
    WineValidation.name(dllName)
    WineValidation.architecture(request.architecture)
    if !Set("dotnet", "framework").contains(request.runtime) then throw WineFailure("invalid_wine", "runtime must be dotnet or framework")
    if !Set("native", "dotnet", "com").contains(request.kind) then throw WineFailure("invalid_wine", "kind must be native, dotnet or com")
    if request.kind == "com" && request.classId.forall(_.isEmpty) then throw WineFailure("invalid_wine", "COM requires classId (CLSID or ProgID)")
    if request.kind != "com" && request.path.forall(_.isEmpty) then throw WineFailure("invalid_wine", "DLL path is required")
    request.path.foreach(WineValidation.windows)
  } *> entry(name).flatMap { e => e.gate.permit.use { _ => IO.uncancelable { poll => for
    env <- live(e)
    _ <- IO.raiseWhen(e.sessions.contains(dllName))(WineFailure("wine_conflict", "DLL session name already exists; unload it first"))
    _ <- IO.raiseWhen(e.sessions.size >= 32)(WineFailure("wine_limit", "At most 32 DLL sessions per instance"))
    directory = store.instance(name).resolve("sessions").resolve(dllName + "-" + UUID.randomUUID().toString)
    _ <- IO.blocking(Files.createDirectories(directory))
    _ <- if request.runtime == "framework" then poll(WineRuntime.framework(config, env)) else IO.unit
    allocated <- poll((for
      worker <- WineWorker.resource(config, env, request.architecture, directory.resolve("stdout.log"), directory.resolve("stderr.log"), request.runtime)
      _ <- Resource.eval(worker.call(payload("load", request.copy(path = request.path.map(WineValidation.windows))), 30000))
    yield worker).allocated)
    (worker, release) = allocated
    value = WineDll(name, dllName, request.kind, request.architecture, request.runtime, "loaded", request.path, request.classId, directory.toString)
    _ <- IO { e.sessions += dllName -> (new Session(value, worker), release) }
  yield value } } }
  def dlls(name: String): IO[Vector[WineDll]] = entry(name).flatMap(e => e.gate.permit.use(_ => IO {
    e.sessions.values.toVector.map { (s, _) => s.value.copy(status = if s.worker.alive then "loaded" else "failed") }.sortBy(_.dllName)
  }))
  private def session(name: String, dllName: String): IO[Session] = entry(name).flatMap(e => e.gate.permit.use(_ =>
    IO.fromOption(e.sessions.get(dllName).map(_._1))(WineFailure("wine_not_found", s"Unknown DLL session: $dllName"))))
  def call(name: String, dllName: String, request: WineCall, timeoutMs: Int): IO[Json] =
    session(name, dllName).flatMap(_.worker.call(payload("call", request), timeoutMs))
  def inspect(name: String, dllName: String, typeName: Option[String]): IO[Json] = session(name, dllName).flatMap(_.worker.call(Json.obj(
    "op" -> Json.fromString("inspect"), "typeName" -> typeName.fold(Json.Null)(Json.fromString)), 10000))
  def release(name: String, dllName: String, handle: String): IO[Boolean] = session(name, dllName).flatMap(_.worker.call(Json.obj(
    "op" -> Json.fromString("release"), "handle" -> Json.fromString(handle)), 10000)).map(_.asBoolean.contains(true))
  def unload(name: String, dllName: String): IO[Boolean] = entry(name).flatMap(e => e.gate.permit.use { _ => IO.uncancelable { _ =>
    IO { val value = e.sessions.get(dllName); e.sessions -= dllName; value }.flatMap(value => value.traverse_(_._2).as(value.nonEmpty))
  } })

  def execute(name: String, request: WineExec): IO[WineTask] = IO {
    WineValidation.timeout(request.timeoutMs)
    WineValidation.architecture(request.architecture)
    WineValidation.windows(request.exe)
    request.cwd.foreach(WineValidation.windows)
    if request.args.exists(_.contains('\u0000')) || request.env.exists((k, v) => k.isEmpty || k.contains('=') || k.contains('\u0000') || v.contains('\u0000')) then
      throw WineFailure("invalid_wine", "Invalid argument or environment variable")
    if request.args.map(_.length.toLong).sum > 32768 || request.env.size > 128 || request.stdinBase64.length > 512 * 1024 then
      throw WineFailure("invalid_wine", "Process request exceeds its limit")
    if JsonModel[WineExec].encoder(request).noSpaces.getBytes(UTF_8).length > WineWorker.maxFrame - 1024 then
      throw WineFailure("invalid_wine", "Process request exceeds 1 MiB")
    try Base64.getDecoder.decode(request.stdinBase64) catch case _: IllegalArgumentException => throw WineFailure("invalid_wine", "stdinBase64 must be valid Base64")
  } *> entry(name).flatMap(e => e.gate.permit.use { _ => IO.uncancelable { poll => for
    env <- live(e)
    active <- runs.get
    _ <- IO.raiseWhen(active.size >= 64)(WineFailure("wine_limit", "At most 64 active EXE processes"))
    id = "wine_" + UUID.randomUUID().toString.replace("-", "")
    directory = store.task(id)
    _ <- IO.blocking(Files.createDirectories(directory))
    allocated <- poll(WineWorker.resource(config, env, request.architecture, directory.resolve("stdout.log"), directory.resolve("stderr.log")).allocated)
    (worker, cleanup) = allocated
    task = WineTask(id, name, "running", request.exe, Instant.now().toString, None, None, None,
      directory.resolve("stdout.log").toString, directory.resolve("stderr.log").toString)
    _ <- store.save(task).onError(_ => cleanup)
    cancel <- Deferred[IO, String]
    done <- Deferred[IO, Unit]
    run = new Run(task, cancel, done)
    _ <- runs.update(_ + (id -> run))
    message = Json.obj("op" -> Json.fromString("exec"), "exe" -> Json.fromString(WineValidation.windows(request.exe)),
      "arguments" -> Json.fromValues(request.args.map(Json.fromString)), "cwd" -> Json.fromString(request.cwd.map(WineValidation.windows).getOrElse("C:\\")),
      "env" -> Json.obj(request.env.toVector.map((k, v) => k -> Json.fromString(v))*), "stdinBase64" -> Json.fromString(request.stdinBase64))
    _ <- supervisor.supervise((for
      outcome <- worker.call(message, request.timeoutMs).attempt.race(cancel.get)
      _ <- cleanup
      finished = outcome match
        case Right(reason) => task.copy(state = reason, error = Some(reason))
        case Left(Left(error: WineFailure)) => task.copy(state = if error.code == "wine_timeout" then "timed_out" else "failed", error = Some(error.getMessage))
        case Left(Left(error)) => task.copy(state = "failed", error = Some(error.toString))
        case Left(Right(result)) =>
          val code = result.hcursor.get[Long]("exitCode").getOrElse(-1L)
          task.copy(state = if code == 0 then "succeeded" else "failed", exitCode = Some(code))
      _ <- store.save(finished.copy(finishedAt = Some(Instant.now().toString)))
    yield ()).guarantee(runs.update(_ - id) *> done.complete(()).void))
  yield task } })

  def status(id: String): IO[WineTask] = store.read[WineTask](store.task(id).resolve("task.json"))
  def tasks(name: Option[String]): IO[Vector[WineTask]] = store.directories(store.home.resolve("tasks")).flatMap { paths =>
    paths.sortBy(_.getFileName.toString).traverse(p => store.read[WineTask](p.resolve("task.json"))).map(_.filter(t => name.forall(_ == t.instanceName)).sortBy(_.createdAt).reverse.take(200))
  }
  def await(id: String, timeoutMs: Int): IO[WineTask] = IO(WineValidation.timeout(timeoutMs)) *> runs.get.flatMap(_.get(id)
    .traverse_(_.done.get)).timeoutTo(timeoutMs.millis, IO.raiseError(WineFailure("wine_wait_timeout", "Wait timed out; the process continues running"))) *> status(id)
  def cancel(id: String): IO[WineTask] = status(id) *> runs.get.flatMap(_.get(id).traverse_(r => r.cancel.complete("cancelled") *> r.done.get)) *> status(id)
  private def logPath(id: String, stream: String): IO[Path] =
    if !Set("stdout", "stderr").contains(stream) then IO.raiseError(WineFailure("invalid_wine", "stream must be stdout or stderr"))
    else status(id).map(t => Path.of(if stream == "stdout" then t.stdoutPath else t.stderrPath))
  def logs(id: String, stream: String): IO[String] = logPath(id, stream).flatMap(path => IO.blocking {
    val file = new RandomAccessFile(path.toFile, "r")
    try
      val count = math.min(file.length(), 65536L).toInt
      file.seek(file.length() - count)
      val bytes = new Array[Byte](count)
      file.readFully(bytes)
      new String(bytes, UTF_8)
    finally file.close()
  })
  def follow(id: String, stream: String): IO[SSE[PlainText]] = logPath(id, stream).map { path =>
    SSE(Stream.resource(Resource.fromAutoCloseable(IO.blocking(new RandomAccessFile(path.toFile, "r")))).flatMap { file =>
      def read: IO[Array[Byte]] = IO.blocking {
        val bytes = new Array[Byte](8192)
        val count = file.read(bytes)
        if count <= 0 then Array.emptyByteArray else java.util.Arrays.copyOf(bytes, count)
      }
      def drain: Stream[IO, Byte] = Stream.repeatEval(read).takeWhile(_.nonEmpty).flatMap(b => Stream.chunk(Chunk.array(b)))
      def loop: Stream[IO, Byte] = Stream.eval(read).flatMap { bytes =>
        if bytes.nonEmpty then Stream.chunk(Chunk.array(bytes)) ++ loop
        else Stream.eval(status(id)).flatMap(t => if t.terminal then drain else Stream.sleep_[IO](100.millis) ++ loop)
      }
      loop.through(fs2.text.utf8.decode).through(fs2.text.lines).map(PlainText(_))
    })
  }
  private def recover: IO[Unit] = for
    paths <- store.directories(store.home.resolve("instances"))
    _ <- paths.traverse_ { directory => for
      saved <- store.read[SavedWine](directory.resolve("instance.json"))
      _ <- IO.raiseWhen(store.instance(saved.instanceName) != directory)(WineFailure("wine_conflict", "Invalid instance metadata"))
      _ <- if Files.isExecutable(config.server) then WineRuntime.stopPrefix(config, directory.resolve("prefix"), directory.resolve("wine.log")) else IO.unit
      _ <- IO.blocking { for
        pid <- saved.displayPid
        p <- ProcessHandle.of(pid).toScala
        started <- p.info().startInstant().toScala
        if saved.displayStartedAt.contains(started.toString)
      do p.destroyForcibly() }
      gate <- Semaphore[IO](1)
      clean = saved.copy(displayPid = None, displayStartedAt = None)
      _ <- store.save(clean) *> entries.update(_ + (saved.instanceName -> new Entry(clean, gate)))
    yield () }
    taskPaths <- store.directories(store.home.resolve("tasks"))
    _ <- taskPaths.traverse_(p => store.read[WineTask](p.resolve("task.json")).flatMap(t =>
      if t.terminal then IO.unit else store.save(t.copy(state = "interrupted", finishedAt = Some(Instant.now().toString), error = Some("Server restarted")))))
  yield ()
  private def close: IO[Unit] = entries.get.flatMap(_.values.toVector.parTraverse_(e => e.gate.permit.use(_ => stopEntry(e)).void))

object WineService:
  private class Entry(val saved: SavedWine, val gate: Semaphore[IO]):
    var live: Option[(WineEnvironment, IO[Unit])] = None
    var sessions = Map.empty[String, (Session, IO[Unit])]
  private class Session(val value: WineDll, val worker: WineWorker)
  private class Run(val initial: WineTask, val cancel: Deferred[IO, String], val done: Deferred[IO, Unit])
  def resource(): Resource[IO, WineService] = managed(WineConfig.default)
  private[wine] def managed(config: WineConfig): Resource[IO, WineService] = for
    store <- WineStore.resource(config.home)
    entries <- Resource.eval(Ref.of[IO, Map[String, Entry]](Map.empty))
    runs <- Resource.eval(Ref.of[IO, Map[String, Run]](Map.empty))
    lifecycle <- Resource.eval(Semaphore[IO](1))
    supervisor <- Supervisor[IO](await = true)
    service = new WineService(config, store, entries, runs, lifecycle, supervisor)
    _ <- Resource.make(service.recover)(_ => service.close)
  yield service
