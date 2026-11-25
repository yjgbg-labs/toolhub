package groot.kv

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import scala.jdk.CollectionConverters.*

class KvSuite extends CatsEffectSuite:
  private def key(value: String): Json = Json.obj("key" -> Json.fromString(value))

  test("strings survive reopening exactly in a single JSONL file; writes overwrite and clear is persistent and idempotent") {
    temporary.use { home =>
      val path = home.resolve("键值 ?#")
      val storeFile = path.resolve("kv.jsonl")
      val name = "task:中文/🙂'"
      val value = " \n中文🙂\u0000'\\\" "
      KvService.resource(path).use { service => for
        missing <- service.get(name)
        _ = assertEquals(missing, None)
        _ = assert(!Files.exists(storeFile))
        _ <- service.set(name, "old")
        _ <- service.set(name, value)
        _ <- service.set("empty", "")
      yield () } *> IO.blocking {
        val entries = Files.list(path).iterator().asScala.toVector
        assertEquals(entries.map(_.getFileName.toString), Vector("kv.jsonl"))
        val lines = Files.readAllLines(storeFile, UTF_8).asScala.toVector
        assertEquals(lines.size, 2)
        assert(lines.forall(line => parse(line).isRight), s"Expected valid JSONL lines: $lines")
      } *> KvService.resource(path).use { service => for
        saved <- service.get(name)
        empty <- service.get("empty")
        _ = assertEquals(saved, Some(value))
        _ = assertEquals(empty, Some(""))
        removed <- service.clear(name)
        absent <- service.clear(name)
        _ = assert(removed && !absent)
      yield () } *> KvService.resource(path).use { service => for
        missing <- service.get(name)
        empty <- service.get("empty")
        _ = assertEquals(missing, None)
        _ = assertEquals(empty, Some(""))
      yield () }
    }
  }

  test("concurrent callers and independent instances share atomic values without losing unrelated keys") {
    temporary.use { home => (KvService.resource(home), KvService.resource(home)).tupled.use { (a, b) =>
      val values = (0 until 32).toList.map(i => s"$i:" + "🙂" * 1024)
      for
        _ <- a.set("shared", values.head)
        _ <- values.zipWithIndex.parTraverse_ { (value, i) =>
          val writer = if i % 2 == 0 then a else b
          for
            _ <- writer.set(s"key:$i", value)
            _ <- writer.set("shared", value)
            seen <- b.get("shared")
            _ = assert(seen.exists(values.contains))
          yield ()
        }
        _ <- values.zipWithIndex.traverse_ { (value, i) => b.get(s"key:$i").map(saved => assertEquals(saved, Some(value))) }
      yield ()
    } }
  }

  test("KvService resolves relative and absolute kv.file paths and follows live config updates") {
    temporary.use { home =>
      val defaultDir = home.resolve("conf")
      val customAbs = home.resolve("external/abs.jsonl")
      for
        cfgRef <- Ref.of[IO, KvConfig](KvConfig())
        _ <- KvService.resource(defaultDir, cfgRef.get).use { service =>
          for
            _ <- service.set("k", "default")
            _ = assert(Files.isRegularFile(defaultDir.resolve("kv.jsonl")))
            _ <- cfgRef.set(KvConfig(file = "nested/rel.jsonl"))
            missingRel <- service.get("k")
            _ = assertEquals(missingRel, None)
            _ <- service.set("k", "relative")
            _ = assert(Files.isRegularFile(defaultDir.resolve("nested/rel.jsonl")))
            _ <- cfgRef.set(KvConfig(file = customAbs.toString))
            _ <- service.set("k", "absolute")
            _ = assert(Files.isRegularFile(customAbs))
            relRead <- cfgRef.set(KvConfig(file = "nested/rel.jsonl")) *> service.get("k")
            _ = assertEquals(relRead, Some("relative"))
          yield ()
        }
      yield ()
    }
  }

  test("ordinary GraalJS-only declarations return nullable strings, validate types and limits, and reject public HTTP") {
    temporary.use { home => KvService.resource(home).use { service =>
      val app = httpApp { KvRoutes.register(service) }
      val sdk = GraalCommandSdk(app)
      def set(name: String, value: String) = sdk.call("kv.set", JsonModel[KvWrite].encoder(KvWrite(name, value)))
      for
        _ <- app.endpoints.toList.traverse_ { endpoint =>
          assertEquals(endpoint.exposedOn, Set(framework.Client.graaljs))
          List(Method.GET, Method.POST).traverse_ { method =>
            app.httpApp(Request[IO](method, Uri.unsafeFromString("/" + endpoint.operationId.replace('.', '/'))))
              .map(result => assertEquals(result.status, Status.NotFound))
          }
        }
        missing <- sdk.call("kv.get", key("missing"))
        _ = assertEquals(missing.hcursor.downField("body").focus, Some(Json.Null))
        written <- set("empty", "")
        _ = assertEquals(written.hcursor.get[Int]("status"), Right(200))
        empty <- sdk.call("kv.get", key("empty"))
        _ = assertEquals(empty.hcursor.get[String]("body"), Right(""))
        removed <- sdk.call("kv.clear", key("empty"))
        absent <- sdk.call("kv.clear", key("empty"))
        _ = assertEquals(removed.hcursor.get[Boolean]("body"), Right(true))
        _ = assertEquals(absent.hcursor.get[Boolean]("body"), Right(false))
        _ <- List("", "x" * 129, "nul\u0000key").traverse_ { name =>
          List(set(name, "v"), sdk.call("kv.get", key(name)), sdk.call("kv.clear", key(name))).sequence.map { results =>
            results.foreach(result => assertEquals(result.hcursor.get[Int]("status"), Right(400)))
          }
        }
        _ <- List(Json.fromInt(1), Json.Null, Json.obj()).traverse_ { value =>
          sdk.call("kv.set", Json.obj("key" -> Json.fromString("typed"), "value" -> value))
            .map(result => assertEquals(result.hcursor.get[Int]("status"), Right(400)))
        }
        _ <- set("limit", "🙂" * (KvService.maxValueBytes / 4))
        large <- set("limit", "🙂" * (KvService.maxValueBytes / 4) + "x")
        _ = assertEquals(large.hcursor.get[Int]("status"), Right(413))
        saved <- service.get("limit")
        _ = assertEquals(saved, Some("🙂" * (KvService.maxValueBytes / 4)))
      yield ()
    } }
  }

  test("real isolated GraalJS tasks share committed kv values across failure and service restart") {
    temporary.use { home =>
      def run(source: String, expected: String = "succeeded"): IO[Unit] = KvService.resource(home.resolve("kv")).use { service =>
        val sdk = GraalCommandSdk(httpApp { KvRoutes.register(service) })
        GraalManager.resource(home.resolve("tasks"), IO.pure(sdk)).use { manager => for
          task <- manager.submit(JavaScript.source(source), "1s", "10s")
          done <- manager.await(task.id)
        yield assertEquals(done.state, expected, done.error.toString) }
      }
      run("await tools.kv.set({key:'cycle',value:'第一轮'}); throw Error('after commit');", "failed") *>
        run("""
          const value = (await tools.kv.get({key:'cycle'})).body;
          if (value !== '第一轮') throw Error(JSON.stringify(value));
          await tools.kv.set({key:'cycle',value:''});
          if ((await tools.kv.get({key:'cycle'})).body !== '') throw Error('empty value lost');
          if (!(await tools.kv.clear({key:'cycle'})).body) throw Error('not cleared');
          """) *>
        run("if ((await tools.kv.get({key:'cycle'})).body !== null) throw Error('clear was not persisted');")
    }
  }
