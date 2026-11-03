package frameworktests

import cats.effect.IO
import cats.syntax.all.*
import framework.http.CorsScope
import framework.httpDsl.*
import framework.runtime.Application
import io.circe.Json
import java.util.concurrent.atomic.AtomicInteger
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Response, Status, Uri}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

class CorsSuite extends CatsEffectSuite:
  private val origin = "http://htpc.local:5173"
  private def raw(name: String, value: String): Header.Raw = Header.Raw(CIString(name), value)
  private def headerValue(response: Response[IO], name: String): Option[String] =
    response.headers.get(CIString(name)).map(_.head.value)
  private def headerSet(response: Response[IO], name: String): Set[String] =
    headerValue(response, name).toSet.flatMap(_.split(",").map(_.trim.toLowerCase(java.util.Locale.ROOT)))
  private def request(path: String, method: Method = Method.GET, from: String = origin): Request[IO] =
    Request[IO](method = method, uri = Uri.unsafeFromString(path)).putHeaders(raw("Origin", from))
  private def preflight(path: String, method: String = "POST", headers: String = "content-type, authorization"): Request[IO] =
    request(path, Method.OPTIONS).putHeaders(
      raw("Access-Control-Request-Method", method), raw("Access-Control-Request-Headers", headers))
  private def jsonRequest(payload: String): Request[IO] =
    request("/echo", Method.POST).withEntity(payload).putHeaders(raw("Content-Type", "application/json"))

  private def application(config: CorsScope ?=> Unit, calls: AtomicInteger = new AtomicInteger): Application = httpApp {
    server { maxBodyBytes(64); cors { config } }
    routes {
      get("/ok") {
        val ok = response.text(200) {}
        handle { IO.pure(ok("ok")) }
      }
      post("/echo") {
        val input = body.json[Json]
        val ok = response.json[Json](200) {}
        handle { calls.incrementAndGet(); IO.pure(ok(input.value)) }
      }
      get("/fail") {
        val ok = response.empty(204) {}
        handle { IO.raiseError(new IllegalArgumentException("cors-test failure")) }
      }
      options("/manual") {
        val done = response.empty(204) {}
        handle { calls.incrementAndGet(); IO.pure(done(())) }
      }
    }
  }

  test("CORS is opt-in and ordinary OPTIONS retains its route semantics") {
    val disabled = httpApp {
      routes { options("/manual") {
        val done = response.empty(204) {}
        handle { IO.pure(done(())) }
      } }
    }
    val calls = new AtomicInteger
    val enabled = application({}, calls)
    for
      off <- disabled.httpApp(preflight("/manual"))
      ordinary <- enabled.httpApp(request("/manual", Method.OPTIONS))
      withoutOrigin <- enabled.httpApp(Request[IO](method = Method.OPTIONS, uri = Uri.unsafeFromString("/manual")))
      native <- enabled.httpApp(Request[IO](uri = Uri.unsafeFromString("/ok")))
      nativeBody <- native.as[String]
    yield
      assertEquals(off.status, Status.NoContent)
      assertEquals(headerValue(off, "Access-Control-Allow-Origin"), None)
      assertEquals(ordinary.status, Status.NoContent)
      assertEquals(headerValue(ordinary, "Access-Control-Allow-Origin"), Some("*"))
      assertEquals(withoutOrigin.status, Status.NoContent)
      assertEquals(calls.get(), 2)
      assertEquals(nativeBody, "ok")
      assertEquals(headerValue(native, "Access-Control-Allow-Origin"), None)
  }

  test("JSON POST preflight reflects requested headers without reading the body or invoking handlers") {
    val calls = new AtomicInteger
    val app = application({ allowOrigins("*"); allowHeaders("*"); maxAge(10.minutes) }, calls)
    val req = preflight("/echo").withBodyStream(fs2.Stream.raiseError[IO](new RuntimeException("preflight body was read")))
    for
      result <- app.httpApp(req)
      empty <- result.as[String]
      _ = assertEquals(calls.get(), 0)
      actual <- app.httpApp(jsonRequest("{\"action\":\"play\"}"))
      body <- actual.as[String]
    yield
      assertEquals(result.status, Status.Ok)
      assertEquals(empty, "")
      assertEquals(headerValue(result, "Access-Control-Allow-Origin"), Some("*"))
      assert(headerSet(result, "Access-Control-Allow-Methods").contains("post"))
      assertEquals(headerSet(result, "Access-Control-Allow-Headers"), Set("content-type", "authorization"))
      assert(headerSet(result, "Vary").contains("access-control-request-headers"))
      assertEquals(headerValue(result, "Access-Control-Max-Age"), Some("600"))
      assertEquals(headerValue(result, "Access-Control-Allow-Credentials"), None)
      assertEquals(actual.status, Status.Ok)
      assertEquals(headerValue(actual, "Access-Control-Allow-Origin"), Some("*"))
      assertEquals(body, "{\"action\":\"play\"}")
      assertEquals(calls.get(), 1)
  }

  test("global CORS covers framework errors and cached OpenAPI GET and HEAD responses") {
    val app = application { exposeHeaders("ETag", "Allow") }
    val cases = List(
      request("/missing") -> Status.NotFound,
      request("/ok", Method.POST) -> Status.MethodNotAllowed,
      jsonRequest("{") -> Status.BadRequest,
      jsonRequest("x" * 65) -> Status.PayloadTooLarge,
      request("/echo", Method.POST).withEntity("{}") -> Status.UnsupportedMediaType,
      request("/fail") -> Status.InternalServerError
    )
    ExpectedRequestErrors("cors-test failure") {
      for
        _ <- cases.traverse_ { (req, status) => app.httpApp(req).map { result =>
          assertEquals(result.status, status)
          assertEquals(headerValue(result, "Access-Control-Allow-Origin"), Some("*"))
        } }
        docs <- app.httpApp(request("/openapi.json"))
        tag = headerValue(docs, "ETag").get
        cached <- app.httpApp(request("/openapi.json").putHeaders(raw("If-None-Match", tag)))
        head <- app.httpApp(request("/openapi.json", Method.HEAD))
        headBody <- head.as[String]
      yield
        assertEquals(docs.status, Status.Ok)
        assertEquals(cached.status, Status.NotModified)
        assertEquals(head.status, Status.Ok)
        assertEquals(headBody, "")
        List(docs, cached, head).foreach { result =>
          assertEquals(headerValue(result, "Access-Control-Allow-Origin"), Some("*"))
          assertEquals(headerSet(result, "Access-Control-Expose-Headers"), Set("etag", "allow"))
        }
    }
  }

  test("credentialed allowlists match complete origins and vary cached responses") {
    val app = application {
      allowOrigins(origin, "https://htpc.example")
      allowMethods("GET", "POST")
      allowHeaders("Content-Type", "Authorization")
      exposeHeaders("ETag")
      allowCredentials(true)
    }
    for
      allowed <- app.httpApp(preflight("/echo", headers = "CONTENT-TYPE, authorization"))
      actual <- app.httpApp(request("/ok", from = "https://htpc.example"))
      _ <- List("http://htpc.local:5174", "https://htpc.example.evil", "null").traverse_ { from =>
        app.httpApp(request("/ok", from = from)).map { result =>
          assertEquals(headerValue(result, "Access-Control-Allow-Origin"), None)
          assertEquals(headerValue(result, "Access-Control-Allow-Credentials"), None)
          assert(headerSet(result, "Vary").contains("origin"))
        }
      }
      native <- app.httpApp(Request[IO](uri = Uri.unsafeFromString("/ok")))
    yield
      assertEquals(headerValue(allowed, "Access-Control-Allow-Origin"), Some(origin))
      assertEquals(headerValue(allowed, "Access-Control-Allow-Credentials"), Some("true"))
      assertEquals(headerValue(actual, "Access-Control-Allow-Origin"), Some("https://htpc.example"))
      assertEquals(headerValue(actual, "Access-Control-Allow-Credentials"), Some("true"))
      List(allowed, actual, native).foreach(result => assert(headerSet(result, "Vary").contains("origin")))
  }

  test("disallowed origins, methods and headers do not receive preflight permission") {
    val calls = new AtomicInteger
    val app = application({
      allowOrigins(origin)
      allowMethods("POST")
      allowHeaders("Content-Type")
    }, calls)
    List(
      preflight("/echo", headers = "content-type").putHeaders(raw("Origin", "https://other.example")),
      preflight("/echo", method = "DELETE", headers = "content-type"),
      preflight("/echo", headers = "content-type, x-forbidden")
    ).traverse_ { req => app.httpApp(req).map { result =>
      assertEquals(headerValue(result, "Access-Control-Allow-Origin"), None)
      assertEquals(calls.get(), 0)
    } }
  }

  test("wildcard headers support credentials and explicit null origins are supported") {
    val app = application { allowOrigins("null"); allowCredentials(true); allowHeaders("*") }
    app.httpApp(preflight("/echo").putHeaders(raw("Origin", "null"))).map { result =>
      assertEquals(headerValue(result, "Access-Control-Allow-Origin"), Some("null"))
      assertEquals(headerValue(result, "Access-Control-Allow-Credentials"), Some("true"))
      assertEquals(headerSet(result, "Access-Control-Allow-Headers"), Set("content-type", "authorization"))
    }
  }

  test("CORS configuration is validated at construction and frozen with the application") {
    val invalid: List[CorsScope ?=> Unit] = List(
      allowOrigins("https://htpc.example/path"),
      allowOrigins("https://user@htpc.example"),
      allowOrigins("https://htpc.example?token=1"),
      allowOrigins("https://htpc.example#fragment"),
      allowOrigins("*.example"),
      allowOrigins("*", origin),
      allowHeaders("Content-Type\r\nX-Injected: value"),
      allowMethods("BAD METHOD"),
      maxAge((-1).second),
      allowCredentials(true),
      { allowOrigins(origin); allowCredentials(true); allowMethods("*") },
      { allowOrigins(origin); allowCredentials(true); exposeHeaders("*") }
    )
    invalid.foreach { config => intercept[IllegalArgumentException] { application(config) } }
    intercept[IllegalArgumentException] { httpApp { server { cors {}; cors {} } } }
    var mutate: () => Unit = () => ()
    application { mutate = () => allowOrigins("https://other.example") }
    interceptMessage[IllegalArgumentException]("requirement failed: Application configuration is frozen") { mutate() }
  }

  test("RPC exports CORS and applies it to lazy SSE responses") {
    import framework.rpcDsl.*
    val consumed = new AtomicInteger
    val app = httpApp {
      server { cors { allowOrigins("*"); allowMethods("GET"); exposeHeaders("*"); maxAge(0.seconds) } }
      command[SSE[PlainText]]("events") {
        handle { IO.pure(SSE(fs2.Stream.eval(IO { consumed.incrementAndGet(); PlainText("ready") }))) }
      }
    }
    for
      result <- app.httpApp(request("/events"))
      _ = assertEquals(consumed.get(), 0)
      body <- result.as[String]
    yield
      assertEquals(headerValue(result, "Access-Control-Allow-Origin"), Some("*"))
      assertEquals(headerValue(result, "Access-Control-Expose-Headers"), Some("*"))
      assertEquals(headerValue(result, "Content-Type"), Some("text/event-stream"))
      assertEquals(body, "data: ready\n\n")
      assertEquals(consumed.get(), 1)
  }

  test("Ember serves the same global policy for preflight and actual requests") {
    val app = httpApp {
      server { port(0); cors { allowOrigins(origin); allowMethods("GET") } }
      routes { get("/health") {
        val ok = response.text(200) {}
        handle { IO.pure(ok("healthy")) }
      } }
    }
    (app.resource, EmberClientBuilder.default[IO].build).tupled.use { (server, client) =>
      val url = s"http://127.0.0.1:${server.address.getPort}/health"
      client.run(preflight(url, method = "GET")).use { response => IO {
        assertEquals(response.status, Status.Ok)
        assertEquals(headerValue(response, "Access-Control-Allow-Origin"), Some(origin))
      } } *> client.run(request(url)).use { response => response.as[String].map { body =>
        assertEquals(headerValue(response, "Access-Control-Allow-Origin"), Some(origin))
        assertEquals(body, "healthy")
      } }
    }
  }
