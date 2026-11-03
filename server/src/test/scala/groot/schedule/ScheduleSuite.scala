package groot.schedule

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import framework.model.JsonModel
import framework.runtime.Application
import framework.rpcDsl.*
import groot.graaljs.{GraalManager, ScheduleTestSupport as Stub}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.typelevel.ci.CIString
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class ScheduleSuite extends ScriptFixtures:
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "groot-schedule-test-"))
  )(path => IO.blocking {
    val files = Files.walk(path)
    try files.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    finally files.close()
  })

  private def eventually[A](io: IO[A])(check: A => Boolean): IO[A] =
    io.flatMap(value => if check(value) then IO.pure(value) else IO.sleep(10.millis) *> eventually(io)(check)).timeout(10.seconds)

  private def scheduler(home: Path): Resource[IO, Scheduler] =
    Stub.resource(home.resolve("graaljs"), autoFinish = true).flatMap { (manager, _) => Scheduler.resource(manager, home, startLoop = false) }

  private def app(scheduler: Scheduler): Application = httpApp { ScheduleRoutes.register(scheduler) }
  private def request(path: String, json: Option[String] = None): Request[IO] =
    val request = Request[IO](if json.isDefined then Method.POST else Method.GET, Uri.unsafeFromString(path))
    json.fold(request)(body => request.withEntity(body).putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))

  private def retryDue(home: Path, name: String): IO[Unit] =
    val store = new Store(home)
    store.state(name).flatMap(state => store.writeState(name, state.copy(nextRetryAt = Some("2000-01-01T00:00:00Z"))))

  test("duration, limits, resource capacities and retry policy are validated") {
    assertEquals(Validation.durationMs("0ms"), 0L)
    assertEquals(Validation.durationMs("2d"), 172800000L)
    List("1.5h", "1h30m", "-1s", "1w", "99999999999999999999d").foreach { value =>
      intercept[InvalidSchedule](Validation.durationMs(value))
    }
    intercept[InvalidSchedule](Validation.config(ScheduleConfig.empty.copy(resources = Map("cpu" -> Double.NaN))))
    intercept[InvalidSchedule](Validation.config(ScheduleConfig.empty.copy(resources = Map("cpu" -> -1d))))
    List(job().copy(script = " "), job().copy(script = "relative.js"), job().copy(script = "https://example.com/task.js"),
      job().copy(script = "/bad\u0000.js"), job().copy(cpu = "0s"), job().copy(timeout = "0ms"),
      job().copy(retry = RetryPolicy(maxRetries = -1)), job().copy(retry = RetryPolicy(maxRetries = 101)),
      job().copy(retry = RetryPolicy(backoff = Double.PositiveInfinity)), job().copy(retry = RetryPolicy(backoff = 0.5)),
      job().copy(retry = RetryPolicy(delay = "2s", maxDelay = "1s"))).foreach { invalid =>
      intercept[InvalidSchedule](Validation.config(ScheduleConfig.empty.copy(jobs = Map("a" -> invalid))))
    }
    assertEquals((1 to 4).map(Validation.retryDelayMs(RetryPolicy(maxDelay = "3s"), _)).toList, List(1000L, 2000L, 3000L, 3000L))
    assertEquals(Validation.retryDelayMs(RetryPolicy(backoff = Double.MaxValue), 100), 30000L)
    assertEquals(Store.fileName("a/b 空 +%"), "a%2Fb%20%E7%A9%BA%20%2B%25")
  }

  test("HTTP accepts script paths with defaults and rejects inline source, command, cwd, state and invalid retries atomically") {
    temporary.use { home => scheduler(home).use { scheduler =>
      val http = app(scheduler)
      for
        created <- http.httpApp(request("/schedule/new?name=sample", Some("""{"every":"1h","script":"/scripts/sample.js"}""")))
        _ = assertEquals(created.status, Status.Ok)
        createdJson <- created.as[Json]
        _ = assertEquals(createdJson.hcursor.get[String]("cpu"), Right("1s"))
        _ = assertEquals(createdJson.hcursor.downField("retry").get[Int]("maxRetries"), Right(3))
        _ <- new Store(home).writeState("sample", JobState(Some("2026-09-19T00:00:00Z"), Some(7)))
        _ <- List(""""source":"console.log(1)"""", """"command":["/bin/true"]""", """"cwd":"/tmp"""", """"lastExitCode":0""",
          """"resource":{"cpu":1}""", """"retry":{"maxRetries":-1}""").traverse_ { extra =>
          http.httpApp(request("/schedule/new?name=sample", Some(s"""{"every":"1h","script":"/scripts/sample.js",$extra}""")))
            .map(response => assertEquals(response.status, Status.BadRequest))
        }
        persisted <- scheduler.list
        _ = assertEquals(persisted.head.lastExitCode, Some(7))
        partial <- http.httpApp(request("/schedule/config/write", Some("{}")))
        _ = assertEquals(partial.status, Status.BadRequest)
        badLines <- http.httpApp(request("/schedule/log?name=sample&lines=0"))
        _ = assertEquals(badLines.status, Status.BadRequest)
        missing <- http.httpApp(request("/schedule/reset?name=missing"))
        _ = assertEquals(missing.status, Status.NotFound)
        reset <- http.httpApp(request("/schedule/reset?name=sample"))
        resetJson <- reset.as[Json]
        _ = assertEquals(resetJson, JsonModel[JobState].encoder(JobState()))
        deleted <- http.httpApp(request("/schedule/delete?name=sample"))
        _ = assertEquals(deleted.status, Status.Ok)
        duplicate <- http.httpApp(request("/schedule/delete?name=sample"))
        _ = assertEquals(duplicate.status, Status.Ok)
        openapi <- http.httpApp(Request[IO](uri = Uri.unsafeFromString("/openapi.json"))).flatMap(_.as[Json])
      yield assertEquals(openapi.hcursor.downField("paths").focus.get.asObject.get.size, 7)
    } }
  }

  test("concurrent config updates keep every job and runtime never rewrites configuration") {
    temporary.use { home => scheduler(home).use { scheduler =>
      for
        _ <- (1 to 20).toList.parTraverse_(i => scheduler.put(s"job-$i", job()))
        config <- scheduler.config
        _ = assertEquals(config.jobs.size, 20)
        before <- IO.blocking(Files.readString(home.resolve("schedule.json")))
        _ <- scheduler.dispatch
        rows <- eventually(scheduler.list)(_.forall(_.lastExitCode.contains(0)))
        _ = assertEquals(rows.flatMap(_.lastTaskId).distinct.size, 20)
        after <- IO.blocking(Files.readString(home.resolve("schedule.json")))
      yield assertEquals(after, before)
    } }
  }

  test("resource queue skips blocked jobs, never overlaps and honours disabled jobs") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
        for
          _ <- scheduler.writeConfig(ScheduleConfig(Map("cpu" -> 1d), Map("a" -> job("a", 1), "b" -> job("b", 1), "c" -> job("c"))))
          _ <- scheduler.dispatch
          calls <- control.calls.get
          _ = assertEquals(calls.map(_.source), Vector("a", "c"))
          rows <- scheduler.list
          _ = assertEquals(rows.map(row => row.name -> row.status).toMap, Map("a" -> "running", "b" -> "queued", "c" -> "running"))
          _ <- scheduler.dispatch
          count <- control.calls.get
          _ = assertEquals(count.size, 2)
          config <- scheduler.config
          _ <- scheduler.writeConfig(config.copy(jobs = config.jobs.updated("b", job("b", 1).copy(enabled = false))))
          _ <- calls.traverse_(call => control.finish(call.task.id))
          _ <- eventually(scheduler.list)(_.filter(_.name != "b").forall(_.status == "waiting"))
          _ <- scheduler.dispatch
          disabled <- scheduler.list
          _ = assertEquals(disabled.find(_.name == "b").get.status, "disabled")
          _ <- scheduler.writeConfig(config)
          _ <- scheduler.dispatch
          calls <- control.calls.get
          _ = assertEquals(calls.size, 3)
          _ <- control.finish(calls.last.task.id)
          _ <- eventually(scheduler.list)(_.forall(_.lastExitCode.contains(0)))
        yield ()
      }
    } }
  }

  test("failures submit distinct tasks with capped exponential retries, release resources and stop at the limit") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
        for
          _ <- scheduler.writeConfig(ScheduleConfig(Map("cpu" -> 1d), Map("a" -> job("fail", 1).copy(retry = RetryPolicy(maxDelay = "3s")), "b" -> job("other", 1))))
          _ <- scheduler.dispatch
          first <- control.calls.get
          _ <- control.finish(first.head.task.id, "failed")
          rows <- eventually(scheduler.list)(_.head.status == "retrying")
          _ = assertEquals(rows.head.attempt, 1)
          _ <- scheduler.dispatch
          calls <- control.calls.get
          _ = assertEquals(calls.map(_.source), Vector("fail", "other"))
          _ <- control.finish(calls.last.task.id)
          _ <- eventually(scheduler.list)(_.last.lastExitCode.contains(0))
          _ <- (2 to 4).toList.traverse_ { attempt =>
            for
              state <- new Store(home).state("a")
              delay = java.time.Duration.between(Instant.parse(state.lastFinishedAt.get), Instant.parse(state.nextRetryAt.get)).toMillis
              _ = assertEquals(delay, List(1000L, 2000L, 3000L)(attempt - 2))
              _ <- retryDue(home, "a")
              _ <- scheduler.dispatch
              started <- control.calls.get
              _ <- scheduler.dispatch
              unchanged <- control.calls.get
              _ = assertEquals(unchanged.size, started.size)
              _ <- control.finish(started.last.task.id, "failed")
              _ <- eventually(scheduler.list)(_.head.lastExitCode.contains(1))
            yield ()
          }
          rows <- scheduler.list
          _ = assertEquals(rows.head.attempt, 4)
          _ = assertEquals(rows.head.nextRetryAt, None)
          _ = assertEquals(rows.head.status, "waiting")
          _ <- scheduler.dispatch
          calls <- control.calls.get
        yield assertEquals(calls.map(_.task.id).distinct.size, 5)
      }
    } }
  }

  test("successful retries clear retry state and the next normal interval starts at attempt one; maxRetries zero disables retries") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
        for
          _ <- scheduler.put("sample", job().copy(every = "0ms", retry = RetryPolicy(delay = "0ms")))
          _ <- scheduler.dispatch
          calls <- control.calls.get
          _ <- control.finish(calls.last.task.id, "failed")
          _ <- eventually(scheduler.list)(_.head.status == "retrying")
          _ <- scheduler.dispatch
          calls <- control.calls.get
          _ <- control.finish(calls.last.task.id)
          rows <- eventually(scheduler.list)(_.head.lastExitCode.contains(0))
          _ = assertEquals(rows.head.attempt, 2)
          _ = assertEquals(rows.head.nextRetryAt, None)
          _ <- scheduler.dispatch
          rows <- scheduler.list
          _ = assertEquals(rows.head.attempt, 1)
          _ <- scheduler.put("no-retry", job().copy(retry = RetryPolicy(maxRetries = 0)))
          _ <- scheduler.dispatch
          calls <- control.calls.get
          _ <- control.finish(calls.last.task.id, "failed")
          rows <- eventually(scheduler.list)(_.find(_.name == "no-retry").get.lastExitCode.contains(1))
        yield assertEquals(rows.find(_.name == "no-retry").get.nextRetryAt, None)
      }
    } }
  }

  test("delete/recreate, reset and config replacement retain reservations but discard stale results and retries") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
        for
          _ <- scheduler.put("same", job("old"))
          _ <- List("recreate", "reset", "replace", "disable").traverse_ { operation =>
            for
              _ <- scheduler.dispatch
              calls <- control.calls.get
              _ <- operation match
                case "recreate" => scheduler.delete("same") *> scheduler.put("same", job("new")).void
                case "reset" => scheduler.reset("same").void
                case _ => scheduler.config.flatMap(config => scheduler.writeConfig(config.copy(jobs = Map("same" -> job(operation).copy(enabled = operation != "disable"))))).void
              _ <- scheduler.dispatch
              still <- control.calls.get
              _ = assertEquals(still.size, calls.size)
              _ <- control.finish(calls.last.task.id, "failed")
              rows <- eventually(scheduler.list)(_.head.status != "running")
              _ = assertEquals(rows.head.lastExitCode, None)
              _ = assertEquals(rows.head.lastTaskId, None)
              _ = assertEquals(rows.head.nextRetryAt, None)
            yield ()
          }
        yield ()
      }
    } }
  }

  test("retry deadlines and attempt counts survive restart; completed and never-admitted in-flight attempts recover without replay") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      val store = new Store(home)
      for
        saved <- Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
          for
            _ <- scheduler.put("sample", job())
            _ <- scheduler.dispatch
            calls <- control.calls.get
            _ <- control.finish(calls.head.task.id, "failed")
            _ <- eventually(scheduler.list)(_.head.nextRetryAt.nonEmpty)
            state <- store.state("sample")
          yield state
        }
        _ <- Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
          for
            state <- store.state("sample")
            _ = assertEquals(state, saved)
            _ <- scheduler.dispatch
            calls <- control.calls.get
            _ = assertEquals(calls.size, 1)
          yield ()
        }
        // Simulate process loss after GraalJS finished but before schedule persisted the result.
        _ <- store.writeState("sample", saved.copy(lastExitCode = None, nextRetryAt = None, inFlight = true))
        _ <- Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
          for
            rows <- eventually(scheduler.list)(_.head.nextRetryAt.nonEmpty)
            _ = assertEquals(rows.head.lastTaskId, saved.lastTaskId)
            _ = assertEquals(rows.head.nextRetryAt, saved.nextRetryAt)
            calls <- control.calls.get
            _ = assertEquals(calls.size, 1)
          yield ()
        }
        // Simulate process loss after schedule persisted its ID but before GraalJS accepted it.
        _ <- store.writeState("sample", saved.copy(lastTaskId = Some("gjs_" + java.util.UUID.randomUUID().toString.replace("-", "")), attempt = 2, nextRetryAt = None, inFlight = true))
        _ <- Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
          for
            rows <- eventually(scheduler.list)(_.head.nextRetryAt.nonEmpty)
            _ = assertEquals(rows.head.attempt, 2)
            _ = assertEquals(rows.head.lastTaskState, Some("interrupted"))
            _ <- retryDue(home, "sample")
            _ <- scheduler.dispatch
            rows <- scheduler.list
            _ = assertEquals(rows.head.attempt, 3)
            calls <- control.calls.get
          yield assertEquals(calls.size, 2)
        }
      yield ()
    } }
  }

  test("shutdown waits for context cleanup before releasing scheduler ownership and persists retry state") {
    temporary.use { home =>
      Deferred[IO, Unit].flatMap { release => Stub.resource(home.resolve("graaljs"), cleanup = release.get).use { (manager, control) =>
        (for
          allocated <- Scheduler.resource(manager, home, startLoop = false).allocated
          (scheduler, close) = allocated
          _ <- scheduler.put("sample", job())
          _ <- scheduler.dispatch
          calls <- control.calls.get
          closing <- close.start
          _ <- calls.head.cancelled.get
          second <- Scheduler.resource(manager, home, startLoop = false).use(_ => IO.unit).attempt
          _ = assert(second.isLeft)
          task <- manager.status(calls.head.task.id)
          _ = assertEquals(task.state, "cancelling")
          _ <- release.complete(())
          _ <- closing.joinWithNever.timeout(5.seconds)
          _ <- Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
            scheduler.list.map { rows =>
              assertEquals(rows.head.lastTaskState, Some("cancelled"))
              assertEquals(rows.head.attempt, 1)
              assert(rows.head.nextRetryAt.nonEmpty)
            }
          }
        yield ()).guarantee(release.complete(()).void)
      } }
    }
  }

  test("recovery ignores stale state if a crash interrupted invalidation after config replacement") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      for
        saved <- Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
          for
            _ <- scheduler.put("sample", job("old"))
            _ <- scheduler.dispatch
            calls <- control.calls.get
            _ <- control.finish(calls.head.task.id, "failed")
            _ <- eventually(scheduler.list)(_.head.nextRetryAt.nonEmpty)
            state <- new Store(home).state("sample")
          yield state
        }
        _ <- new Store(home).writeConfig(ScheduleConfig.empty.copy(jobs = Map("sample" -> job("new"))))
        _ <- new Store(home).writeState("sample", saved.copy(inFlight = true))
        _ <- Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
          for
            rows <- eventually(scheduler.list)(_.head.status == "due")
            _ = assertEquals(rows.head.lastExitCode, None)
            _ = assertEquals(rows.head.nextRetryAt, None)
            _ <- scheduler.dispatch
            rows <- scheduler.list
            _ = assertEquals(rows.head.attempt, 1)
            calls <- control.calls.get
          yield assertEquals(calls.map(_.source), Vector("old", "new"))
        }
        // A deletion can likewise have persisted config before removing its separate state file.
        _ <- new Store(home).writeConfig(ScheduleConfig.empty)
        _ <- new Store(home).writeState("sample", saved)
        _ <- Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
          scheduler.writeConfig(ScheduleConfig.empty.copy(jobs = Map("sample" -> job("old")))) *>
            scheduler.list.map { rows =>
              assertEquals(rows.head.attempt, 0)
              assertEquals(rows.head.nextRetryAt, None)
            }
        }
      yield ()
    } }
  }

  test("real GraalJS tasks capture stdout/stderr, fail on script errors and retry timeouts") {
    temporary.use { home => GraalManager.resource(home.resolve("graaljs")).use { manager =>
      Scheduler.resource(manager, home, startLoop = false).use { scheduler =>
        for
          _ <- scheduler.put("a/b 空", job("console.log('中文🙂'); console.error('stderr'); throw Error('broken')"))
          _ <- scheduler.put("timeout", job("await new Promise(() => {})").copy(timeout = "500ms"))
          _ <- scheduler.dispatch
          rows <- eventually(scheduler.list)(_.forall(_.lastExitCode.nonEmpty))
          _ = assertEquals(rows.map(_.lastTaskState).distinct, List(Some("failed")))
          _ = assertEquals(rows.last.lastError.map(_.code), Some("timeout"))
          log <- scheduler.log("a/b 空", 100)
          _ = assert(log.contains("中文🙂") && log.contains("stderr") && log.contains("broken"), log)
          task <- manager.status(rows.head.lastTaskId.get)
          _ = assertEquals(task.state, "failed")
          _ <- scheduler.delete("a/b 空")
          history <- new Store(home).log("a/b 空", 100)
        yield assert(history.contains(task.id))
      }
    } }
  }

  test("schedule saves the returned task ID and retains live ownership if that state write fails") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      Ref.of[IO, Boolean](true).flatMap { failOnce =>
        val store = new Store(home):
          override def writeState(name: String, state: JobState) =
            if state.inFlight then failOnce.getAndSet(false).flatMap { fail =>
              if fail then IO.raiseError(new java.io.IOException("injected post-admission persistence failure"))
              else super.writeState(name, state)
            }
            else super.writeState(name, state)
        Scheduler.managed(manager, store, startLoop = false).use { scheduler =>
          for
            _ <- scheduler.put("sample", job())
            failed <- scheduler.dispatch.attempt
            _ = assert(failed.isLeft)
            calls <- control.calls.get
            _ = assertEquals(calls.size, 1)
            _ <- scheduler.dispatch
            still <- control.calls.get
            _ = assertEquals(still.size, 1)
            _ <- control.finish(calls.head.task.id)
            rows <- eventually(scheduler.list)(_.head.lastExitCode.contains(0))
          yield assertEquals(rows.head.lastTaskId, Some(calls.head.task.id))
        }
      }
    } }
  }

  test("log tails handle UTF-8, block boundaries, trailing newlines and missing files") {
    temporary.use { home =>
      val store = new Store(home)
      for
        _ <- store.appendLog("sample", "old\n" * 10000 + "第一行\nsecond\nthird\n")
        tail <- store.log("sample", 3)
        _ = assertEquals(tail, "第一行\nsecond\nthird")
        _ <- store.appendLog("sample", "unterminated")
        tail <- store.log("sample", 2)
        _ = assertEquals(tail, "third\nunterminated")
        absent <- store.log("missing", 10)
      yield assertEquals(absent, "")
    }
  }
