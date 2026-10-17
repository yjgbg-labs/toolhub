package groot.schedule

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.typelevel.ci.CIString
import sprout.{Application, JsonModel}
import sprout.rpcDsl.*
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class ScheduleSuite extends CatsEffectSuite:
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "sprout-schedule-test-"))
  )(path => IO.blocking {
    val files = Files.walk(path)
    try files.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    finally files.close()
  })

  private def eventually[A](io: IO[A])(check: A => Boolean): IO[A] =
    io.flatMap(value => if check(value) then IO.pure(value) else IO.sleep(10.millis) *> eventually(io)(check)).timeout(5.seconds)

  private def job(command: String, cpu: Double = 0): ScheduleJob =
    ScheduleJob("1h", List(command), if cpu == 0 then Map.empty else Map("cpu" -> cpu))

  private def app(scheduler: Scheduler): Application = httpApp { ScheduleRoutes.register(scheduler) }
  private def request(path: String, json: Option[String] = None): Request[IO] =
    val request = Request[IO](if json.isDefined then Method.POST else Method.GET, Uri.unsafeFromString(path))
    json.fold(request)(body => request.withEntity(body).putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))

  test("duration syntax, numeric capacities and commands are validated") {
    assertEquals(Validation.durationMs("0ms"), 0L)
    assertEquals(Validation.durationMs("2d"), 172800000L)
    List("1.5h", "1h30m", "-1s", "1w", "99999999999999999999d").foreach { value =>
      intercept[InvalidSchedule](Validation.durationMs(value))
    }
    intercept[InvalidSchedule](Validation.config(ScheduleConfig.empty.copy(tickMs = Double.NaN)))
    intercept[InvalidSchedule](Validation.config(ScheduleConfig.empty.copy(resources = Map("cpu" -> -1d))))
    intercept[InvalidSchedule](Validation.config(ScheduleConfig.empty.copy(jobs = Map("a" -> job("")))))
    assertEquals(Store.fileName("a/b 空 +%"), "a%2Fb%20%E7%A9%BA%20%2B%25")
  }

  test("HTTP operations expose typed schemas, explicit errors and atomic validation") {
    temporary.use { home => Scheduler.resource(home, startLoop = false).use { scheduler =>
      val http = app(scheduler)
      for
        created <- http.httpApp(request("/schedule/new?name=sample", Some("""{"every":"1h","command":["/bin/true"]}""")))
        _ = assertEquals(created.status, Status.Ok)
        _ <- new Store(home).writeState("sample", JobState(Some("2026-09-19T00:00:00Z"), Some(7)))
        invalid <- http.httpApp(request("/schedule/new?name=sample", Some("""{"every":"1h","command":["/bin/true"],"resource":{"cpu":1}}""")))
        _ = assertEquals(invalid.status, Status.BadRequest)
        persisted <- scheduler.list
        _ = assertEquals(persisted.head.lastExitCode, Some(7))
        partial <- http.httpApp(request("/schedule/config/write", Some("{}")))
        _ = assertEquals(partial.status, Status.BadRequest)
        badRuntime <- http.httpApp(request("/schedule/config/write", Some("""{"resources":{},"tickMs":1000,"jobs":{"sample":{"every":"1h","command":["true"],"lastExitCode":0}}}""")))
        _ = assertEquals(badRuntime.status, Status.BadRequest)
        badLines <- http.httpApp(request("/schedule/log?name=sample&lines=0"))
        _ = assertEquals(badLines.status, Status.BadRequest)
        missing <- http.httpApp(request("/schedule/reset?name=missing"))
        _ = assertEquals(missing.status, Status.NotFound)
        reset <- http.httpApp(request("/schedule/reset?name=sample"))
        resetJson <- reset.as[Json]
        _ = assertEquals(resetJson, Json.obj("lastRunAt" -> Json.Null, "lastExitCode" -> Json.Null))
        deleted <- http.httpApp(request("/schedule/delete?name=sample"))
        _ = assertEquals(deleted.status, Status.Ok)
        deletedBody <- deleted.as[String]
        _ = assertEquals(deletedBody, "")
        duplicate <- http.httpApp(request("/schedule/delete?name=sample"))
        _ = assertEquals(duplicate.status, Status.Ok)
        openapi <- http.httpApp(Request[IO](uri = Uri.unsafeFromString("/openapi.json"))).flatMap(_.as[Json])
      yield assertEquals(openapi.hcursor.downField("paths").focus.get.asObject.get.size, 7)
    } }
  }

  test("concurrent config updates keep every job and runtime state never rewrites static configuration") {
    temporary.use { home => Scheduler.resource(home, startLoop = false).use { scheduler =>
      for
        _ <- (1 to 20).toList.parTraverse_(i => scheduler.put(s"job-$i", job("/bin/true")))
        config <- scheduler.config
        _ = assertEquals(config.jobs.size, 20)
        before <- IO.blocking(Files.readString(home.resolve("schedule.json")))
        _ <- scheduler.tick
        _ <- eventually(scheduler.list)(_.forall(_.lastExitCode.contains(0)))
        after <- IO.blocking(Files.readString(home.resolve("schedule.json")))
      yield assertEquals(after, before)
    } }
  }

  test("resource queue skips blocked jobs, never overlaps a job, and honours hot reload") {
    temporary.use { home =>
      for
        gates <- List("a", "b", "c").traverse(name => Deferred[IO, Int].map(name -> _)).map(_.toMap)
        starts <- Ref.of[IO, List[String]](Nil)
        runner: Scheduler.Runner = (job, _) => starts.update(_ :+ job.command.head) *> gates(job.command.head).get
        _ <- Scheduler.resource(home, startLoop = false, runner = runner).use { scheduler =>
          for
            _ <- scheduler.writeConfig(ScheduleConfig(Map("cpu" -> 1d), 10, Map("a" -> job("a", 1), "b" -> job("b", 1), "c" -> job("c"))))
            _ <- scheduler.tick
            _ <- eventually(starts.get)(_.size == 2)
            rows <- scheduler.list
            _ = assertEquals(rows.map(row => row.name -> row.status).toMap, Map("a" -> "running", "b" -> "queued", "c" -> "running"))
            _ <- scheduler.tick
            count <- starts.get
            _ = assertEquals(count.sorted, List("a", "c"))
            config <- scheduler.config
            _ <- scheduler.writeConfig(config.copy(jobs = config.jobs.updated("b", job("b", 1).copy(enabled = false))))
            _ <- gates("a").complete(0)
            _ <- gates("c").complete(0)
            _ <- eventually(scheduler.list)(_.filter(_.name != "b").forall(_.status == "waiting"))
            _ <- scheduler.tick
            disabled <- scheduler.list
            _ = assertEquals(disabled.find(_.name == "b").get.status, "disabled")
            _ <- scheduler.writeConfig(config)
            _ <- scheduler.tick
            _ <- eventually(starts.get)(_.size == 3)
            _ <- gates("b").complete(9)
            rows <- eventually(scheduler.list)(_.find(_.name == "b").get.lastExitCode.contains(9))
          yield assertEquals(rows.find(_.name == "b").get.status, "waiting")
        }
      yield ()
    }
  }

  test("deleting and recreating an active job keeps its reservation and ignores stale completion") {
    temporary.use { home =>
      for
        first <- Deferred[IO, Int]
        second <- Deferred[IO, Int]
        starts <- Ref.of[IO, Int](0)
        runner: Scheduler.Runner = (job, _) => starts.update(_ + 1) *> (if job.command.head == "old" then first.get else second.get)
        _ <- Scheduler.resource(home, startLoop = false, runner = runner).use { scheduler =>
          for
            _ <- scheduler.put("same", job("old"))
            _ <- scheduler.tick
            _ <- eventually(starts.get)(_ == 1)
            _ <- scheduler.delete("same")
            _ <- scheduler.put("same", job("new"))
            _ <- scheduler.tick
            count <- starts.get
            _ = assertEquals(count, 1)
            _ <- first.complete(7)
            rows <- eventually(scheduler.list)(_.head.status == "due")
            _ = assertEquals(rows.head.lastRunAt, None)
            _ = assertEquals(rows.head.lastExitCode, None)
            _ <- scheduler.tick
            _ <- eventually(starts.get)(_ == 2)
            _ <- scheduler.reset("same")
            _ <- scheduler.tick
            count <- starts.get
            _ = assertEquals(count, 2)
            _ <- second.complete(0)
            rows <- eventually(scheduler.list)(_.head.status == "due")
          yield assertEquals(rows.head.lastExitCode, None)
        }
      yield ()
    }
  }

  test("real commands preserve argument boundaries, collect stdout and stderr, and record spawn errors") {
    temporary.use { home => Scheduler.resource(home, startLoop = false).use { scheduler =>
      for
        _ <- scheduler.put("a/b 空", ScheduleJob("1h", List("/bin/sh", "-c", "printf '%s\\n' \"$1\"; printf 'stderr\\n' >&2; exit 7", "test", "literal $HOME && *")))
        _ <- scheduler.put("missing", job("/no/such/executable"))
        _ <- scheduler.tick
        rows <- eventually(scheduler.list)(_.forall(_.lastExitCode.nonEmpty))
        _ = assertEquals(rows.map(row => row.name -> row.lastExitCode.get).toMap, Map("a/b 空" -> 7, "missing" -> 1))
        log <- scheduler.log("a/b 空", 3)
        _ = assert(log.startsWith("literal $HOME && *\nstderr\n"))
        _ = assert(log.endsWith("exit: 7"))
        error <- scheduler.log("missing", 2)
        _ = assert(error.contains("error:"))
        _ <- scheduler.delete("a/b 空")
        history <- new Store(home).log("a/b 空", 1)
      yield assert(history.endsWith("exit: 7"))
    } }
  }

  test("a second scheduler cannot acquire the same directory; state survives restart") {
    temporary.use { home =>
      Scheduler.resource(home, startLoop = false).use { scheduler =>
        for
          second <- Scheduler.resource(home, startLoop = false).use(_ => IO.unit).attempt
          _ = assert(second.isLeft)
          _ <- scheduler.put("sample", job("/bin/true"))
          _ <- scheduler.tick
          _ <- eventually(scheduler.list)(_.head.lastExitCode.contains(0))
        yield ()
      } *> Scheduler.resource(home, startLoop = false).use { scheduler =>
        scheduler.list.map(rows => assertEquals(rows.head.status, "waiting"))
      }
    }
  }

  test("commands use their configured cwd and shutdown terminates active children before releasing ownership") {
    temporary.use { home =>
      val pidFile = home.resolve("child.pid")
      for
        pid <- Scheduler.resource(home, startLoop = false).use { scheduler =>
          for
            _ <- scheduler.put("cwd", job("/bin/pwd").copy(cwd = Some(home.toString)))
            _ <- scheduler.put("long", ScheduleJob("1h", List("/bin/sh", "-c", "echo $$ > \"$1\"; sleep 30", "test", pidFile.toString)))
            _ <- scheduler.tick
            _ <- eventually(scheduler.list)(_.find(_.name == "cwd").get.lastExitCode.contains(0))
            log <- scheduler.log("cwd", 2)
            _ = assert(log.startsWith(home.toString + "\n"))
            _ <- eventually(IO.blocking(Files.exists(pidFile)))(identity)
            pid <- IO.blocking(Files.readString(pidFile).trim.toLong)
          yield pid
        }
        _ <- IO(assert(!ProcessHandle.of(pid).isPresent || !ProcessHandle.of(pid).get().isAlive))
        _ <- Scheduler.resource(home, startLoop = false).use { scheduler =>
          scheduler.list.map(rows => assertEquals(rows.find(_.name == "long").get.lastExitCode, Some(143)))
        }
      yield ()
    }
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
