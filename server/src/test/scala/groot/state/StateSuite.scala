package groot.state

import cats.effect.IO
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

class StateSuite extends CatsEffectSuite:
  private def key(value: String): Json = Json.obj("key" -> Json.fromString(value))

  test("strings survive reopening exactly; writes overwrite and clear is persistent and idempotent") {
    temporary.use { home =>
      val path = home.resolve("状态 ?#")
      val name = "task:中文/🙂'"
      val value = " \n中文🙂\u0000'\\\" "
      StateService.resource(path).use { service => for
        missing <- service.get(name)
        _ = assertEquals(missing, None)
        _ <- service.set(name, "old")
        _ <- service.set(name, value)
        _ <- service.set("empty", "")
      yield () } *> StateService.resource(path).use { service => for
        saved <- service.get(name)
        empty <- service.get("empty")
        _ = assertEquals(saved, Some(value))
        _ = assertEquals(empty, Some(""))
        removed <- service.clear(name)
        absent <- service.clear(name)
        _ = assert(removed && !absent)
      yield () } *> StateService.resource(path).use { service => for
        missing <- service.get(name)
        empty <- service.get("empty")
        _ = assertEquals(missing, None)
        _ = assertEquals(empty, Some(""))
      yield () }
    }
  }

  test("concurrent callers and independent connections share atomic values without losing unrelated keys") {
    temporary.use { home => (StateService.resource(home), StateService.resource(home)).tupled.use { (a, b) =>
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

  test("ordinary GraalJS-only declarations return nullable strings, validate types and limits, and reject public HTTP") {
    temporary.use { home => StateService.resource(home).use { service =>
      val app = httpApp { StateRoutes.register(service) }
      val sdk = GraalCommandSdk(app)
      def set(name: String, value: String) = sdk.call("state.set", JsonModel[StateWrite].encoder(StateWrite(name, value)))
      for
        _ <- app.endpoints.toList.traverse_ { endpoint =>
          assertEquals(endpoint.exposedOn, Set(framework.Client.graaljs))
          List(Method.GET, Method.POST).traverse_ { method =>
            app.httpApp(Request[IO](method, Uri.unsafeFromString("/" + endpoint.operationId.replace('.', '/'))))
              .map(result => assertEquals(result.status, Status.NotFound))
          }
        }
        missing <- sdk.call("state.get", key("missing"))
        _ = assertEquals(missing.hcursor.downField("body").focus, Some(Json.Null))
        written <- set("empty", "")
        _ = assertEquals(written.hcursor.get[Int]("status"), Right(200))
        empty <- sdk.call("state.get", key("empty"))
        _ = assertEquals(empty.hcursor.get[String]("body"), Right(""))
        removed <- sdk.call("state.clear", key("empty"))
        absent <- sdk.call("state.clear", key("empty"))
        _ = assertEquals(removed.hcursor.get[Boolean]("body"), Right(true))
        _ = assertEquals(absent.hcursor.get[Boolean]("body"), Right(false))
        _ <- List("", "x" * 129, "nul\u0000key").traverse_ { name =>
          List(set(name, "v"), sdk.call("state.get", key(name)), sdk.call("state.clear", key(name))).sequence.map { results =>
            results.foreach(result => assertEquals(result.hcursor.get[Int]("status"), Right(400)))
          }
        }
        _ <- List(Json.fromInt(1), Json.Null, Json.obj()).traverse_ { value =>
          sdk.call("state.set", Json.obj("key" -> Json.fromString("typed"), "value" -> value))
            .map(result => assertEquals(result.hcursor.get[Int]("status"), Right(400)))
        }
        _ <- set("limit", "🙂" * (StateService.maxValueBytes / 4))
        large <- set("limit", "🙂" * (StateService.maxValueBytes / 4) + "x")
        _ = assertEquals(large.hcursor.get[Int]("status"), Right(413))
        saved <- service.get("limit")
        _ = assertEquals(saved, Some("🙂" * (StateService.maxValueBytes / 4)))
      yield ()
    } }
  }

  test("real isolated GraalJS tasks share committed state across failure and service restart") {
    temporary.use { home =>
      def run(source: String, expected: String = "succeeded"): IO[Unit] = StateService.resource(home.resolve("state")).use { service =>
        val sdk = GraalCommandSdk(httpApp { StateRoutes.register(service) })
        GraalManager.resource(home.resolve("tasks"), IO.pure(sdk)).use { manager => for
          task <- manager.start(JavaScript.source(source), "1s", "10s")
          done <- manager.await(task.id)
        yield assertEquals(done.state, expected, done.error.toString) }
      }
      run("await tools.state.set({key:'cycle',value:'第一轮'}); throw Error('after commit');", "failed") *>
        run("""
          const value = (await tools.state.get({key:'cycle'})).body;
          if (value !== '第一轮') throw Error(JSON.stringify(value));
          await tools.state.set({key:'cycle',value:''});
          if ((await tools.state.get({key:'cycle'})).body !== '') throw Error('empty value lost');
          if (!(await tools.state.clear({key:'cycle'})).body) throw Error('not cleared');
          """) *>
        run("if ((await tools.state.get({key:'cycle'})).body !== null) throw Error('clear was not persisted');")
    }
  }
