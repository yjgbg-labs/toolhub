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
import sprout.{Application, RoutesScope}
import sprout.dsl.*

class GroupMiddlewareSuite extends CatsEffectSuite:
  private def request(path: String, method: Method = Method.POST): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))

  private def operationDoc(app: Application, path: String) =
    io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths").downField(path).downField("post")

  test("nested group parameters inherit locations, defaults, constraints and request-local values") {
    val app = httpApp { routes { group("/api/{tenant}") {
      val tenant = path[String]("tenant") { minLength(1) }
      group("users") {
        val limit = query[Int]("limit") { default(20); minimum(1) }
        val token = header[String]("X-Token") { description("Shared token") }
        val session = cookie[Option[String]]("session")
        operation[List[String]]("get") {
          val id = path[Long]("id")
          handle { IO.cede.as(ok(List(tenant.value, id.value.toString, limit.value.toString, token.value, session.value.getOrElse("none")))) }
        }
        group("admin") {
          operation[String]("read") { handle { IO.cede *> IO(ok(s"${tenant.value}:${token.value}:${limit.value}")) } }
        }
      }
    } } }
    val parameters = operationDoc(app, "/api/{tenant}/users/get/{id}").get[List[Json]]("parameters").toOption.get
    assertEquals(parameters.map(p => p.hcursor.get[String]("name").toOption.get), List("tenant", "limit", "X-Token", "session", "id"))
    assertEquals(parameters.map(p => p.hcursor.get[String]("in").toOption.get), List("path", "query", "header", "cookie", "path"))
    assertEquals(parameters(1).hcursor.downField("schema").get[Int]("default"), Right(20))
    assertEquals(parameters(1).hcursor.downField("schema").get[Int]("minimum"), Right(1))
    assertEquals(parameters(2).hcursor.get[String]("description"), Right("Shared token"))
    for
      first <- app.httpApp(request("/api/acme/users/get/7").putHeaders(
        Header.Raw(CIString("X-Token"), "secret"), Header.Raw(CIString("Cookie"), "session=abc"))).flatMap(_.as[Json])
      _ = assertEquals(first, Json.fromValues(List("acme", "7", "20", "secret", "abc").map(Json.fromString)))
      _ <- (1 to 50).toList.parTraverse_ { n =>
        app.httpApp(request(s"/api/t$n/users/admin/read?limit=$n").putHeaders(Header.Raw(CIString("X-Token"), s"key$n")))
          .flatMap(_.as[String]).map(value => assertEquals(value, s"t$n:key$n:$n"))
      }
      invalid <- app.httpApp(request("/api/acme/users/get/7?limit=0").putHeaders(Header.Raw(CIString("X-Token"), "secret")))
    yield assertEquals(invalid.status, Status.BadRequest)
  }

  test("inherited path parameters extend generated operation URLs in ancestor declaration order") {
    val app = httpApp { routes { group("tenants") {
      val tenant = path[String]("tenant")
      group("users") {
        val section = path[String]("section")
        operation[String]("get") {
          val id = path[Int]("id")
          handle { IO.pure(ok(s"${tenant.value}:${section.value}:${id.value}")) }
        }
      }
    } } }
    assertEquals(operationDoc(app, "/tenants/users/get/{tenant}/{section}/{id}").get[String]("operationId"), Right("tenants.users.get"))
    app.httpApp(request("/tenants/users/get/acme/admin/7")).flatMap(_.as[String])
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
    val app = httpApp { routes { group("users") {
      val input = body.json[CreateUser](using counted, summon[RoutesScope])
      middleware { next =>
        IO { assert(input.value.active); seen.incrementAndGet() } *> next
      }
      operation[String]("name") { handle { IO.pure(ok(input.value.name)) } }
      group("copy") {
        operation[CreateUser]("read") { handle { IO.pure(ok(input.value)) } }
      }
    } } }
    assert(operationDoc(app, "/users/copy/read").downField("requestBody").succeeded)
    assert(operationDoc(app, "/users/copy/read").downField("responses").downField("415").succeeded)
    def req(path: String) = request(path).withEntity("""{"name":"Ada"}""")
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
    val app = httpApp { routes {
      group("outer") {
        middleware(trace("first"))
        group("inner") {
          operation[String]("run") {
            handle { events += "handle"; IO.pure(ok("ok")) }
            middleware(trace("operation"))
          }
          middleware(trace("child"))
        }
        middleware(trace("second"))
      }
      middleware(trace("root"))
    } }
    val pending = app.httpApp(request("/outer/inner/run"))
    assertEquals(events.toList, Nil)
    pending.flatMap(_.as[String]).map { result =>
      assertEquals(result, "ok")
      assertEquals(events.toList, List("+root", "+first", "+second", "+child", "+operation", "handle",
        "-operation", "-child", "-second", "-first", "-root"))
    }
  }

  test("group recovery handles synchronous, asynchronous, IO construction and encoding failures") {
    val app = httpApp { routes { group("recover") {
      val label = header[String]("X-Label") { default("shared") }
      val rejected = response.text(422) { description("Recovered failure") }
      middleware { next => next.recover {
        case error: IllegalStateException => rejected(s"${label.value}:${error.getMessage}")
        case error: IllegalArgumentException => rejected(s"${label.value}:${error.getMessage}")
      } }
      operation[String]("pure") { handle { throw new IllegalStateException("pure") } }
      operation[Int]("effect") { handle { IO.raiseError[EndpointResult](new IllegalStateException("effect")) } }
      operation[Unit]("construct") {
        def action: IO[Unit] = throw new IllegalStateException("construct")
        handle { action.map(ok(_)) }
      }
      group("nested") {
        operation[Double]("encode") { handle { IO.pure(ok(Double.PositiveInfinity)) } }
      }
    } } }
    List("pure", "effect", "construct", "nested/encode").traverse_ { path =>
      assertEquals(operationDoc(app, s"/recover/$path").downField("responses").downField("422").get[String]("description"), Right("Recovered failure"))
      for
        response <- app.httpApp(request(s"/recover/$path"))
        body <- response.as[String]
      yield
        assertEquals(response.status, Status.UnprocessableContent)
        assert(body.startsWith("shared:"))
        if path == "nested/encode" then assert(body.contains("finite")) else assertEquals(body, s"shared:$path")
    }
  }

  test("inner recovery wins and errors thrown by middleware or recovery reach outer middleware") {
    val app = httpApp { routes { group("outer") {
      val outer = response.text(502) {}
      middleware { next => next.recover { case e: IllegalArgumentException => outer(e.getMessage) } }
      group("inner") {
        val inner = response.text(409) {}
        middleware { next => next.recoverWith { case _: IllegalStateException => IO.pure(inner("inner")) } }
        operation[String]("handled") { handle { IO.raiseError[EndpointResult](new IllegalStateException("failure")) } }
        operation[String]("rethrow") {
          middleware { next => next.recover { case _: IllegalStateException => throw new IllegalArgumentException("recovery") } }
          handle { throw new IllegalStateException("failure") }
        }
        operation[String]("middleware") {
          middleware { _ => throw new IllegalArgumentException("middleware") }
          handle { IO.pure(ok("unused")) }
        }
      }
    } } }
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
    val app = httpApp { routes { group("late") {
      operation[Int]("count") { handle { IO.pure(ok(handled.incrementAndGet())) } }
      group("child") { operation[Unit]("empty") { handle { handled.incrementAndGet(); IO.pure(ok(())) } } }
      val enabled = query[Boolean]("enabled")
      val denied = response.text(403) {}
      middleware { next =>
        wrapped.incrementAndGet()
        if enabled.value then next else IO.pure(denied("disabled"))
      }
    } } }
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

  test("inherited explicit responses work directly in manual routes and typed operations") {
    val app = httpApp { routes { group("/api/{id}") {
      val id = path[Int]("id")
      val missing = response.json[sprout.ErrorBody](404) {}
      get("/manual") {
        val ok = response.text(200) {}
        handle { IO.pure(if id.value == 1 then ok("manual") else missing(sprout.ErrorBody("not_found", "missing"))) }
      }
      operation[String]("typed") {
        handle { IO.pure(if id.value == 1 then ok("typed") else missing(sprout.ErrorBody("not_found", "missing"))) }
      }
    } } }
    List(request("/api/2/manual", Method.GET), request("/api/2/typed")).traverse_ { req =>
      for
        response <- app.httpApp(req)
        body <- response.as[Json]
      yield
        assertEquals(response.status, Status.NotFound)
        assertEquals(body.hcursor.get[String]("code"), Right("not_found"))
    }
  }

  test("same-named groups keep independent contracts and reject foreign inherited handles") {
    var input: Option[Input[Int]] = None
    var output: Option[Output[String]] = None
    val app = httpApp { routes {
      group("users") {
        val n = query[Int]("n")
        val denied = response.text(403) {}
        input = Some(n)
        output = Some(denied)
        middleware { _ => IO.pure(denied(n.value.toString)) }
        operation[Int]("first") { handle { IO.pure(ok(n.value)) } }
      }
      group("users") {
        operation[String]("second") { handle { IO.pure(ok("independent")) } }
        operation[Int]("input") { handle { IO.pure(ok(input.get.value)) } }
        operation[String]("output") { handle { IO.pure(output.get("foreign")) } }
      }
    } }
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
    def invalid(init: RoutesScope ?=> Unit): Unit =
      intercept[IllegalArgumentException] { httpApp { routes(init) } }
    invalid {
      query[Int]("n")
      operation[Int]("read") { query[Int]("n"); handle { IO.pure(ok(1)) } }
    }
    invalid { group("one") {
      header[String]("X-Token")
      group("two") { header[String]("x-token"); operation[Int]("read") { handle { IO.pure(ok(1)) } } }
    } }
    invalid {
      body.json[Int]
      operation[Int]("read") { body.json[String]; handle { IO.pure(ok(1)) } }
    }
    invalid {
      response.text(404) {}
      operation[Int]("read") { response.json[Int](404) {}; handle { IO.pure(ok(1)) } }
    }
    invalid {
      response.text(200) {}
      operation[Int]("read") { handle { IO.pure(ok(1)) } }
    }
    invalid {
      response.text(404) {}
      head("/head") { val ok = response.empty(200) {}; handle { IO.pure(ok(())) } }
    }
  }

  test("group declarations and middleware retain compile-time parameter and response types") {
    val value = typeCheckErrors("""
      import sprout.dsl.*
      httpApp { routes { group("users") { val n = query[Int]("n"); val invalid = n.value } } }
    """)
    val defaultErrors = typeCheckErrors("""
      import sprout.dsl.*
      httpApp { routes { group("users") { query[Int]("n") { default("wrong") } } } }
    """)
    val responseErrors = typeCheckErrors("""
      import sprout.dsl.*
      httpApp { routes { group("users") {
        val bad = response.json[Int](400) {}
        middleware { next => next.recover { case _: RuntimeException => bad("wrong") } }
      } } }
    """)
    val middlewareResult = typeCheckErrors("""
      import cats.effect.IO
      import sprout.dsl.*
      httpApp { routes { middleware { _ => IO.pure("wrong") } } }
    """)
    assert(value.exists(_.message.contains("RequestContext")))
    assert(defaultErrors.nonEmpty)
    assert(responseErrors.nonEmpty)
    assert(middlewareResult.nonEmpty)
  }
