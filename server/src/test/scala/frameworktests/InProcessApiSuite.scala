package frameworktests

import cats.effect.IO
import framework.Client
import framework.http.InputLocation
import framework.httpDsl.*
import framework.model.SchemaNode
import munit.CatsEffectSuite
import org.http4s.{Request, Status, Uri}

class InProcessApiSuite extends CatsEffectSuite:
  test("in-process views expose HTTP declarations and raw responses without SDK naming or size policies") {
    val suffix = "x" * (1024 * 1024 + 1)
    val app = httpApp { routes { get("/values/{id}") {
      operationId("values.read")
      val pathId = path[String]("id")
      val queryId = query[String]("id")
      val ok = response.text(200) {}
      handle { IO.pure(ok(s"${pathId.value}:${queryId.value}:$suffix")) }
    } } }
    val api = app.inProcess(Client.graaljs)
    val endpoint = api.endpoints.head
    assertEquals(endpoint.operationId, "values.read")
    assertEquals((endpoint.method, endpoint.path), ("GET", "/values/{id}"))
    assertEquals(endpoint.inputs.map(input => (input.name, input.location, input.schema, input.bodyMediaType)), Vector(
      ("id", InputLocation.Path, SchemaNode.Primitive("string"), None),
      ("id", InputLocation.Query, SchemaNode.Primitive("string"), None)))
    for
      response <- api.httpApp(Request[IO](uri = Uri.unsafeFromString("/values/path?id=query")))
      text <- response.as[String]
      missing <- api.httpApp(Request[IO](uri = Uri.unsafeFromString("/values/path")))
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(text, s"path:query:$suffix")
      assertEquals(missing.status, Status.BadRequest)
  }
