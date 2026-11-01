package groot.schedule

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import groot.graaljs.{GraalManager, ScheduleTestSupport as Stub}
import framework.model.JsonModel
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import java.time.Instant
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class EventSchedulerSuite extends ScriptFixtures:
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "schedule-events-"))
  )(path => IO.blocking {
    val files = Files.walk(path)
    try files.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    finally files.close()
  })

  private def eventually[A](read: IO[A], timeout: FiniteDuration = 3.seconds)(check: A => Boolean): IO[A] =
    def loop: IO[A] = read.flatMap(value => if check(value) then IO.pure(value) else IO.sleep(5.millis) *> loop)
    loop.timeout(timeout)

  private def counting(home: Path, reads: Ref[IO, Int]): Store = new Store(home):
    override def config = reads.update(_ + 1) *> super.config

  test("legacy tickMs is readable but the current configuration and schema have no polling field") {
    val model = JsonModel[ScheduleConfig]
    val config = parse("""{"resources":{},"tickMs":1000,"jobs":{}}""").flatMap(model.decoder.decodeJson).toOption.get
    assertEquals(config, ScheduleConfig.empty)
    val encoded = model.encoder(config)
    assertEquals(encoded.asObject.get.keys.toSet, Set("resources", "jobs"))
    assertEquals(model.decoder.decodeJson(encoded), Right(config))
  }

  test("empty and disabled-only schedulers do not poll storage") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, _) =>
      Ref.of[IO, Int](0).flatMap { reads => Scheduler.managed(manager, counting(home, reads)).use { scheduler =>
        for
          _ <- eventually(reads.get)(_ >= 2)
          _ <- IO.sleep(50.millis)
          before <- reads.get
          _ <- IO.sleep(1100.millis)
          after <- reads.get
          _ = assertEquals(after, before, "empty scheduler polled storage")
          _ <- scheduler.put("disabled", job("1").copy(enabled = false))
          _ <- eventually(reads.get)(_ >= after + 2)
          _ <- IO.sleep(50.millis)
          before <- reads.get
          _ <- IO.sleep(1100.millis)
          after <- reads.get
        yield assertEquals(after, before, "disabled-only scheduler polled storage")
      } }
    } }
  }

  test("new/reset interrupt a distant deadline and disabling cancels the pending interval") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      Scheduler.resource(manager, home).use { scheduler =>
        for
          _ <- scheduler.put("sample", job("1"))
          first <- eventually(control.calls.get, 700.millis)(_.size == 1)
          _ <- control.finish(first.head.task.id)
          _ <- eventually(scheduler.list)(_.head.lastExitCode.contains(0))
          _ <- scheduler.reset("sample")
          reset <- eventually(control.calls.get, 700.millis)(_.size == 2)
          _ <- control.finish(reset.last.task.id)
          _ <- eventually(scheduler.list)(_.head.lastExitCode.contains(0))
          _ <- scheduler.put("sample", job("1").copy(every = "250ms"))
          immediate <- eventually(control.calls.get, 700.millis)(_.size == 3)
          _ <- control.finish(immediate.last.task.id)
          interval <- eventually(control.calls.get, 800.millis)(_.size == 4)
          gap = java.time.Duration.between(Instant.parse(immediate.last.task.createdAt), Instant.parse(interval.last.task.createdAt)).toMillis
          _ = assert(gap >= 250 && gap < 800, s"interval was $gap ms")
          config <- scheduler.config
          _ <- scheduler.writeConfig(config.copy(jobs = config.jobs.updated("sample", config.jobs("sample").copy(enabled = false))))
          _ <- control.finish(interval.last.task.id, "failed")
          _ <- eventually(scheduler.list)(_.head.status == "disabled")
          _ <- IO.sleep(400.millis)
          unchanged <- control.calls.get
          _ = assertEquals(unchanged.size, 4)
          _ <- scheduler.delete("sample")
        yield ()
      }
    } }
  }

  test("resource-blocked queues sleep and resume immediately on completion or capacity changes") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      Ref.of[IO, Int](0).flatMap { reads => Scheduler.managed(manager, counting(home, reads)).use { scheduler =>
        for
          _ <- scheduler.writeConfig(ScheduleConfig(Map("cpu" -> 1d), Map("a" -> job("a", 1), "b" -> job("b", 1), "c" -> job("c", 1))))
          first <- eventually(control.calls.get, 700.millis)(_.size == 1)
          _ <- eventually(scheduler.list)(_.count(_.status == "queued") == 2)
          _ <- IO.sleep(50.millis)
          before <- reads.get
          _ <- IO.sleep(1100.millis)
          after <- reads.get
          _ = assertEquals(after, before, "resource-blocked queue spun on an expired deadline")
          _ <- control.finish(first.head.task.id)
          second <- eventually(control.calls.get, 700.millis)(_.size == 2)
          _ = assertEquals(second.last.source, "b")
          config <- scheduler.config
          _ <- scheduler.writeConfig(config.copy(resources = Map("cpu" -> 2d)))
          third <- eventually(control.calls.get, 700.millis)(_.size == 3)
          _ = assertEquals(third.last.source, "c")
          _ <- third.tail.traverse_(call => control.finish(call.task.id))
        yield ()
      } }
    } }
  }

  test("coalesced concurrent config/completion events do not lose wakeups or duplicate tasks") {
    temporary.use { home => Stub.resource(home.resolve("graaljs"), autoFinish = true).use { (manager, control) =>
      Scheduler.resource(manager, home).use { scheduler =>
        for
          _ <- (1 to 30).toList.parTraverse_(i => scheduler.put(s"job-$i", job(s"console.log($i)")))
          rows <- eventually(scheduler.list, 5.seconds)(rows => rows.size == 30 && rows.forall(_.lastTaskState.contains("succeeded")))
          calls <- control.calls.get
          _ = assertEquals(calls.size, 30)
          _ = assertEquals(calls.map(_.source).distinct.size, 30)
        yield assertEquals(rows.flatMap(_.lastTaskId).distinct.size, 30)
      }
    } }
  }

  test("restart arms the persisted retry deadline and subsecond backoff runs without tick rounding") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      for
        first <- Scheduler.resource(manager, home).use { scheduler =>
          for
            _ <- scheduler.put("sample", job("1").copy(retry = RetryPolicy(maxRetries = 2, delay = "300ms", maxDelay = "600ms")))
            calls <- eventually(control.calls.get)(_.size == 1)
            _ <- control.finish(calls.head.task.id, "failed")
            row <- eventually(scheduler.list)(_.head.nextRetryAt.nonEmpty)
          yield row.head
        }
        _ <- Scheduler.resource(manager, home).use { scheduler =>
          for
            second <- eventually(control.calls.get, 850.millis)(_.size == 2)
            _ = assert(!Instant.parse(second.last.task.createdAt).isBefore(Instant.parse(first.nextRetryAt.get)))
            _ <- control.finish(second.last.task.id, "failed")
            third <- eventually(control.calls.get, 900.millis)(_.size == 3)
            secondTask <- manager.status(second.last.task.id)
            gap = java.time.Duration.between(Instant.parse(secondTask.finishedAt.get), Instant.parse(third.last.task.createdAt)).toMillis
            _ = assert(gap >= 600 && gap < 900, s"retry delay was $gap ms")
            _ <- control.finish(third.last.task.id)
            rows <- eventually(scheduler.list)(_.head.lastExitCode.contains(0))
          yield
            assertEquals(rows.head.attempt, 3)
            assertEquals(rows.head.nextRetryAt, None)
        }
      yield ()
    } }
  }

  test("storage faults use a bounded recovery timer and valid config events interrupt that timer") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      for
        broken <- Ref.of[IO, Boolean](false)
        reads <- Ref.of[IO, Int](0)
        store = new Store(home):
          override def config = reads.update(_ + 1) *> broken.get.flatMap { fail =>
            if fail then IO.raiseError(new java.io.IOException("injected config failure")) else super.config
          }
        _ <- Scheduler.managed(manager, store).use { scheduler =>
          for
            _ <- eventually(reads.get)(_ >= 2)
            _ <- IO.sleep(30.millis)
            before <- reads.get
            _ <- broken.set(true)
            rejected <- scheduler.put("sample", job("1")).attempt
            _ = assert(rejected.isLeft)
            _ <- eventually(reads.get)(_ >= before + 2)
            failed <- reads.get
            _ <- IO.sleep(200.millis)
            stable <- reads.get
            _ = assertEquals(stable, failed, "storage failure caused a busy loop")
            _ <- broken.set(false)
            _ <- scheduler.put("sample", job("1"))
            calls <- eventually(control.calls.get, 700.millis)(_.size == 1)
            _ <- control.finish(calls.head.task.id)
          yield ()
        }
      yield ()
    } }
  }

  test("failed result persistence is retried on its recovery deadline without resubmitting the guest") {
    temporary.use { home => Stub.resource(home.resolve("graaljs")).use { (manager, control) =>
      for
        broken <- Ref.of[IO, Boolean](true)
        failures <- Ref.of[IO, Int](0)
        store = new Store(home):
          override def writeState(name: String, value: JobState) = broken.get.flatMap { fail =>
            if fail && !value.inFlight then failures.update(_ + 1) *> IO.raiseError(new java.io.IOException("injected state failure"))
            else super.writeState(name, value)
          }
        _ <- Scheduler.managed(manager, store).use { scheduler =>
          for
            _ <- scheduler.put("sample", job("1"))
            calls <- eventually(control.calls.get)(_.size == 1)
            _ <- control.finish(calls.head.task.id)
            _ <- eventually(failures.get)(_ == 1)
            _ <- IO.sleep(250.millis)
            count <- failures.get
            _ = assertEquals(count, 1, "result observation spun instead of backing off")
            _ <- broken.set(false)
            rows <- eventually(scheduler.list, 2.seconds)(_.head.lastExitCode.contains(0))
            allCalls <- control.calls.get
            _ = assertEquals(allCalls.size, 1)
          yield assertEquals(rows.head.lastTaskId, Some(calls.head.task.id))
        }
      yield ()
    } }
  }
