package groot.wine

import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import java.io.{DataInputStream, DataOutputStream, InputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.CompletableFuture
import scala.concurrent.duration.*

private[wine] final class WineWorker(val process: Process, socket: Socket, gate: Semaphore[IO]):
  private val input = new DataInputStream(socket.getInputStream)
  private val output = new DataOutputStream(socket.getOutputStream)
  def alive: Boolean = process.isAlive && !socket.isClosed
  def close: IO[Unit] = IO.blocking(socket.close()) *> WineRuntime.terminate(process)
  def call(request: Json, timeoutMs: Int): IO[Json] =
    IO(WineValidation.timeout(timeoutMs)) *> gate.permit.use { _ =>
      WineRuntime.async {
        if !alive then throw WineFailure("wine_disconnected", "Worker stopped; reload the DLL session")
        val bytes = request.noSpaces.getBytes(UTF_8)
        if bytes.length > WineWorker.maxFrame then throw WineFailure("invalid_wine", "Request exceeds 1 MiB")
        output.writeInt(Integer.reverseBytes(bytes.length))
        output.write(bytes)
        output.flush()
        val response = WineWorker.read(input)
        if !response.hcursor.get[Boolean]("ok").contains(true) then
          throw WineFailure("wine_call_failed", response.hcursor.downField("error").focus.fold("Worker error")(_.noSpaces))
        response.hcursor.downField("result").focus.getOrElse(Json.Null)
      }(close).adaptError {
        case e: java.io.IOException => WineFailure("wine_disconnected", s"Worker connection lost: ${e.getMessage}; see session logs")
      }
    }.timeoutTo(timeoutMs.millis, close *> IO.raiseError(WineFailure("wine_timeout", "Call timed out; its worker and handles have been released")))

private[wine] object WineWorker:
  val maxFrame = 1024 * 1024
  private def read(input: DataInputStream): Json =
    val size = Integer.reverseBytes(input.readInt())
    if size < 1 || size > maxFrame then throw WineFailure("wine_protocol", "Invalid worker frame size")
    val bytes = new Array[Byte](size)
    input.readFully(bytes)
    parse(new String(bytes, UTF_8)).fold(throw _, identity)

  /** Continue draining after truncation, so a noisy DLL cannot block on its output pipe. */
  private def capture(input: InputStream, path: Path): Resource[IO, Unit] =
    Resource.make(IO.blocking {
      val output = Files.newOutputStream(path)
      val done = new CompletableFuture[Unit]()
      Thread.ofVirtual().name("wine-log").start(() => {
        try
          val buffer = new Array[Byte](8192)
          var stored = 0L
          var count = input.read(buffer)
          while count >= 0 do
            val keep = math.min(count.toLong, (16L * 1024 * 1024 - stored).max(0)).toInt
            if keep > 0 then output.write(buffer, 0, keep)
            stored += keep
            count = input.read(buffer)
          done.complete(())
        catch case scala.util.control.NonFatal(error) => done.completeExceptionally(error)
        finally output.close()
      })
      done
    })(done => IO.fromCompletableFuture(IO(done)).void.timeoutTo(3.seconds, IO.blocking(input.close())).attempt.void).void

  def resource(config: WineConfig, env: WineEnvironment, architecture: String, stdout: Path, stderr: Path,
      runtimeKind: String = "dotnet"): Resource[IO, WineWorker] = for
    _ <- Resource.eval(IO {
      WineValidation.architecture(architecture)
      if !Files.isRegularFile(config.helper.resolve("Sprout.Wine.Helper.dll")) then throw WineFailure("wine_unavailable", "Install the helper using wine-deploy")
    })
    listener <- Resource.fromAutoCloseable(IO.blocking(new ServerSocket(0, 1, InetAddress.getLoopbackAddress)))
    token = UUID.randomUUID().toString
    process <- Resource.make(IO.blocking {
      val helper = config.helper.toRealPath()
      val command = if runtimeKind == "framework" then Vector(config.binary.toString,
        helper.resolve(s"framework-$architecture/Sprout.Wine.Helper.exe").toString, listener.getLocalPort.toString)
      else Vector(config.binary.toString, config.runtime.resolve(s"dotnet-$architecture/dotnet.exe").toString,
        WineValidation.windows(helper.resolve("Sprout.Wine.Helper.dll").toString), listener.getLocalPort.toString)
      val overrides = if runtimeKind == "framework" then "winemenubuilder.exe=d" else "winemenubuilder.exe=d;mscoree=b"
      val child = WineRuntime.builder(command,
        env.variables ++ Map("SPROUT_WINE_TOKEN" -> token, "WINEDLLOVERRIDES" -> overrides)).start()
      child.getOutputStream.close()
      child
    })(WineRuntime.terminate)
    // The inner process owner is released first; EOF drains logs before the outer safety owner.
    _ <- capture(process.getInputStream, stdout)
    _ <- capture(process.getErrorStream, stderr)
    _ <- Resource.make(IO.unit)(_ => WineRuntime.terminate(process))
    socket <- Resource.make(WineRuntime.async(listener.accept())(IO.blocking(listener.close())).timeoutTo(30.seconds,
      IO.raiseError(WineFailure("wine_timeout", s"Worker did not connect; see $stderr"))))(s => IO.blocking(s.close()))
    _ <- Resource.eval(IO.blocking(listener.close()))
    hello <- Resource.eval(WineRuntime.async {
      socket.setSoTimeout(10000)
      val value = read(new DataInputStream(socket.getInputStream))
      socket.setSoTimeout(0)
      value
    }(IO.blocking(socket.close())))
    _ <- Resource.eval(IO.raiseUnless(hello.hcursor.get[String]("token").contains(token) && hello.hcursor.get[Int]("protocol").contains(1) &&
      hello.hcursor.get[Int]("bits").contains(if architecture == "x64" then 64 else 32))(WineFailure("wine_protocol", "Worker handshake or architecture mismatch")))
    gate <- Resource.eval(Semaphore[IO](1))
  yield new WineWorker(process, socket, gate)
