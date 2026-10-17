package sprouttests

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import java.util.concurrent.atomic.AtomicInteger
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.{middleware as _, *}
import org.typelevel.ci.CIString
import scala.collection.mutable.ArrayBuffer
import scala.compiletime.testing.typeCheckErrors
import sprout.{Application, CommandsScope}
import sprout.rpcDsl.*

class GroupMiddlewareSuite extends CatsEffectSuite:
  private def request(path: String, method: Method = Method.GET): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))

  private def operationDoc(app: Application, path: String, method: String = "get") =
    io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths").downField(path).downField(method)

  test("nested commands inherit parameters, defaults, constraints and request-local values") {
    val app = httpApp { commands("api") {
      val tenant = param[String]("tenant", "Tenant") { minLength(1) }
      commands("users") {
        val limit = param[Int]("limit", "Limit") { default(20); minimum(1) }
        val token = param[String]("token", "Shared token")
        val session = param[Option[String]]("session", "Session")
        command[List[String]]("get") {
          val id = param[Long]("id", "User ID")
          handle { IO.cede.as(List(tenant.value, id.value.toString, limit.value.toString, token.value, session.value.getOrElse("none"))) }
        }
        commands("admin") {
          command[String]("read") { handle { IO.cede *> IO(s"${tenant.value}:${token.value}:${limit.value}") } }
        }
      }
    } }
    val parameters = operationDoc(app, "/api/users/get").get[List[Json]]("parameters").toOption.get
    assertEquals(parameters.map(p => p.hcursor.get[String]("name").toOption.get), List("tenant", "limit", "token", "session", "id"))
    assertEquals(parameters.map(p => p.hcursor.get[String]("in").toOption.get), List.fill(5)("query"))
    assertEquals(parameters(1).hcursor.downField("schema").get[Int]("default"), Right(20))
    assertEquals(parameters(1).hcursor.downField("schema").get[Int]("minimum"), Right(1))
    assertEquals(parameters(2).hcursor.get[String]("description"), Right("Shared token"))
    for
      first <- app.httpApp(request("/api/users/get?tenant=acme&id=7&token=secret&session=abc")).flatMap(_.as[Json])
      _ = assertEquals(first, Json.fromValues(List("acme", "7", "20", "secret", "abc").map(Json.fromString)))
      _ <- (1 to 50).toList.parTraverse_ { n =>
        app.httpApp(request(s"/api/users/admin/read?tenant=t$n&limit=$n&token=key$n"))
          .flatMap(_.as[String]).map(value => assertEquals(value, s"t$n:key$n:$n"))
      }
      invalid <- app.httpApp(request("/api/users/get?tenant=acme&id=7&limit=0&token=secret"))
    yield assertEquals(invalid.status, Status.BadRequest)
  }

  test("inherited parameters retain ancestor declaration order without changing command paths") {
    val app = httpApp { commands("tenants") {
      val tenant = param[String]("tenant", "Tenant")
      commands("users") {
        val section = param[String]("section", "Section")
        command[String]("get") {
          val id = param[Int]("id", "User ID")
          handle { IO.pure(s"${tenant.value}:${section.value}:${id.value}") }
        }
      }
    } }
    val op = operationDoc(app, "/tenants/users/get")
    assertEquals(op.get[String]("operationId"), Right("tenants.users.get"))
    assertEquals(op.get[List[Json]]("parameters").toOption.get.map(_.hcursor.get[String]("name").toOption.get), List("tenant", "section", "id"))
    app.httpApp(request("/tenants/users/get?tenant=acme&section=admin&id=7")).flatMap(_.as[String])
      .map(value => assertEquals(value, "acme:admin:7"))
  }

  test("inherited JSON bodies decode once for middleware and differently typed child handlers") {
    val decoded = new AtomicInteger()
    val seen = new AtomicInteger()
    val original = JsonModel[CreateUser]
    val counted = new JsonModel[CreateUser]:
      val typeId = original.typeId
      val schema = original.schema
      val encoder = original.encoder
      val decoder: Decoder[CreateUser] = Decoder.instance { cursor =>
        decoded.incrementAndGet()
        original.decoder(cursor)
      }
    val app = httpApp { commands("users") {
      val input = body[CreateUser](using counted, summon[CommandsScope])("User payload")
      middleware { next =>
        IO { assert(input.value.active); seen.incrementAndGet() } *> next
      }
      command[String]("name") { handle { IO.pure(input.value.name) } }
      commands("copy") {
        command[CreateUser]("read") { handle { IO.pure(input.value) } }
      }
    } }
    assert(operationDoc(app, "/users/copy/read", "post").downField("requestBody").succeeded)
    assert(operationDoc(app, "/users/copy/read", "post").downField("responses").downField("415").succeeded)
    def req(path: String) = request(path, Method.POST).withEntity("""{"name":"Ada"}""")
      .putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))
    for
      name <- app.httpApp(req("/users/name")).flatMap(_.as[String])
      copied <- app.httpApp(req("/users/copy/read")).flatMap(_.as[Json])
    yield
      assertEquals(name, "Ada")
      assertEquals(copied.hcursor.get[String]("name"), Right("Ada"))
      assertEquals(decoded.get(), 2)
      assertEquals(seen.get(), 2)
  }

  test("middleware nests root to operation, first declared outermost, including late declarations") {
    val events = ArrayBuffer.empty[String]
    def trace(name: String): Handler => Handler = next =>
      IO { events += s"+$name" } *> next.guarantee(IO { events += s"-$name" })
    val app = httpApp {
      commands("outer") {
        middleware(trace("first"))
        commands("inner") {
          command[String]("run") {
            handle { events += "handle"; IO.pure("ok") }
            middleware(trace("operation"))
          }
          middleware(trace("child"))
        }
        middleware(trace("second"))
      }
      middleware(trace("root"))
    }
    val pending = app.httpApp(request("/outer/inner/run"))
    assertEquals(events.toList, Nil)
    pending.flatMap(_.as[String]).map { result =>
      assertEquals(result, "ok")
      assertEquals(events.toList, List("+root", "+first", "+second", "+child", "+operation", "handle",
        "-operation", "-child", "-second", "-first", "-root"))
    }
  }

  test("group recovery handles synchronous, asynchronous, IO construction and encoding failures") {
    val app = httpApp { commands("recover") {
      val label = param[String]("label", "Label") { default("shared") }
      val rejected = response.text(422) { description("Recovered failure") }
      middleware { next => next.recover {
        case error: IllegalStateException => rejected(s"${label.value}:${error.getMessage}")
        case error: IllegalArgumentException => rejected(s"${label.value}:${error.getMessage}")
      } }
      command[String]("pure") { handle { throw new IllegalStateException("pure") } }
      command[Int]("effect") { handle { IO.raiseError[Int](new IllegalStateException("effect")) } }
      command[Unit]("construct") {
        def action: IO[Unit] = throw new IllegalStateException("construct")
        handle { action }
      }
      commands("nested") {
        command[Double]("encode") { handle { IO.pure(Double.PositiveInfinity) } }
        command[Int]("error-encode") {
          val invalid = response.json[Double](409) {}
          val failed = invalid.raise(Double.PositiveInfinity)
          handle { failed }
        }
      }
    } }
    List("pure", "effect", "construct", "nested/encode", "nested/error-encode").traverse_ { path =>
      assertEquals(operationDoc(app, s"/recover/$path").downField("responses").downField("422").get[String]("description"), Right("Recovered failure"))
      for
        response <- app.httpApp(request(s"/recover/$path"))
        body <- response.as[String]
      yield
        assertEquals(response.status, Status.UnprocessableContent)
        assert(body.startsWith("shared:"))
        if path.endsWith("encode") then assert(body.contains("finite")) else assertEquals(body, s"shared:$path")
    }
  }

  test("raised responses stop the IO chain, run finalizers and reach outer middleware as results") {
    val continued = new AtomicInteger()
    val finalized = new AtomicInteger()
    val recovered = new AtomicInteger()
    val app = httpApp { commands("errors") {
      val denied = response.text(403) {}
      val unexpected = response.text(502) {}
      val reject = denied.raise("denied")
      middleware { next => next.recover { case _ =>
        recovered.incrementAndGet()
        unexpected("unexpected")
      } }
      command[Int]("handler") {
        handle { (reject *> IO(continued.incrementAndGet())).guarantee(IO { finalized.incrementAndGet(); () }) }
      }
      commands("child") {
        middleware { _ => reject }
        command[Int]("middleware") { handle { IO(continued.incrementAndGet()) } }
      }
    } }
    assertEquals(continued.get(), 0)
    assertEquals(finalized.get(), 0)
    List("handler", "child/middleware").traverse_ { path =>
      for
        response <- app.httpApp(request(s"/errors/$path"))
        body <- response.as[String]
      yield
        assertEquals(response.status, Status.Forbidden)
        assertEquals(body, "denied")
    }.map { _ =>
      assertEquals(continued.get(), 0)
      assertEquals(finalized.get(), 1)
      assertEquals(recovered.get(), 0)
    }
  }

  test("inner recovery wins and errors thrown by middleware or recovery reach outer middleware") {
    val app = httpApp { commands("outer") {
      val outer = response.text(502) {}
      middleware { next => next.recover { case e: IllegalArgumentException => outer(e.getMessage) } }
      commands("inner") {
        val inner = response.text(409) {}
        middleware { next => next.recoverWith { case _: IllegalStateException => IO.pure(inner("inner")) } }
        command[String]("handled") { handle { IO.raiseError[String](new IllegalStateException("failure")) } }
        command[String]("rethrow") {
          middleware { next => next.recover { case _: IllegalStateException => throw new IllegalArgumentException("recovery") } }
          handle { throw new IllegalStateException("failure") }
        }
        command[String]("middleware") {
          middleware { _ => throw new IllegalArgumentException("middleware") }
          handle { IO.pure("unused") }
        }
      }
    } }
    for
      inner <- app.httpApp(request("/outer/inner/handled"))
      body <- inner.as[String]
      outer <- app.httpApp(request("/outer/inner/rethrow"))
      rethrown <- outer.as[String]
      failed <- app.httpApp(request("/outer/inner/middleware"))
      failure <- failed.as[String]
    yield
      assertEquals(inner.status, Status.Conflict)
      assertEquals(body, "inner")
      assertEquals(outer.status, Status.BadGateway)
      assertEquals(rethrown, "recovery")
      assertEquals(failed.status, Status.BadGateway)
      assertEquals(failure, "middleware")
  }

  test("late group contracts apply to earlier children and validation precedes short-circuit middleware") {
    val handled = new AtomicInteger()
    val wrapped = new AtomicInteger()
    val app = httpApp { commands("late") {
      command[Int]("count") { handle { IO.pure(handled.incrementAndGet()) } }
      commands("child") { command[Unit]("empty") { handle { handled.incrementAndGet(); IO.unit } } }
      val enabled = param[Boolean]("enabled", "Enabled")
      val denied = response.text(403) {}
      middleware { next =>
        wrapped.incrementAndGet()
        if enabled.value then next else IO.pure(denied("disabled"))
      }
    } }
    assertEquals(wrapped.get(), 0)
    val doc = operationDoc(app, "/late/child/empty")
    assertEquals(doc.get[List[Json]]("parameters").toOption.get.head.hcursor.get[String]("name"), Right("enabled"))
    assert(doc.downField("responses").downField("403").succeeded)
    for
      invalid <- app.httpApp(request("/late/count?enabled=wrong"))
      _ = assertEquals(wrapped.get(), 0)
      denied <- app.httpApp(request("/late/child/empty?enabled=false"))
      _ = assertEquals(handled.get(), 0)
      allowed <- app.httpApp(request("/late/count?enabled=true"))
      body <- allowed.as[Json]
    yield
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(denied.status, Status.Forbidden)
      assertEquals(allowed.status, Status.Ok)
      assertEquals(body, Json.fromInt(1))
      assertEquals(wrapped.get(), 2)
  }

  test("HTTP groups retain inherited locations, responses and middleware") {
    import sprout.httpDsl.*
    val app = httpApp { routes { group("/api/{id}") {
      val id = path[Int]("id")
      val token = header[String]("X-Token")
      val session = cookie[Option[String]]("session")
      val missing = response.json[sprout.ErrorBody](404) {}
      middleware { next =>
        if token.value == "secret" && session.value.contains("abc") then next
        else missing.raise(sprout.ErrorBody("not_found", "missing"))
      }
      group("/child") { get("/manual") {
        val ok = response.text(200) {}
        handle { IO.pure(if id.value == 1 then ok("manual") else missing(sprout.ErrorBody("not_found", "missing"))) }
      } }
    } } }
    def req(id: Int) = request(s"/api/$id/child/manual").putHeaders(
      Header.Raw(CIString("X-Token"), "secret"), Header.Raw(CIString("Cookie"), "session=abc"))
    for
      success <- app.httpApp(req(1)).flatMap(_.as[String])
      missing <- app.httpApp(req(2))
      invalid <- app.httpApp(request("/api/1/child/manual"))
      denied <- app.httpApp(request("/api/1/child/manual").putHeaders(Header.Raw(CIString("X-Token"), "wrong")))
    yield
      assertEquals(success, "manual")
      assertEquals(missing.status, Status.NotFound)
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(denied.status, Status.NotFound)
  }

  test("same-named groups keep independent contracts and reject foreign inherited handles") {
    var input: Option[Input[Int]] = None
    var output: Option[Output[String]] = None
    val app = httpApp {
      commands("users") {
        val n = param[Int]("n", "Number")
        val denied = response.text(403) {}
        input = Some(n)
        output = Some(denied)
        middleware { _ => IO.pure(denied(n.value.toString)) }
        command[Int]("first") { handle { IO.pure(n.value) } }
      }
      commands("users") {
        command[String]("second") { handle { IO.pure("independent") } }
        command[Int]("input") { handle { IO.pure(input.get.value) } }
        command[String]("output") { handle { output.get.raise("foreign") } }
      }
    }
    val doc = operationDoc(app, "/users/second")
    assert(doc.get[List[Json]]("parameters").toOption.forall(_.isEmpty))
    assert(!doc.downField("responses").downField("403").succeeded)
    for
      first <- app.httpApp(request("/users/first?n=7"))
      second <- app.httpApp(request("/users/second")).flatMap(_.as[String])
      badInput <- app.httpApp(request("/users/input"))
      badOutput <- app.httpApp(request("/users/output"))
    yield
      assertEquals(first.status, Status.Forbidden)
      assertEquals(second, "independent")
      assertEquals(badInput.status, Status.InternalServerError)
      assertEquals(badOutput.status, Status.InternalServerError)
  }

  test("inherited contracts reject duplicate parameters, bodies, statuses and HEAD bodies") {
    def invalid(init: CommandsScope ?=> Unit): Unit =
      intercept[IllegalArgumentException] { httpApp { init } }
    invalid {
      param[Int]("n", "Number")
      command[Int]("read") { param[Int]("n", "Duplicate"); handle { IO.pure(1) } }
    }
    invalid { commands("one") {
      param[String]("token", "Token")
      commands("two") { param[String]("token", "Duplicate"); command[Int]("read") { handle { IO.pure(1) } } }
    } }
    invalid {
      body[Int]("Input")
      command[Int]("read") { body[String]("Duplicate"); handle { IO.pure(1) } }
    }
    invalid {
      response.text(404) {}
      command[Int]("read") { response.json[Int](404) {}; handle { IO.pure(1) } }
    }
    invalid {
      response.text(200) {}
      command[Int]("read") { handle { IO.pure(1) } }
    }
    intercept[IllegalArgumentException] {
      import sprout.httpDsl.*
      httpApp { routes {
        response.text(404) {}
        head("/head") { val ok = response.empty(200) {}; handle { IO.pure(ok(())) } }
      } }
    }
  }

  test("group declarations and middleware retain compile-time parameter and response types") {
    val value = typeCheckErrors("""
      import sprout.rpcDsl.*
      httpApp { commands("users") { val n = param[Int]("n", "Number"); val invalid = n.value } }
    """)
    val defaultErrors = typeCheckErrors("""
      import sprout.rpcDsl.*
      httpApp { commands("users") { param[Int]("n", "Number") { default("wrong") } } }
    """)
    val responseErrors = typeCheckErrors("""
      import sprout.rpcDsl.*
      httpApp { commands("users") {
        val bad = response.json[Int](400) {}
        middleware { next => next.recover { case _: RuntimeException => bad("wrong") } }
      } }
    """)
    val middlewareResult = typeCheckErrors("""
      import cats.effect.IO
      import sprout.rpcDsl.*
      httpApp { middleware { _ => IO.pure("wrong") } }
    """)
    assert(value.exists(_.message.contains("RequestContext")))
    assert(defaultErrors.nonEmpty)
    assert(responseErrors.nonEmpty)
    assert(middlewareResult.nonEmpty)
  }
