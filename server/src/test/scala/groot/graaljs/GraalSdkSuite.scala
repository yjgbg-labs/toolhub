package groot.graaljs

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import framework.rpcDsl.*
import framework.runtime.CommandSdk
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import GraalTestSupport.*

object GraalSdkSuite:
  case class Payload(text: String, values: List[Int]) derives JsonModel

class GraalSdkSuite extends CatsEffectSuite:
  import GraalSdkSuite.*

  private def run(manager: GraalManager, source: String, cpu: String = "5s", timeout: String = "10s"): IO[(GraalTask, String)] =
    for
      submitted <- manager.start(source, cpu, timeout)
      task <- awaitUntil(manager.status(submitted.id))(_.terminal)
      log <- manager.logs(task.id)
    yield (task, log.toString)

  test("the read-only command tree supports native Promises, nested/hyphenated names, typed inputs, all response kinds and API errors") {
    temporary.use { home =>
      for
        calls <- Ref.of[IO, Int](0)
        app = httpApp {
          middleware { next => calls.update(_ + 1) *> next }
          command[PlainText]("health") { handle { IO.pure(PlainText("ok")) } }
          command[PlainText]("sample") { handle { IO.pure(PlainText("also a namespace")) } }
          commands("sample") {
            val scale = param[Int]("scale", "Scale") { default(2); minimum(1) }
            commands("deep") {
              command[PlainText]("echo-text") {
                val words = param[List[String]]("words", "Words")
                val text = body.text("Text")
                handle { IO.pure(PlainText(s"${scale.value}:${words.value.mkString("|")}:${text.value}")) }
              }
            }
            command[Payload]("json") {
              val payload = body[Payload]("Payload")
              handle { IO.pure(payload.value.copy(values = payload.value.values.map(_ * scale.value))) }
            }
            command[Unit]("empty") { handle { IO.unit } }
            command[PlainText]("name") { handle { IO.pure(PlainText("name")) } }
            command[PlainText]("length") { handle { IO.pure(PlainText("length")) } }
            command[PlainText]("prototype") { handle { IO.pure(PlainText("prototype")) } }
            command[Shell]("shell") { handle { IO.pure(Shell("echo sdk-does-not-execute-shell")) } }
            command[SSE[PlainText]]("events") { handle { IO.pure(SSE(fs2.Stream.emits(List(PlainText("one"), PlainText("two"))))) } }
            command[PlainText]("denied") {
              val denied = response.text(409) { description("Conflict") }
              handle { denied.raise("conflict") }
            }
          }
        }
        _ <- GraalManager.resource(home.resolve("data"), IO.pure(app.commandSdk)).use { manager =>
          run(manager, """
            if (typeof search !== "undefined" || typeof Java !== "undefined" || typeof fetch !== "undefined") throw Error("unexpected SDK");
            const originalTools = tools, health = tools.health;
            for (const mutate of [() => { tools.health = null; }, () => { delete tools.health; },
                 () => { tools.extra = 42; }, () => { globalThis.tools = {}; },
                 () => { Object.defineProperty(tools, "health", {value: null}); }]) {
              try { mutate(); } catch (_) {}
            }
            if (tools !== originalTools || tools.health !== health || tools.extra !== undefined) throw Error("mutable tree");
            if (!Object.keys(tools).includes("sample") || tools.getClass !== undefined) throw Error("invalid namespace");
            const promise = tools.health(undefined);
            if (!(promise instanceof Promise) || (await promise).body !== "ok") throw Error("expected native Promise");
            console.log((await tools.health()).body, (await tools.sample()).body);
            console.log((await tools.sample.name()).body, (await tools.sample.length()).body, (await tools.sample.prototype()).body);
            console.log((await tools.sample.deep["echo-text"]({query: {words: ["a&b", "中文/🙂"]}, body: "raw\ntext"})).body);
            const result = await tools.sample.json({query: {scale: 3}, body: {text: "你好", values: [2, 4]}});
            if (result.status !== 200 || result.headers["content-type"] !== "application/json" || result.body.text !== "你好" || result.body.values.join(',') !== "6,12") throw Error("invalid JSON response");
            console.log(JSON.stringify(result.body));
            console.log((await tools.sample.empty()).body === null);
            console.log((await tools.sample.shell()).body);
            console.log((await tools.sample.events()).body);
            for (const invoke of [() => tools.sample.denied(), () => tools.sample.json({body: {text: "missing values"}}),
                 () => tools.sample.json({query: {scale: 0}, body: {text: "x", values: []}})]) {
              try { await invoke(); throw Error("expected rejection"); }
              catch (error) { if (!(error instanceof Error) || error.name !== "ApiError") throw error; console.log(error.operationId, error.response.status); }
            }
            const cyclic = {}; cyclic.self = cyclic;
            for (const input of [{query: {scale: "3"}}, {query: {typo: 1}}, {unknown: true}, null, cyclic, 1n, () => {}]) {
              const rejected = tools.sample.empty(input);
              if (!(rejected instanceof Promise)) throw Error("validation did not return a Promise");
              try { await rejected; throw Error("accepted invalid input"); }
              catch (error) { if (!(error instanceof Error) || error.message === "accepted invalid input") throw error; }
            }
          """).flatMap { (task, log) =>
            IO {
              assertEquals(task.state, "succeeded", log)
              assert(log.contains("ok also a namespace"), log)
              assert(log.contains("name length prototype"), log)
              assert(log.contains("2:a&b|中文/🙂:raw\ntext"), log)
              assert(log.contains("{\"text\":\"你好\",\"values\":[6,12]}"), log)
              assert(log.contains("true\necho sdk-does-not-execute-shell"), log)
              assert(log.contains("data: one") && log.contains("data: two"), log)
              assert(log.contains("sample.denied 409") && log.contains("sample.json 400"), log)
            } *> calls.get.map(count => assert(count >= 8))
          }
        }
      yield ()
    }
  }

  test("exposure removes unavailable commands and namespaces while retaining callable namespace children") {
    temporary.use { home =>
      val app = httpApp {
        commands("external") {
          exposeOn(cli, codemode)
          command[Int]("read") { handle { IO.pure(1) } }
        }
        command[Int]("mixed") { exposeOn(cli); handle { IO.pure(2) } }
        commands("mixed") {
          command[Int]("read") { exposeOn(framework.Client.graaljs); handle { IO.pure(3) } }
          command[Int]("hidden") { exposeOn(codemode); handle { IO.pure(4) } }
        }
        command[Int]("shared") { handle { IO.pure(5) } }
      }
      GraalManager.resource(home.resolve("data"), IO.pure(app.commandSdk)).use { manager =>
        run(manager, """
          if (tools.external !== undefined || tools.mixed.hidden !== undefined) throw Error("leaked command");
          if (typeof tools.mixed === "function") throw Error("leaked callable namespace");
          if (Object.keys(tools).sort().join(",") !== "mixed,shared") throw Error("leaked namespace");
          console.log((await tools.mixed.read()).body, (await tools.shared()).body);
        """).map { (task, log) =>
          assertEquals(task.state, "succeeded", log)
          assert(log.contains("3 5"), log)
        }
      }
    }
  }

  test("Promise.all starts independent command IO concurrently and resumes on the guest thread") {
    temporary.use { home =>
      for
        gate <- Deferred[IO, Unit]
        arrivals <- Ref.of[IO, Int](0)
        app = httpApp {
          command[Int]("arrive") {
            handle { arrivals.updateAndGet(_ + 1).flatMap { count =>
              (if count == 2 then gate.complete(()).void else IO.unit) *> gate.get *> IO.sleep(150.millis).as(count)
            } }
          }
        }
        _ <- GraalManager.resource(home.resolve("data"), IO.pure(app.commandSdk)).use { manager =>
          run(manager, "const results = await Promise.all([tools.arrive(), tools.arrive()]); console.log(results.map(r => r.body).sort().join(','));")
            .map { (task, log) => assertEquals(task.state, "succeeded", log); assert(log.contains("1,2"), log) }
        }
      yield ()
    }
  }

  test("cancellation retains the task reservation until in-flight command finalizers finish") {
    temporary.use { home =>
      for
        started <- Deferred[IO, Unit]
        finalizing <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        app = httpApp {
          command[Unit]("wait") {
            handle { (started.complete(()) *> IO.never[Unit]).onCancel(finalizing.complete(()) *> release.get) }
          }
        }
        _ <- GraalManager.managed(home.resolve("data"), GraalRunner.resource(IO.pure(app.commandSdk)), Limits.taskBytes).use { manager =>
          (for
            first <- manager.start("await tools.wait();", "5s", "10s")
            _ <- started.get.timeout(5.seconds)
            second <- manager.start("console.log('next');", "5s", "10s")
            _ <- manager.cancel(first.id)
            _ <- finalizing.get.timeout(5.seconds)
            waiting <- manager.status(second.id)
            _ = assertEquals(waiting.state, "queued")
            current <- manager.status(first.id)
            _ = assertEquals(current.state, "cancelling")
            _ <- release.complete(())
            cancelled <- awaitUntil(manager.status(first.id))(_.terminal)
            _ = assertEquals(cancelled.state, "cancelled")
            finished <- awaitUntil(manager.status(second.id))(_.terminal)
          yield assertEquals(finished.state, "succeeded")).guarantee(release.complete(()).void)
        }
      yield ()
    }
  }

  test("wall timeout cancels awaited IO, CPU limits survive await, and rejected async scripts fail") {
    temporary.use { home =>
      for
        cancelled <- Deferred[IO, Unit]
        app = httpApp {
          command[Unit]("wait") { handle { IO.never[Unit].onCancel(cancelled.complete(()).void) } }
          command[PlainText]("health") { handle { IO.pure(PlainText("ok")) } }
        }
        _ <- GraalManager.resource(home.resolve("data"), IO.pure(app.commandSdk)).use { manager =>
          for
            timed <- run(manager, "await tools.wait();", timeout = "500ms")
            _ = assertEquals(timed._1.error.map(_.code), Some("timeout"), timed._2)
            _ <- cancelled.get.timeout(5.seconds)
            cpu <- run(manager, "await tools.health(); while(true) {}", cpu = "100ms")
            _ = assertEquals(cpu._1.error.map(_.code), Some("cpu_limit"), cpu._2)
            failed <- run(manager, "await tools.health(); throw new Error('async failure');")
            _ = assertEquals(failed._1.error.map(_.code), Some("script_error"), failed._2)
            _ = assert(failed._2.contains("async failure"), failed._2)
            unresolved <- run(manager, "await new Promise(() => {});", timeout = "100ms")
          yield assertEquals(unresolved._1.error.map(_.code), Some("timeout"), unresolved._2)
        }
      yield ()
    }
  }

  test("SDK response and outstanding-call bounds reject cleanly and unawaited IO is cancelled") {
    temporary.use { home =>
      for
        active <- Ref.of[IO, Int](0)
        app = httpApp {
          command[PlainText]("large") { handle { IO.pure(PlainText("x" * (CommandSdk.maxBytes + 1))) } }
          command[Unit]("wait") { handle { active.update(_ + 1).bracket(_ => IO.never[Unit])(_ => active.update(_ - 1)) } }
        }
        _ <- GraalManager.resource(home.resolve("data"), IO.pure(app.commandSdk)).use { manager =>
          for
            bounded <- run(manager, """
              try { await tools.large(); throw Error("missing response bound"); }
              catch (e) { if (!e.message.includes("1 MiB")) throw e; console.log("bounded response"); }
              const pending = Array.from({length: 65}, () => tools.wait());
              try { await pending[64]; throw Error("missing pending bound"); }
              catch (e) { if (!e.message.includes("64")) throw e; console.log("bounded pending"); }
            """)
            _ = assertEquals(bounded._1.state, "succeeded", bounded._2)
            count <- active.get
          yield assertEquals(count, 0)
        }
      yield ()
    }
  }

  test("the complete live registry includes GraalJS itself and handles nested tasks through text bodies and SSE") {
    temporary.use { home =>
      for
        commands <- Deferred[IO, CommandSdk]
        _ <- GraalManager.resource(home.resolve("data"), commands.get).use { manager =>
          val app = httpApp {
            GraalJsRoutes.register(manager)
            command[PlainText]("health") { handle { IO.pure(PlainText("ok")) } }
          }
          commands.complete(app.commandSdk) *> run(manager, """
            const child = await tools.graaljs.start({query: {cpu: "1s", timeout: "5s"}, body: 'console.log((await tools.health()).body)'});
            const logs = await tools.graaljs["logs-follow"]({query: {id: child.body.id}});
            const status = await tools.graaljs.status({query: {id: child.body.id}});
            if (status.body.state !== "succeeded" || !logs.body.includes("data: ok")) throw Error(JSON.stringify(status));
            console.log("nested task succeeded");
            try { await tools.graaljs["notify-on-finish"]({query: {id: child.body.id}}); }
            catch (e) { if (e.response.status !== 501) throw e; console.log("501 preserved"); }
          """).map { (task, log) =>
            assertEquals(task.state, "succeeded", log)
            assert(log.contains("nested task succeeded") && log.contains("501 preserved"), log)
          }
        }
      yield ()
    }
  }
