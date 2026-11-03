package groot.wine

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import framework.model.JsonModel
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class WineService private (config: WineConfig, environment: Ref[IO, Option[(WineEnvironment, IO[Unit])]],
    setup: Semaphore[IO], slots: Semaphore[IO]):
  import WineValidation.*
  private def prepare(runtime: String, architecture: String): IO[WineEnvironment] = setup.permit.use { _ => IO.uncancelable { poll => for
    existing <- environment.get
    env <- existing.fold(poll(WineRuntime.resource(config).allocated).flatTap(v => environment.set(Some(v))).map(_._1))(v => IO.pure(v._1))
    _ <- if runtime == "framework" then poll(WineRuntime.framework(config, env, architecture)) else IO.unit
  yield env } }

  private def validate[A: JsonModel](request: A, timeout: Int, architecture: String, cwd: Option[String], env: Map[String, String]): IO[Unit] = IO {
    require(timeout >= 1 && timeout <= 86400000, "timeoutMs must be between 1 and 86400000")
    require(Set("x64", "x86").contains(architecture), "architecture must be x64 or x86")
    require(JsonModel[A].encoder(request).noSpaces.getBytes(UTF_8).length <= maxBytes, "Request exceeds 1 MiB")
    cwd.foreach(p => require(p.startsWith("/") && !p.contains('\u0000') && Files.isDirectory(Path.of(p)), "cwd must be an existing absolute Linux directory"))
    require(env.size <= 128 && env.forall((k, v) => k.matches("[A-Za-z_][A-Za-z0-9_]*") && !v.contains('\u0000')), "Invalid environment variables")
  }
  private def directory: Resource[IO, Path] = Resource.make(IO.blocking {
    val parent = config.home.resolve("calls")
    Files.createDirectories(parent)
    Files.createTempDirectory(parent, "call-")
  })(p => IO.blocking(Using.resource(Files.walk(p))(_.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists))))

  private def invoke[A](timeout: Int)(run: (Path, Ref[IO, (String, String)]) => IO[A])(stamp: (A, Long) => A): IO[A] = for
    started <- IO.monotonic
    logs <- Ref.of[IO, (String, String)](("", ""))
    elapsed = IO.monotonic.map(now => (now - started).toMillis)
    result <- slots.permit.use(_ => directory.use(p => run(p, logs))).timeoutTo(timeout.millis,
      IO.raiseError(WineFailure("wine_timeout", "Wine invocation timed out"))).handleErrorWith {
        case e: WineFailure => (logs.get, elapsed).tupled.flatMap { case ((stdout, stderr), ms) =>
          IO.raiseError(e.copy(error = e.error.copy(stdout = stdout, stderr = stderr, durationMs = ms)))
        }
        case e: java.io.IOException => (logs.get, elapsed).tupled.flatMap { case ((stdout, stderr), ms) =>
          IO.raiseError(WineFailure(WineError("wine_unavailable", e.getMessage, stdout, stderr, ms)))
        }
      }
    duration <- elapsed
  yield stamp(result, duration)

  private def run(env: WineEnvironment, args: Vector[String], cwd: Option[String], variables: Map[String, String],
      stdin: String, directory: Path, modern: Boolean, logs: Ref[IO, (String, String)]): IO[WineExecResult] = for
    input <- IO.blocking(Files.write(directory.resolve("stdin.bin"), Base64.getDecoder.decode(stdin)))
    overrides = if modern then "winemenubuilder.exe=d;mscoree=b" else "winemenubuilder.exe=d"
    result <- WineProcess.run(Vector(config.binary.toString) ++ args,
      variables ++ env.variables + ("WINEDLLOVERRIDES" -> overrides), cwd.map(Path.of(_)).getOrElse(env.prefix.resolve("drive_c")), input, logs)
  yield result

  def execute(request: WineExec): IO[WineExecResult] =
    validate(request, request.timeoutMs, request.architecture, request.cwd, request.env) *> IO {
      windows(request.exe)
      require(Set("wine", "dotnet", "framework").contains(request.runtime), "runtime must be wine, dotnet or framework")
      require(request.args.forall(!_.contains('\u0000')) && request.args.map(_.length.toLong).sum <= 32768, "Invalid or oversized argument list")
      require(scala.util.Try(Base64.getDecoder.decode(request.stdinBase64)).isSuccess, "stdinBase64 must be valid Base64")
    } *> invoke(request.timeoutMs) { (directory, logs) => for
      env <- prepare(request.runtime, request.architecture)
      args = (if request.runtime == "dotnet" then Vector(config.runtime.resolve(s"dotnet-${request.architecture}/dotnet.exe").toString) else Vector.empty) ++
        Vector(windows(request.exe)) ++ request.args
      result <- run(env, args, request.cwd, request.env, request.stdinBase64, directory, request.runtime == "dotnet", logs)
    yield result }((r, ms) => r.copy(durationMs = ms))

  def call(request: WineCall): IO[WineCallResult] =
    validate(request, request.timeoutMs, request.architecture, request.cwd, request.env) *> IO {
      require(Set("native", "dotnet", "com").contains(request.kind), "kind must be native, dotnet or com")
      require(Set("dotnet", "framework").contains(request.runtime), "runtime must be dotnet or framework")
      require(if request.kind == "com" then request.classId.exists(_.nonEmpty) else request.path.exists(_.nonEmpty), "Provide path or COM classId")
      request.path.foreach(windows)
      require(request.steps.size <= 64, "At most 64 batch steps")
      require(request.steps.isEmpty || request.single == WineStep(), "Use either a single call or steps, not both")
      val names = scala.collection.mutable.Set.empty[String]
      for step <- if request.steps.isEmpty then Vector(request.single) else request.steps do
        val operations = request.kind match
          case "native" => Set("invoke", "read", "inspect", "release")
          case "dotnet" => Set("invoke", "create", "get", "set", "inspect", "release")
          case _ => Set("invoke", "get", "set", "inspect", "release")
        require(operations.contains(step.operation), s"Unsupported ${request.kind} operation")
        require(step.args.size <= 32, "At most 32 arguments per step")
        require(step.target.forall(names.contains), "target must refer to a preceding saveAs")
        if Set("read", "release").contains(step.operation) then require(step.target.nonEmpty, "read/release requires target")
        if step.operation == "read" then
          require(step.size.exists(s => s >= 0 && s <= maxBytes / 2), "read requires size between 0 and 524288")
          require(Set("buffer", "utf8", "utf16").contains(step.returnType), "read returnType must be buffer, utf8 or utf16")
        for arg <- step.args do
          require(arg.ref.forall(names.contains), "Argument ref must refer to a preceding saveAs")
          require(arg.ref.isEmpty || arg.value.isNull, "Argument ref and value are mutually exclusive")
          require(arg.size.forall(s => s >= 0 && s <= maxBytes / 2), "Invalid buffer size")
          require(!Set("i64", "u64").contains(arg.`type`) || arg.ref.nonEmpty || arg.value.isString, "64-bit integer values must be decimal strings")
        step.saveAs.foreach { id =>
          require(id.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && !names.contains(id), "saveAs must be a unique identifier")
          names += id
        }
    } *> invoke(request.timeoutMs) { (directory, logs) => for
      env <- prepare(request.runtime, request.architecture)
      helper <- IO.blocking {
        val root = config.helper.toRealPath()
        val path = root.resolve(if request.runtime == "framework" then s"framework-${request.architecture}/Groot.Wine.Helper.exe" else "Groot.Wine.Helper.dll")
        if !Files.isRegularFile(path) then throw WineFailure("wine_unavailable", "Install the invoker with wine-helper-deploy")
        path
      }
      input = directory.resolve("request.json")
      output = directory.resolve("result.json")
      _ <- IO.blocking(Files.writeString(input, JsonModel[WineCall].encoder(request.copy(path = request.path.map(windows))).noSpaces))
      args = (if request.runtime == "framework" then Vector.empty else Vector(config.runtime.resolve(s"dotnet-${request.architecture}/dotnet.exe").toString)) ++
        Vector(windows(helper.toString), "--request", windows(input.toString), "--result", windows(output.toString))
      process <- run(env, args, request.cwd, request.env, "", directory, request.runtime == "dotnet", logs)
      result <- IO.blocking {
        if !Files.isRegularFile(output) then throw WineFailure("wine_call_failed", s"Invoker exited ${process.exitCode} without a result")
        if Files.size(output) > maxBytes then throw WineFailure("wine_call_failed", "Invoker result exceeds 1 MiB")
        parse(Files.readString(output)).fold(e => throw WineFailure("wine_call_failed", e.message), identity)
      }
      cursor = result.hcursor
      _ <- if cursor.get[Boolean]("ok").contains(true) && process.exitCode == 0 then IO.unit else
        IO.raiseError(WineFailure(WineError("wine_call_failed", cursor.downField("error").get[String]("message").getOrElse(s"Invoker exited ${process.exitCode}"),
          step = cursor.downField("error").get[Int]("step").toOption)))
      value <- IO.fromEither(cursor.downField("result").as[Json]).adaptError(e => WineFailure("wine_call_failed", e.getMessage))
      outputs <- IO.fromEither(JsonModel[Vector[WineOutput]].decoder.decodeJson(value.hcursor.downField("outputs").focus.getOrElse(Json.arr())))
      steps <- IO.fromEither(JsonModel[Vector[WineStepResult]].decoder.decodeJson(value.hcursor.downField("steps").focus.getOrElse(Json.arr())))
    yield WineCallResult(value.hcursor.downField("value").focus.getOrElse(Json.Null), outputs, steps,
      process.stdout, process.stderr, 0, process.stdoutTruncated, process.stderrTruncated) }((r, ms) => r.copy(durationMs = ms))

  private def close: IO[Unit] = setup.permit.use(_ => environment.getAndSet(None).flatMap(_.traverse_(_._2)))

object WineService:
  def resource(): Resource[IO, WineService] = managed(WineConfig.default)
  private[wine] def managed(config: WineConfig): Resource[IO, WineService] = for
    environment <- Resource.eval(Ref.of[IO, Option[(WineEnvironment, IO[Unit])]](None))
    setup <- Resource.eval(Semaphore[IO](1))
    slots <- Resource.eval(Semaphore[IO](8))
    service = new WineService(config, environment, setup, slots)
    _ <- Resource.make(IO.unit)(_ => service.close)
  yield service
