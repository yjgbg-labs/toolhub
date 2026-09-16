package sprouttests

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Response, Status, Uri}
import org.http4s.circe.*
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import sprout.{Application, ErrorBody, JsonModel, Schema}
import sprout.dsl.*
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class HttpSuite extends CatsEffectSuite:
  private def request(path: String, method: Method = Method.GET): Request[IO] =
    Request[IO](method = method, uri = Uri.unsafeFromString(path))

  private def jsonRequest(path: String, payload: String): Request[IO] =
    request(path, Method.POST).withEntity(payload).putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))

  test("handler is deferred; defaults and constraints apply before execution") {
    val calls = new AtomicInteger()
    val app = httpApp {
      routes {
        get("/users/{id}") {
          val id = path[Long]("id") { minimum(1L) }
          val limit = query[Int]("limit") { default(20); minimum(1); maximum(100) }
          val ok = response.json[List[Long]](200) {}
          handle {
            calls.incrementAndGet()
            ok(List(id.value, limit.value.toLong))
          }
        }
      }
    }
    assertEquals(calls.get(), 0)
    for
      success <- app.httpApp(request("/users/7"))
      json <- success.as[Json]
      invalid <- app.httpApp(request("/users/0?limit=101"))
      errors <- invalid.as[Json]
      malformed <- app.httpApp(request("/users/nope"))
      duplicate <- app.httpApp(request("/users/7?limit=1&limit=2"))
    yield
      assertEquals(success.status, Status.Ok)
      assertEquals(json, Json.arr(Json.fromInt(7), Json.fromInt(20)))
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(errors.hcursor.downField("errors").as[List[Json]].map(_.size), Right(2))
      assertEquals(malformed.status, Status.BadRequest)
      assertEquals(duplicate.status, Status.BadRequest)
      assertEquals(calls.get(), 1)
  }

  test("asynchronous concurrent handlers retain their own lexical request context") {
    val app = httpApp {
      routes {
        get("/{id}") {
          val id = path[Int]("id")
          val ok = response.json[Int](200) {}
          handle { IO.sleep(5.millis) *> IO.cede *> IO(ok(id.value)) }
        }
      }
    }
    (1 to 100).toList.parTraverse { id =>
      app.httpApp(request(s"/$id")).flatMap(_.as[Json]).map(json => assertEquals(json, Json.fromInt(id)))
    }
  }

  test("optional and repeated query parameters, headers and cookies are typed") {
    val app = httpApp {
      routes {
        get("/") {
          val optional = query[Option[Int]]("offset")
          val repeated = query[List[String]]("tag")
          val count = header[Int]("X-Count")
          val session = cookie[String]("session")
          val ok = response.json[List[String]](200) {}
          handle { ok(List(optional.value.toString, repeated.value.mkString(","), count.value.toString, session.value)) }
        }
      }
    }
    val req = request("/?tag=a&tag=b").putHeaders(
      Header.Raw(CIString("x-count"), "3"), Header.Raw(CIString("Cookie"), "session=abc")
    )
    app.httpApp(req).flatMap(_.as[Json]).map { json =>
      assertEquals(json, Json.fromValues(List("None", "a,b", "3", "abc").map(Json.fromString)))
    }
  }

  test("JSON request bodies decode once and apply model defaults") {
    val count = new AtomicInteger()
    val original = JsonModel[CreateUser]
    val counted = new JsonModel[CreateUser]:
      val typeId = original.typeId
      val schema = original.schema
      val encoder = original.encoder
      val decoder: Decoder[CreateUser] = Decoder.instance { cursor =>
        count.incrementAndGet()
        original.decoder(cursor)
      }
    val app = httpApp {
      routes {
        post("/users") {
          val input = body.json[CreateUser](using counted, summon[sprout.EndpointScope])
          val created = response.json[User](201) {}
          handle { created(User(1L, input.value.name, input.value.nickname)) }
        }
      }
    }
    for
      result <- app.httpApp(jsonRequest("/users", """{"name":"Ada"}"""))
      payload <- result.as[Json]
    yield
      assertEquals(result.status, Status.Created)
      assertEquals(payload.hcursor.get[String]("name"), Right("Ada"))
      assertEquals(count.get(), 1)
  }

  test("malformed, missing, oversized and unsupported bodies have declared errors") {
    val app = httpApp {
      server { maxBodyBytes(64) }
      routes {
        post("/") {
          val input = body.json[CreateUser]
          val ok = response.json[CreateUser](200) {}
          handle { ok(input.value) }
        }
      }
    }
    for
      malformed <- app.httpApp(jsonRequest("/", "{"))
      missing <- app.httpApp(request("/", Method.POST))
      invalid <- app.httpApp(jsonRequest("/", "{}"))
      unsupported <- app.httpApp(request("/", Method.POST).withEntity("{}"))
      oversized <- app.httpApp(jsonRequest("/", "x" * 65))
      invalidUtf8 <- app.httpApp(request("/", Method.POST)
        .withBodyStream(fs2.Stream.emits(Array[Byte](0xc3.toByte, 0x28)).covary[IO])
        .putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
    yield
      assertEquals(malformed.status, Status.BadRequest)
      assertEquals(missing.status, Status.BadRequest)
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(unsupported.status, Status.UnsupportedMediaType)
      assertEquals(oversized.status, Status.PayloadTooLarge)
      assertEquals(invalidUtf8.status, Status.BadRequest)
  }

  test("literal route precedence, method negotiation, trailing slashes and escaped segments") {
    val app = httpApp {
      routes {
        get("/users/{name}") {
          val name = path[String]("name")
          val ok = response.text(200) {}
          handle { ok(name.value) }
        }
        post("/users/me") {
          val done = response.empty(204) {}
          handle { done(()) }
        }
      }
    }
    for
      method <- app.httpApp(request("/users/me"))
      escaped <- app.httpApp(request("/users/a%2Fb+z/"))
      text <- escaped.as[String]
      missing <- app.httpApp(request("/missing"))
      empty <- app.httpApp(request("/users/me", Method.POST))
      emptyBody <- empty.as[String]
    yield
      assertEquals(method.status, Status.MethodNotAllowed)
      assertEquals(method.headers.get(CIString("Allow")).map(_.head.value), Some("POST"))
      assertEquals(text, "a/b+z")
      assertEquals(missing.status, Status.NotFound)
      assertEquals(empty.status, Status.NoContent)
      assertEquals(emptyBody, "")
  }

  test("OpenAPI is served as cached JSON with conditional GET and HEAD") {
    val app = httpApp { routes { get("/") { val ok = response.text(200) {}; handle { ok("ok") } } } }
    for
      first <- app.httpApp(request("/openapi.json"))
      doc <- first.as[Json]
      tag = first.headers.get(CIString("ETag")).get.head.value
      cached <- app.httpApp(request("/openapi.json").putHeaders(Header.Raw(CIString("If-None-Match"), s"W/$tag")))
      head <- app.httpApp(request("/openapi.json", Method.HEAD))
      headBody <- head.as[String]
    yield
      assertEquals(first.status, Status.Ok)
      assertEquals(doc.hcursor.get[String]("openapi"), Right("3.1.2"))
      assertEquals(cached.status, Status.NotModified)
      assertEquals(head.status, Status.Ok)
      assertEquals(headBody, "")
  }

  test("cross-endpoint input and output handles are rejected") {
    var foreignInput: Option[Input[Int]] = None
    var foreignOutput: Option[Output[Int]] = None
    val app = httpApp {
      routes {
        get("/first/{id}") {
          val id = path[Int]("id")
          val ok = response.json[Int](200) {}
          foreignInput = Some(id)
          foreignOutput = Some(ok)
          handle { ok(id.value) }
        }
        get("/bad-input") {
          val ok = response.json[Int](200) {}
          handle { ok(foreignInput.get.value) }
        }
        get("/bad-output") {
          val ok = response.json[Int](200) {}
          handle { foreignOutput.get(1) }
        }
      }
    }
    for
      badInput <- app.httpApp(request("/bad-input"))
      badOutput <- app.httpApp(request("/bad-output"))
      json <- badInput.as[Json]
    yield
      assertEquals(badInput.status, Status.InternalServerError)
      assertEquals(badOutput.status, Status.InternalServerError)
      assertEquals(json.hcursor.get[String]("message"), Right("Internal server error"))
  }

  test("configuration cannot be mutated from a handler after freeze") {
    val app = httpApp {
      routes {
        get("/") {
          val ok = response.text(200) {}
          handle { summary("too late"); ok("ok") }
        }
      }
    }
    app.httpApp(request("/")).map(result => assertEquals(result.status, Status.InternalServerError))
  }

  test("Ember serves the application over a real ephemeral TCP port") {
    val app = httpApp {
      server { host("127.0.0.1"); port(0) }
      routes {
        get("/health") {
          val ok = response.text(200) {}
          handle { ok("healthy") }
        }
      }
    }
    (app.resource, EmberClientBuilder.default[IO].build).tupled.use { (server, client) =>
      val uri = Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}/health")
      client.expect[String](uri).map(value => assertEquals(value, "healthy"))
    }
  }
