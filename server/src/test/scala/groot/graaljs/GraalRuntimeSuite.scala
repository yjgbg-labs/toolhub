package groot.graaljs

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import java.io.{ByteArrayOutputStream, InputStream}
import org.graalvm.polyglot.{Context, Engine, SandboxPolicy}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration.*
import GraalTestSupport.*

class GraalRuntimeSuite extends CatsEffectSuite:
  test("a 64 MiB isolate supports sixteen independent 4 MiB contexts sharing one engine") {
    IO.blocking {
      val output = new ByteArrayOutputStream()
      val engine = Engine.newBuilder("js")
        .sandbox(SandboxPolicy.ISOLATED).spawnIsolate(true)
        .out(output).err(output).option("engine.MaxIsolateMemory", "64MB").build()
      try
        val contexts = (1 to 16).map { _ =>
          Context.newBuilder("js").engine(engine).sandbox(SandboxPolicy.ISOLATED)
            .in(InputStream.nullInputStream()).out(output).err(output)
            .option("sandbox.MaxHeapMemory", "4MB").option("sandbox.MaxCPUTime", "5s").build()
        }
        try
          contexts.zipWithIndex.foreach { (context, index) =>
            assert(context.getEngine eq engine)
            assertEquals(context.eval("js", "typeof marker").asString(), "undefined")
            assertEquals(context.eval("js", s"globalThis.marker = $index; marker").asInt(), index)
          }
          val pool = Executors.newFixedThreadPool(16)
          try
            val ready = new CountDownLatch(16)
            val go = new CountDownLatch(1)
            val futures = contexts.map { context => pool.submit(new Callable[Int]:
              def call(): Int =
                ready.countDown()
                go.await()
                context.eval("js", "for(let i=0;i<10000;i++) Math.sqrt(i); marker").asInt()
            ) }
            assert(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertEquals(futures.map(_.get(10, TimeUnit.SECONDS)).toList, (0 to 15).toList)
          finally pool.shutdownNow()
        finally contexts.foreach(_.close(true))
      finally engine.close(true)
    }
  }

  test("real isolate logs and CPU metrics, CPU and wall limits, cancellation, errors and heap limits leave the shared engine usable") {
    temporary.use { home => GraalManager.resource(home.resolve("data")).use { manager =>
      def run(code: String, cpu: String = "5s", timeout: String = "10s") =
        manager.start(code, cpu, timeout)
          .flatMap(task => awaitUntil(manager.status(task.id))(_.terminal))
      for
        success <- run("console.log('你好🙂');\nconsole.error('stderr'); print('print');")
        _ = assertEquals(success.state, "succeeded", success.error.toString)
        _ = assert(success.cpuTimeMs.exists(_ > 0d))
        _ = assert(success.runTimeMs.exists(_ > 0d))
        log <- manager.logs(success.id)
        _ = assert(log.contains("你好🙂\nstderr\nprint\n"), log.toString)
        _ = assert(log.contains("state=succeeded cpu="))
        cpu <- run("while(true) {}", "100ms")
        _ = assertEquals(cpu.error.map(_.code), Some("cpu_limit"), cpu.toString)
        _ = assert(cpu.cpuTimeMs.exists(_ >= 50d), cpu.toString)
        wall <- run("while(true) {}", "30s", "100ms")
        _ = assertEquals(wall.error.map(_.code), Some("timeout"), wall.toString)
        syntax <- run("const = ;")
        _ = assertEquals(syntax.error.map(_.code), Some("syntax_error"))
        _ = assert(syntax.error.exists(_.message.contains(s"${syntax.id}.graal.js")))
        scriptError <- run("throw new Error('expected script failure');")
        _ = assertEquals(scriptError.error.map(_.code), Some("script_error"))
        host <- run("Java.type('java.lang.System').exit(0)")
        _ = assertEquals(host.state, "failed")
        long <- manager.start("console.log('ready'); while(true) {}", "30s", "30s")
        _ <- awaitUntil(manager.logs(long.id))(_.contains("ready"))
        _ <- manager.cancel(long.id)
        cancelled <- awaitUntil(manager.status(long.id))(_.terminal)
        _ = assertEquals(cancelled.state, "cancelled")
        memory <- run("globalThis.leak = new Array(1000000).fill('retained'); while(true) {}")
        _ = assertEquals(memory.error.map(_.code), Some("memory_limit"), memory.toString)
        output <- run("const line = 'x'.repeat(8192); for(let i=0;i<1100;i++) console.log(line);")
        _ = assertEquals(output.error.map(_.code), Some("output_limit"), output.toString)
        outputSize <- IO.blocking(java.nio.file.Files.size(java.nio.file.Path.of(output.logPath)))
        _ = assert(outputSize <= Limits.maxLogBytes + 8192)
        finalTask <- run("console.log('still usable')")
        _ = assertEquals(finalTask.state, "succeeded", finalTask.error.toString)
        active <- manager.list
      yield assertEquals(active, Nil)
    } }.timeout(45.seconds)
  }

  test("shutdown closes an actively executing context before releasing the database and engine") {
    temporary.use { home =>
      val data = home.resolve("data")
      for
        id <- GraalManager.resource(data).use { manager =>
          for
            task <- manager.start("console.log('ready'); while(true) {}", "30s", "30s")
            _ <- awaitUntil(manager.logs(task.id))(_.contains("ready"))
          yield task.id
        }
        _ <- GraalStore.resource(data).use { store =>
          for
            task <- store.get(id)
            log <- store.logs(id)
          yield
            assertEquals(task.state, "interrupted")
            assert(task.cpuTimeMs.exists(_ > 0d))
            assert(log.contains("state=interrupted"))
        }
      yield ()
    }.timeout(10.seconds)
  }
