package groot.wine

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import scala.concurrent.duration.*

/** A transient user service owns all Linux descendants, including double-forked Windows children.
  * The shared wineserver is started outside these per-request cgroups. No Windows EXE wrapper is needed.
  */
private[wine] object WineProcess:
  case class Output(text: String, truncated: Boolean)
  private val maxLog = 65536
  private class Capture:
    val done = new CompletableFuture[Output]()
    private val bytes = new ByteArrayOutputStream()
    private var truncated = false
    def append(buffer: Array[Byte], count: Int): Unit = synchronized {
      val keep = math.min(count, maxLog - bytes.size())
      bytes.write(buffer, 0, keep)
      truncated ||= keep < count
    }
    def snapshot: Output = synchronized(Output(bytes.toString(UTF_8), truncated))
  private def capture(input: InputStream): IO[Capture] = IO {
    val output = new Capture
    Thread.ofVirtual().name("wine-output").start(() => {
      try
        val buffer = new Array[Byte](8192)
        var count = input.read(buffer)
        while count >= 0 do
          output.append(buffer, count)
          count = input.read(buffer)
        output.done.complete(output.snapshot)
      catch case scala.util.control.NonFatal(error) => output.done.completeExceptionally(error)
      ()
    })
    output
  }
  private def await[A](future: CompletableFuture[A]): IO[A] = IO.async { callback => IO {
    future.whenComplete((value, error) => callback(if error == null then Right(value) else Left(error)))
    Some(IO.unit)
  }}
  private def stop(unit: String): IO[Unit] = Resource.make(IO.blocking {
    val p = new ProcessBuilder("systemctl", "--user", "stop", unit)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    p.getOutputStream.close()
    p
  })(WineRuntime.terminate).use(p => await(p.onExit()).void.timeoutTo(5.seconds, IO.unit))

  def run(command: Vector[String], env: Map[String, String], cwd: Path, input: Path,
      logState: cats.effect.Ref[IO, (String, String)]): IO[WineExecResult] =
    val unit = "groot-wine-call-" + UUID.randomUUID().toString + ".service"
    val args = Vector("systemd-run", "--user", "--quiet", "--wait", "--pipe", "--collect", "--service-type=exec",
      "--expand-environment=no", s"--unit=$unit", "--property=KillMode=control-group", "--property=TimeoutStopSec=1s",
      "--property=UnsetEnvironment=WAYLAND_DISPLAY", s"--working-directory=$cwd") ++
      env.toVector.sortBy(_._1).map((k, v) => s"--setenv=$k=$v") ++ Vector("--") ++ command
    Resource.make(IO.blocking {
      val child = WineRuntime.builder(args, Map.empty).redirectInput(input.toFile).start()
      child
    })(p => stop(unit).guarantee(WineRuntime.terminate(p))).use { child => for
      out <- capture(child.getInputStream)
      err <- capture(child.getErrorStream)
      result <- (await(child.onExit()).map(_.exitValue()), await(out.done), await(err.done)).parMapN { (code, stdout, stderr) =>
        WineExecResult(code, stdout.text, stderr.text, 0, stdout.truncated, stderr.truncated)
      }.guarantee(IO((out.snapshot.text, err.snapshot.text)).flatMap(logState.set))
    yield result }
