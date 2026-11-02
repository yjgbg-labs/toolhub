package frameworktests

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import framework.Client
import framework.http.{InputLocation, ResponseBody}
import framework.httpDsl.*
import framework.model.SchemaNode
import framework.runtime.Endpoint
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.typelevel.ci.CIString

class EndpointSuite extends CatsEffectSuite:
  private def parity(endpoint: Endpoint, http: Request[IO], native: Endpoint.Request, status: Int): IO[Unit] = for
    external <- endpoint.httpApp(http)
    internal <- endpoint.invoke(native)
    bytes <- external.body.compile.toVector
    nativeBytes <- internal.body match
      case Some(ResponseBody.Json(json)) => IO.pure(json.noSpaces.getBytes(UTF_8).toVector)
      case Some(ResponseBody.Text(text)) => IO.pure(text.getBytes(UTF_8).toVector)
      case Some(ResponseBody.Streaming(stream)) => stream.compile.toVector
      case None => IO.pure(Vector.empty[Byte])
  yield
    assertEquals(external.status.code, status)
    assertEquals(internal.status, status)
    assertEquals(nativeBytes, bytes)
    assertEquals(internal.headers, external.headers.headers.map(h => h.name.toString.toLowerCase(java.util.Locale.ROOT) -> h.value).toMap)

  test("public endpoints expose declarations and bound HTTP entry points without SDK naming or size policies") {
    val suffix = "x" * (1024 * 1024 + 1)
    val app = httpApp { routes { get("/values/{id}") {
      operationId("values.read")
      val pathId = path[String]("id")
      val queryId = query[String]("id")
      val ok = response.text(200) {}
      handle { IO.pure(ok(s"${pathId.value}:${queryId.value}:$suffix")) }
    } } }
    val endpoint = app.endpoints.head
    assertEquals(endpoint.operationId, "values.read")
    assertEquals((endpoint.method, endpoint.path), ("GET", "/values/{id}"))
    assertEquals(endpoint.exposedOn, Client.values.toSet)
    assertEquals(endpoint.inputs.map(input => (input.name, input.location, input.schema, input.bodyMediaType)), Vector(
      ("id", InputLocation.Path, SchemaNode.Primitive("string"), None),
      ("id", InputLocation.Query, SchemaNode.Primitive("string"), None)))
    for
      response <- endpoint.httpApp(Request[IO](uri = Uri.unsafeFromString("/values/path?id=query")))
      text <- response.as[String]
      missing <- endpoint.httpApp(Request[IO](uri = Uri.unsafeFromString("/values/path")))
      method <- endpoint.httpApp(Request[IO](method = Method.POST, uri = Uri.unsafeFromString("/values/path?id=query")))
      docs <- app.httpApp(Request[IO](uri = Uri.unsafeFromString("/openapi.json")))
      wrongPath <- endpoint.httpApp(Request[IO](uri = Uri.unsafeFromString("/openapi.json")))
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(text, s"path:query:$suffix")
      assertEquals(missing.status, Status.BadRequest)
      assertEquals(method.status, Status.MethodNotAllowed)
      assertEquals(docs.status, Status.Ok)
      assertEquals(wrongPath.status, Status.NotFound)
  }

  test("structured invocation shares location-specific decoding, defaults, constraints, middleware and recovery") {
    Ref.of[IO, Int](0).flatMap { calls =>
      val app = httpApp { routes {
        middleware(next => calls.update(_ + 1) *> next)
        post("/values/{id}") {
          val id = path[String]("id")
          val queryId = query[List[String]]("id")
          val count = query[Int]("count") { default(2); minimum(1) }
          val token = header[String]("X-Token")
          val session = cookie[String]("session")
          val payload = body.json[Json]
          val ok = response.json[Json](200) {}
          val conflict = response.text(409) {}
          middleware { next => next.recover { case _: IllegalArgumentException => conflict("recovered 中文🙂") } }
          handle {
            if count.value == 3 then IO.raiseError(new IllegalArgumentException("conflict"))
            else if count.value == 4 then IO.raiseError(new IllegalStateException("failure"))
            else IO.pure(ok(Json.arr(Json.fromString(id.value), Json.fromValues(queryId.value.map(Json.fromString)),
              Json.fromInt(count.value), Json.fromString(token.value), Json.fromString(session.value), payload.value)))
          }
        }
      } }
      val endpoint = app.endpoints.head
      val payload = Json.obj("text" -> Json.fromString("中文🙂"))
      val http = Request[IO](Method.POST, Uri.unsafeFromString("/values/a%2Fb?id=x%26y&id=z"))
        .withEntity(payload.noSpaces).putHeaders(Header.Raw(CIString("Content-Type"), "application/json"),
          Header.Raw(CIString("x-token"), "token"), Header.Raw(CIString("Cookie"), "session=abc"))
      val native = Endpoint.Request(Map(
        (InputLocation.Path -> "id") -> Vector("a/b"), (InputLocation.Query -> "id") -> Vector("x&y", "z"),
        (InputLocation.Header -> "X-Token") -> Vector("token"), (InputLocation.Cookie -> "session") -> Vector("abc")), Some(payload))
      for
        _ <- parity(endpoint, http, native, 200)
        _ <- List("0" -> 400, "bad" -> 400, "3" -> 409, "4" -> 500).traverse_ { (count, status) =>
          parity(endpoint, http.withUri(http.uri.withQueryParam("count", count)),
            native.copy(parameters = native.parameters.updated(InputLocation.Query -> "count", Vector(count))), status)
        }
        _ <- parity(endpoint, http.withEmptyBody, native.copy(body = None), 400)
        count <- calls.get
      yield assertEquals(count, 6)
    }
  }

  test("structured bodies preserve text/JSON byte bounds, empty text, null and defaults") {
    val app = httpApp {
      server { maxBodyBytes(8) }
      routes {
        post("/text") {
          val input = body.text
          val ok = response.text(200) {}
          handle { IO.pure(ok(input.value)) }
        }
        post("/json") {
          val input = body.json[Json] { default(Json.fromInt(42)) }
          val ok = response.json[Json](200) {}
          handle { IO.pure(ok(input.value)) }
        }
      }
    }
    val cases = List(
      ("/text", "text/plain", Some(Json.fromString("")), "", 200),
      ("/text", "text/plain", Some(Json.fromString("🙂🙂")), "🙂🙂", 200),
      ("/text", "text/plain", Some(Json.fromString("中中中")), "中中中", 413),
      ("/json", "application/json", Some(Json.fromString("中文")), "\"中文\"", 200),
      ("/json", "application/json", Some(Json.fromString("中文a")), "\"中文a\"", 413),
      ("/json", "application/json", Some(Json.Null), "null", 200),
      ("/json", "application/json", None, "", 200))
    cases.traverse_ { (path, mediaType, body, text, status) =>
      parity(app.endpoints.find(_.path == path).get,
        Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(text).putHeaders(Header.Raw(CIString("Content-Type"), mediaType)),
        Endpoint.Request(body = body), status)
    }
  }

  test("native streaming and empty responses preserve HTTP headers and bytes") {
    val app = framework.rpcDsl.httpApp {
      framework.rpcDsl.command[framework.rpc.SSE[Json]]("events") {
        framework.rpcDsl.handle { IO.pure(framework.rpc.SSE(fs2.Stream.emit(Json.obj("text" -> Json.fromString("中文🙂"))))) }
      }
      framework.rpcDsl.command[Unit]("empty") { framework.rpcDsl.handle { IO.unit } }
    }
    app.endpoints.traverse_(endpoint => parity(endpoint, Request[IO](uri = Uri.unsafeFromString(endpoint.path)), Endpoint.Request(), 200))
  }
