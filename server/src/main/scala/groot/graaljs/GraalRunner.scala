package groot.graaljs

import cats.effect.{IO, Resource}
import java.io.{IOException, InputStream, OutputStream}
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.graalvm.polyglot.{Context, Engine, PolyglotException, SandboxPolicy, Source}
import scala.util.control.NonFatal

private[graaljs] case class RunResult(
    state: String,
    cpuTimeMs: Option[Double],
    runTimeMs: Double,
    error: Option[TaskError]
)

private[graaljs] trait GraalExecution:
  def result: IO[RunResult]
  /** Returns only after an installed context has stopped; also handles cancellation during construction. */
  def cancel(reason: TaskError): IO[Unit]

private[graaljs] trait GraalRunner:
  def start(task: GraalTask, source: String, log: Path): IO[GraalExecution]

private[graaljs] object GraalRunner:
  val cancelled = TaskError("cancelled", "Cancelled by user")
  val timeout = TaskError("timeout", "Wall-clock timeout exceeded")
  val stopped = TaskError("interrupted", "Server stopped before task completion")

  private final class LogOutput(path: Path) extends OutputStream:
    private val output = Files.newOutputStream(path, StandardOpenOption.APPEND)
    private var written = 0L
    val exceeded = new AtomicBoolean(false)
    override def write(value: Int): Unit = write(Array(value.toByte), 0, 1)
    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = synchronized {
      if length > Limits.maxLogBytes - written then
        exceeded.set(true)
        throw new IOException("Task output exceeded 8 MiB")
      output.write(bytes, offset, length)
      written += length
    }
    override def flush(): Unit = synchronized(output.flush())
    override def close(): Unit = synchronized(output.close())

  private def failure(error: Throwable): TaskError =
    val message = Option(error.getMessage).getOrElse(error.getClass.getSimpleName).take(8192)
    val lower = message.toLowerCase(java.util.Locale.ROOT)
    val code = error match
      case value: PolyglotException if value.isResourceExhausted =>
        if lower.contains("cpu") then "cpu_limit"
        else if lower.contains("memory") || lower.contains("heap") then "memory_limit"
        else "resource_limit"
      case value: PolyglotException if value.isSyntaxError => "syntax_error"
      case _: PolyglotException => "script_error"
      case _ => "runtime_error"
    TaskError(code, message)

  private final class Runner(engine: Engine) extends GraalRunner:
    private val cpu = ManagementFactory.getThreadMXBean
    if cpu.isThreadCpuTimeSupported && !cpu.isThreadCpuTimeEnabled then cpu.setThreadCpuTimeEnabled(true)

    private def cpuNanos(): Option[Long] =
      if !cpu.isCurrentThreadCpuTimeSupported then None
      else Option(cpu.getCurrentThreadCpuTime).filter(_ >= 0)

    def start(task: GraalTask, source: String, log: Path): IO[GraalExecution] = IO {
      val context = new AtomicReference[Context]()
      val stop = new AtomicReference[TaskError]()
      val completed = new CompletableFuture[RunResult]()
      val execution = new GraalExecution:
        // Cancelling an observer never cancels the worker or its completion signal.
        def result: IO[RunResult] = IO.async_ { callback =>
          completed.whenComplete { (value, error) =>
            callback(if error == null then Right(value) else Left(error))
          }
          ()
        }
        def cancel(reason: TaskError): IO[Unit] = IO.blocking {
          if !completed.isDone then
            stop.compareAndSet(null, reason)
            val current = context.get()
            if current != null then current.close(true)
        }

      Thread.ofPlatform().daemon().name(s"graaljs-${task.id}").start(() => {
        val started = System.nanoTime()
        val initialCpu = cpuNanos()
        var output: LogOutput = null
        var error: Option[TaskError] = None
        var returned = false
        try
          output = new LogOutput(log)
          if stop.get() == null then
            val current = Context.newBuilder("js").engine(engine).sandbox(SandboxPolicy.ISOLATED)
              .in(InputStream.nullInputStream()).out(output).err(output)
              .option("js.console", "true")
              .option("sandbox.MaxHeapMemory", s"${Limits.taskBytes}B")
              .option("sandbox.MaxCPUTime", s"${task.cpuLimitMs}ms")
              .build()
            context.set(current)
            if stop.get() == null then
              val code = Source.newBuilder("js", source, task.script).uri(Path.of(task.script).toUri).cached(false).buildLiteral()
              current.eval(code)
          returned = true
        catch case NonFatal(cause) => error = Some(failure(cause))
        finally
          val current = context.get()
          if current != null then
            try current.close(true)
            catch case NonFatal(cause) => if error.isEmpty then error = Some(failure(cause))
          if output != null then
            try output.close()
            catch case NonFatal(cause) => if error.isEmpty then error = Some(failure(cause))
            if output.exceeded.get() then error = Some(TaskError("output_limit", "Task output exceeded 8 MiB"))
          error = Option(stop.get()).orElse(error)
          if !returned && error.isEmpty then error = Some(TaskError("runtime_error", "Worker terminated unexpectedly"))
          val state = error.map(_.code) match
            case Some("cancelled") => "cancelled"
            case Some("interrupted") => "interrupted"
            case Some(_) => "failed"
            case None => "succeeded"
          val usedCpu = for initial <- initialCpu; end <- cpuNanos() yield math.max(0L, end - initial).toDouble / 1000000d
          completed.complete(RunResult(state, usedCpu, (System.nanoTime() - started).toDouble / 1000000d, error))
      })
      execution
    }

  def resource: Resource[IO, GraalRunner] =
    Resource.make(IO.blocking {
      Engine.newBuilder("js").sandbox(SandboxPolicy.ISOLATED).spawnIsolate(true)
        .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
        .option("engine.MaxIsolateMemory", s"${Limits.engineBytes}B").build()
    })(engine => IO.blocking(engine.close(true))).map(new Runner(_))
