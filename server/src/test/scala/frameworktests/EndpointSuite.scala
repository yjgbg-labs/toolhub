package frameworktests

import cats.effect.IO
import framework.Client
import framework.http.InputLocation
import framework.httpDsl.*
import framework.model.SchemaNode
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

class EndpointSuite extends CatsEffectSuite:
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
