package sprouttests

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.*
import sprout.dsl.*

class OperationSuite extends CatsEffectSuite:
  test("operation IDs produce POST routes, ordered path parameters and OpenAPI contracts") {
    val app = httpApp { routes {
      operation("admin.users.get") {
        val tenant = path[String]("tenant")
        val id = path[Long]("id") { minimum(1L) }
        val detail = query[Boolean]("detail") { default(false) }
        val ok = response.json[List[String]](200) {}
        handle { ok(List(tenant.value, id.value.toString, detail.value.toString)) }
      }
    } }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val op = doc.hcursor.downField("paths").downField("/admin/users/get/{tenant}/{id}").downField("post")
    assertEquals(op.get[String]("operationId"), Right("admin.users.get"))
    val uri = Uri.unsafeFromString("/admin/users/get/acme/7?detail=true")
    for
      response <- app.httpApp(Request[IO](Method.POST, uri))
      json <- response.as[Json]
      wrongMethod <- app.httpApp(Request[IO](Method.GET, uri))
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(json, Json.fromValues(List("acme", "7", "true").map(Json.fromString)))
      assertEquals(wrongMethod.status, Status.MethodNotAllowed)
  }

  test("operation routes retain group prefixes and inherit path parameters exactly once") {
    val app = httpApp { routes { group("/api/{tenant}") {
      operation("users.get") {
        val tenant = path[String]("tenant")
        val id = path[Long]("id")
        val ok = response.text(200) {}
        handle { ok(s"${tenant.value}:${id.value}") }
      }
    } } }
    app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/api/acme/users/get/7"))).flatMap(_.as[String])
      .map(value => assertEquals(value, "acme:7"))
  }

  test("operation identity is valid, immutable and globally unique across route groups") {
    List("", ".users", "users.", "users..list", "users/list").foreach { id =>
      intercept[IllegalArgumentException] { httpApp { routes { operation(id) {} } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes { operation("users.list") {
        operationId("renamed")
        val ok = response.text(200) {}
        handle { ok("ok") }
      } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes {
        List("/a", "/b").foreach { prefix => group(prefix) {
          operation("users.list") { val ok = response.text(200) {}; handle { ok("ok") } }
        } }
      } }
    }
  }
