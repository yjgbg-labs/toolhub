package groot.graaljs

import cats.effect.IO
import cats.syntax.all.*
import groot.schedule.{Scheduler, ScheduleJob, RetryPolicy}
import java.nio.file.{Files, Path, StandardCopyOption}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.util.Using
import GraalTestSupport.*

class ScriptSourcesSuite extends CatsEffectSuite:
  private def write(path: Path, text: String): IO[Unit] = IO.blocking(Files.writeString(path, text)).void
  private def source(sources: ScriptSources, path: Path, text: String): IO[GraalSource] =
    sources.snapshot(path).map { result =>
      assertEquals(result.map(_.text), Right(text))
      result.toOption.get
    }

  test("each snapshot hashes current bytes even with unchanged size/mtime; identical content reuses Source") {
    temporary.use { home => ScriptSources.resource.use { sources =>
      val path = home.resolve("中文 script.js")
      for
        _ <- write(path, "console.log('v1')")
        _ <- sources.retain(Set(path, home.resolve("./中文 script.js")))
        lazyCache <- sources.cachedPaths
        _ = assertEquals(lazyCache, Set.empty[Path])
        first <- source(sources, path, "console.log('v1')")
        same <- sources.snapshot(home.resolve("./中文 script.js"))
        _ = assert(same.toOption.get.value eq first.value)
        _ = assertEquals(first.value.getURI, path.toUri)
        modified <- IO.blocking(Files.getLastModifiedTime(path))
        _ <- write(path, "console.log('v2')")
        _ <- IO.blocking(Files.setLastModifiedTime(path, modified))
        second <- source(sources, path, "console.log('v2')")
        _ = assert(!(first.value eq second.value))
        _ = assert(first.value.getCharacters.toString.contains("v1"))
        external <- IO.blocking(Files.createDirectories(home.resolve("elsewhere/nested")))
        _ <- write(external.resolve("replace.tmp"), "console.log('v3')")
        _ <- IO.blocking(Files.move(external.resolve("replace.tmp"), path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
        third <- source(sources, path, "console.log('v3')")
        _ <- write(path, "console.log('v3')")
        unchanged <- sources.snapshot(path)
      yield assert(unchanged.toOption.get.value eq third.value)
    } }
  }

  test("the next snapshot immediately observes missing files, recreated parents and replaced directories") {
    temporary.use { home => ScriptSources.resource.use { sources =>
      val directory = home.resolve("scripts")
      val path = directory.resolve("task.js")
      for
        _ <- sources.retain(Set(path))
        missing <- sources.snapshot(path)
        _ = assert(missing.isLeft)
        _ <- IO.blocking(Files.createDirectory(directory))
        _ <- write(path, "1")
        _ <- source(sources, path, "1")
        _ <- IO.blocking(Files.delete(path))
        deleted <- sources.snapshot(path)
        _ = assert(deleted.isLeft)
        _ <- write(path, "2")
        _ <- source(sources, path, "2")
        _ <- IO.blocking {
          Files.move(directory, home.resolve("old"))
          Files.createDirectory(directory)
          Files.writeString(path, "3")
        }
        _ <- source(sources, path, "3")
        _ <- write(path, "4")
        _ <- source(sources, path, "4")
      yield ()
    } }
  }

  test("each snapshot resolves current symlink targets, including newly created and retargeted files") {
    temporary.use { home => ScriptSources.resource.use { sources =>
      val link = home.resolve("task.js")
      val target = home.resolve("targets/task.js")
      for
        _ <- IO.blocking(Files.createSymbolicLink(link, Path.of("targets/task.js")))
        _ <- sources.retain(Set(link))
        _ <- IO.blocking(Files.createDirectory(target.getParent))
        _ <- write(target, "1")
        _ <- source(sources, link, "1")
        _ <- write(target, "2")
        _ <- source(sources, link, "2")
        _ <- write(home.resolve("second.js"), "3")
        _ <- IO.blocking {
          Files.createSymbolicLink(home.resolve("new-link"), Path.of("second.js"))
          Files.move(home.resolve("new-link"), link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        _ <- source(sources, link, "3")
        _ <- write(home.resolve("second.js"), "4")
        _ <- source(sources, link, "4")
      yield ()
    } }
  }

  test("unreadable content invalidates old code and recovers; no stale fallback for blank/oversize/invalid UTF-8") {
    temporary.use { home => ScriptSources.resource.use { sources =>
      val path = home.resolve("task.js")
      for
        _ <- write(path, "1")
        _ <- sources.retain(Set(path))
        _ <- List(Array.emptyByteArray, Array.fill[Byte](Limits.maxSourceBytes + 1)(65), Array(0xc3.toByte, 0x28.toByte)).traverse_ { bytes =>
          for
            _ <- IO.blocking(Files.write(path, bytes))
            error <- sources.snapshot(path)
            _ = assert(error.isLeft)
            _ = assertEquals(error.swap.toOption.get.code, "source_error")
            _ <- write(path, "1")
            _ <- source(sources, path, "1")
          yield ()
        }
      yield ()
    } }
  }

  test("removing the last reference and closing release cached Sources without preloading files") {
    temporary.use { home =>
      val path = home.resolve("task.js")
      for
        allocated <- ScriptSources.resource.allocated
        (sources, close) = allocated
        _ <- (for
          _ <- write(path, "1")
          _ <- sources.retain(Set(path))
          first <- source(sources, path, "1")
          cached <- sources.cachedPaths
          _ = assertEquals(cached, Set(path))
          _ <- sources.retain(Set.empty)
          empty <- sources.cachedPaths
          _ = assertEquals(empty, Set.empty[Path])
          removed <- sources.snapshot(path)
          _ = assert(removed.isLeft)
          _ <- sources.retain(Set(path))
          second <- source(sources, path, "1")
          _ = assert(!(first.value eq second.value))
        yield ()).guarantee(close.timeout(2.seconds))
        closed <- sources.cachedPaths
      yield assertEquals(closed, Set.empty[Path])
    }
  }

  test("GraalJS queue and active tasks retain Source identity and persisted text across reload and cache eviction") {
    temporary.use { home => ScriptSources.resource.use { sources =>
      ScheduleTestSupport.resource(home.resolve("graaljs"), budgetBytes = Limits.taskBytes).use { (manager, control) =>
        val path = home.resolve("task.js")
        for
          _ <- write(path, "old")
          _ <- sources.retain(Set(path))
          old <- source(sources, path, "old")
          first <- manager.submit(GraalManager.taskId(), Right(old), "1s", "30s")
          queued <- manager.submit(GraalManager.taskId(), Right(old), "1s", "30s")
          _ = assertEquals(queued.state, "queued")
          _ <- write(path, "new")
          updated <- source(sources, path, "new")
          _ <- sources.retain(Set.empty)
          _ <- control.finish(first.id)
          calls <- awaitUntil(control.calls.get)(_.size == 2)
          _ = assert(calls.forall(_.snapshot.value eq old.value))
          _ <- IO.blocking {
            Using.resource(java.sql.DriverManager.getConnection(s"jdbc:sqlite:${home.resolve("graaljs/tasks.sqlite3")}")) { db =>
              Using.resource(db.prepareStatement("SELECT source FROM tasks WHERE id = ?")) { query =>
                query.setString(1, queued.id)
                Using.resource(query.executeQuery()) { rows =>
                  assert(rows.next())
                  assertEquals(rows.getString(1), "old")
                }
              }
            }
          }
          _ <- control.finish(queued.id)
          _ <- manager.await(queued.id)
          next <- manager.submit(GraalManager.taskId(), Right(updated), "1s", "30s")
          calls <- control.calls.get
          _ = assert(calls.last.snapshot.value eq updated.value)
          _ <- control.finish(next.id)
        yield ()
      }
    } }
  }

  test("a cached Source executes concurrently in independent isolated Contexts") {
    temporary.use { home => GraalManager.resource(home.resolve("graaljs")).use { manager =>
      val source = GraalSource.file("globalThis.count = (globalThis.count || 0) + 1; console.log('count', globalThis.count)", home.resolve("shared.js"))
      (1 to 3).toList.parTraverse { _ =>
        for
          task <- manager.submit(GraalManager.taskId(), Right(source), "1s", "30s")
          result <- manager.await(task.id)
          log <- manager.logs(task.id)
        yield
          assertEquals(result.state, "succeeded", result.error.toString)
          assert(log.contains("count 1"), log.toString)
      }.void
    } }
  }

  test("real scheduler rereads scripts on retries, recovering syntax and missing-file failures with distinct IDs") {
    temporary.use { home => GraalManager.resource(home.resolve("graaljs")).use { manager =>
      Scheduler.resource(manager, home).use { scheduler =>
        val path = home.resolve("task.js")
        for
          _ <- write(path, "const broken = ;")
          _ <- scheduler.put("sample", ScheduleJob("1h", path.toString, retry = RetryPolicy(delay = "300ms", maxDelay = "300ms", maxRetries = 10)))
          failed <- awaitUntil(scheduler.list)(_.head.lastError.exists(_.code == "syntax_error"))
          firstId = failed.head.lastTaskId.get
          _ <- write(path, "console.log('repaired')")
          success <- awaitUntil(scheduler.list)(_.head.lastTaskState.contains("succeeded"))
          _ = assert(success.head.attempt >= 2)
          _ = assert(success.head.lastTaskId.get != firstId)
          _ <- IO.blocking(Files.delete(path))
          // The next admission reads the deleted path and still creates a real failed task.
          _ <- scheduler.put("sample", ScheduleJob("100ms", path.toString, retry = RetryPolicy(delay = "300ms", maxDelay = "300ms")))
          missing <- awaitUntil(scheduler.list)(_.head.lastError.exists(_.code == "source_error"))
          task <- manager.status(missing.head.lastTaskId.get)
          _ = assertEquals(task.state, "failed")
          _ <- write(path, "console.log('recreated')")
          _ <- awaitUntil(scheduler.list)(_.head.lastTaskState.contains("succeeded"))
          logs <- scheduler.log("sample", 100)
        yield assert(logs.contains("repaired") && logs.contains("recreated"), logs)
      }
    } }
  }
