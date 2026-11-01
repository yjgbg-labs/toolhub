package groot.schedule

import cats.effect.IO
import cats.syntax.all.*
import groot.graaljs.{GraalManager, JavaScript, ScheduleTestSupport}
import java.nio.file.{Files, Path, StandardCopyOption}
import munit.CatsEffectSuite
import org.graalvm.polyglot.Source
import scala.concurrent.duration.*
import scala.util.Using
import groot.graaljs.GraalTestSupport.*

class ScriptSourcesSuite extends CatsEffectSuite:
  private def write(path: Path, text: String): IO[Unit] = IO.blocking(Files.writeString(path, text)).void
  private def hash(text: String): String = java.util.HexFormat.of().formatHex(
    java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  private def source(sources: ScriptSources, path: Path, text: String): IO[Source] =
    sources.snapshot(path).map { result =>
      assertEquals(result.getCharacters.toString, JavaScript.source(text).getCharacters.toString)
      result
    }

  test("each snapshot hashes current bytes even with unchanged size/mtime; identical content reuses Source") {
    temporary.use { home => ScriptSources.resource().use { sources =>
      val path = home.resolve("中文 script.js")
      for
        _ <- write(path, "console.log('v1')")
        lazyCache <- sources.cachedHashes
        _ = assertEquals(lazyCache, Nil)
        first <- source(sources, path, "console.log('v1')")
        same <- sources.snapshot(home.resolve("./中文 script.js"))
        _ = assert(same eq first)
        _ = assertEquals(first.getName, s"sha256-${hash("console.log('v1')")}.graal.js")
        _ <- write(home.resolve("another.js"), "console.log('v1')")
        shared <- source(sources, home.resolve("another.js"), "console.log('v1')")
        _ = assert(shared eq first)
        modified <- IO.blocking(Files.getLastModifiedTime(path))
        _ <- write(path, "console.log('v2')")
        _ <- IO.blocking(Files.setLastModifiedTime(path, modified))
        second <- source(sources, path, "console.log('v2')")
        _ = assert(!(first eq second))
        _ = assert(first.getCharacters.toString.contains("v1"))
        external <- IO.blocking(Files.createDirectories(home.resolve("elsewhere/nested")))
        _ <- write(external.resolve("replace.tmp"), "console.log('v3')")
        _ <- IO.blocking(Files.move(external.resolve("replace.tmp"), path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
        third <- source(sources, path, "console.log('v3')")
        _ <- write(path, "console.log('v3')")
        unchanged <- sources.snapshot(path)
      yield assert(unchanged eq third)
    } }
  }

  test("the next snapshot immediately observes missing files, recreated parents and replaced directories") {
    temporary.use { home => ScriptSources.resource().use { sources =>
      val directory = home.resolve("scripts")
      val path = directory.resolve("task.js")
      for
        missing <- sources.snapshot(path).attempt
        _ = assert(missing.isLeft)
        _ <- IO.blocking(Files.createDirectory(directory))
        _ <- write(path, "1")
        _ <- source(sources, path, "1")
        _ <- IO.blocking(Files.delete(path))
        deleted <- sources.snapshot(path).attempt
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
    temporary.use { home => ScriptSources.resource().use { sources =>
      val link = home.resolve("task.js")
      val target = home.resolve("targets/task.js")
      for
        _ <- IO.blocking(Files.createSymbolicLink(link, Path.of("targets/task.js")))
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
    temporary.use { home => ScriptSources.resource().use { sources =>
      val path = home.resolve("task.js")
      for
        _ <- write(path, "1")
        _ <- source(sources, path, "1")
        _ <- List(Array.emptyByteArray, Array.fill[Byte](JavaScript.maxSourceBytes + 1)(65), Array(0xc3.toByte, 0x28.toByte)).traverse_ { bytes =>
          for
            _ <- IO.blocking(Files.write(path, bytes))
            error <- sources.snapshot(path).attempt
            _ = assert(error.isLeft)
            _ = assert(error.swap.toOption.get.isInstanceOf[ScriptReadError])
            _ <- write(path, "1")
            _ <- source(sources, path, "1")
          yield ()
        }
      yield ()
    } }
  }

  test("the hash LRU promotes hits, bounds old revisions and clears its references on shutdown") {
    temporary.use { home =>
      val path = home.resolve("task.js")
      for
        allocated <- ScriptSources.resource(maxEntries = 2).allocated
        (sources, close) = allocated
        _ <- (for
          _ <- write(path, "1")
          first <- source(sources, path, "1")
          _ <- write(path, "2")
          _ <- source(sources, path, "2")
          _ <- write(path, "1")
          hit <- source(sources, path, "1")
          _ = assert(hit eq first)
          promoted <- sources.cachedHashes
          _ = assertEquals(promoted, List(hash("2"), hash("1")))
          _ <- write(path, "3")
          _ <- source(sources, path, "3")
          evicted <- sources.cachedHashes
          _ = assertEquals(evicted, List(hash("1"), hash("3")))
          _ <- write(path, "2")
          _ <- source(sources, path, "2")
          reloaded <- sources.cachedHashes
          _ = assertEquals(reloaded, List(hash("3"), hash("2")))
        yield ()).guarantee(close.timeout(2.seconds))
        closed <- sources.cachedHashes
      yield assertEquals(closed, Nil)
    }
  }

  test("GraalJS queue and active tasks retain Source identity and persisted text across reload and cache eviction") {
    temporary.use { home => ScriptSources.resource(maxEntries = 1).use { sources =>
      ScheduleTestSupport.resource(home.resolve("graaljs"), budgetBytes = 4L * 1024 * 1024).use { (manager, control) =>
        val path = home.resolve("task.js")
        for
          _ <- write(path, "old")
          old <- source(sources, path, "old")
          first <- manager.start(old, "1s", "30s")
          queued <- manager.start(old, "1s", "30s")
          _ = assertEquals(queued.state, "queued")
          _ <- write(path, "new")
          updated <- source(sources, path, "new")
          cached <- sources.cachedHashes
          _ = assertEquals(cached, List(hash("new")))
          _ <- control.finish(first.id)
          calls <- awaitUntil(control.calls.get)(_.size == 2)
          _ = assert(calls.forall(_.snapshot eq old))
          _ <- IO.blocking {
            Using.resource(java.sql.DriverManager.getConnection(s"jdbc:sqlite:${home.resolve("graaljs/tasks.sqlite3")}")) { db =>
              Using.resource(db.prepareStatement("SELECT source FROM tasks WHERE id = ?")) { query =>
                query.setString(1, queued.id)
                Using.resource(query.executeQuery()) { rows =>
                  assert(rows.next())
                  assertEquals(rows.getString(1), old.getCharacters.toString)
                }
              }
            }
          }
          _ <- control.finish(queued.id)
          _ <- manager.await(queued.id)
          next <- manager.start(updated, "1s", "30s")
          calls <- control.calls.get
          _ = assert(calls.last.snapshot eq updated)
          _ <- control.finish(next.id)
        yield ()
      }
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
          // File errors belong to schedule; no GraalJS task exists until Source construction succeeds.
          _ <- scheduler.put("sample", ScheduleJob("100ms", path.toString, retry = RetryPolicy(delay = "300ms", maxDelay = "300ms")))
          missing <- awaitUntil(scheduler.list)(_.head.lastError.exists(_.code == "source_error"))
          _ = assertEquals(missing.head.lastTaskId, None)
          _ = assertEquals(missing.head.lastTaskState, Some("failed"))
          _ <- write(path, "console.log('recreated')")
          _ <- awaitUntil(scheduler.list)(_.head.lastTaskState.contains("succeeded"))
          logs <- scheduler.log("sample", 100)
        yield assert(logs.contains("repaired") && logs.contains("recreated"), logs)
      }
    } }
  }
