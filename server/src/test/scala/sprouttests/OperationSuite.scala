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
      operation[List[String]]("admin.users.get") {
        val tenant = path[String]("tenant")
        val id = path[Long]("id") { minimum(1L) }
        val detail = query[Boolean]("detail") { default(false) }
        handle { IO.pure(List(tenant.value, id.value.toString, detail.value.toString)) }
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
      operation[String]("users.get") {
        val tenant = path[String]("tenant")
        val id = path[Long]("id")
        handle { IO.pure(s"${tenant.value}:${id.value}") }
      }
    } } }
    app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/api/acme/users/get/7"))).flatMap(_.as[String])
      .map(value => assertEquals(value, "acme:7"))
  }

  test("operation identity is valid, immutable and globally unique across route groups") {
    List("", ".users", "users.", "users..list", "users/list").foreach { id =>
      intercept[IllegalArgumentException] { httpApp { routes { operation[String](id) {} } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes { operation[String]("users.list") {
        operationId("renamed")
        handle { IO.pure("ok") }
      } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes {
        List("/a", "/b").foreach { prefix => group(prefix) {
          operation[String]("users.list") { handle { IO.pure("ok") } }
        } }
      } }
    }
  }

  test("nested named groups qualify IDs and publish descriptions without changing HTTP prefix semantics") {
    val app = httpApp { routes { group("/api/{tenant}") {
      group("reports") {
        group("daily") {
          description("Daily reports")
          operation[String]("get") {
            operationId("reports.daily.get")
            summary("Get a report")
            description("Report details")
            val tenant = path[String]("tenant")
            val id = path[Int]("id")
            handle { IO.pure(s"${tenant.value}:${id.value}") }
          }
        }
        description("Manage reports")
      }
    } } }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val groups = doc.hcursor.get[List[Json]]("tags").toOption.get.map { tag =>
      tag.hcursor.get[String]("name").toOption.get -> tag.hcursor.get[String]("description").toOption.get
    }.toMap
    assertEquals(groups, Map("reports" -> "Manage reports", "reports.daily" -> "Daily reports"))
    val op = doc.hcursor.downField("paths").downField("/api/{tenant}/reports/daily/get/{id}").downField("post")
    assertEquals(op.get[String]("operationId"), Right("reports.daily.get"))
    assertEquals(op.get[String]("description"), Right("Report details"))
    assertEquals(op.get[List[String]]("tags"), Right(List("reports", "reports.daily")))
    app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/api/acme/reports/daily/get/7"))).flatMap(_.as[String])
      .map(value => assertEquals(value, "acme:7"))
  }

  test("operation groups can be reopened with consistent metadata") {
    val app = httpApp { routes {
      group("reports") {
        description("Manage reports")
        operation[String]("list") { handle { IO.pure("list") } }
      }
      group("reports") {
        operation[String]("get") { handle { IO.pure("get") } }
      }
    } }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val groups = doc.hcursor.get[List[Json]]("tags").toOption.get
    assertEquals(groups.size, 1)
    assertEquals(groups.head.hcursor.get[String]("description"), Right("Manage reports"))
    assertEquals(doc.hcursor.downField("paths").downField("/reports/get").downField("post").get[String]("operationId"), Right("reports.get"))
  }

  test("invalid names, conflicting group descriptions and duplicate qualified IDs fail at startup") {
    List("reports.daily", "reports/daily", "1reports", " reports").foreach { name =>
      intercept[IllegalArgumentException] { httpApp { routes { group(name) {} } } }
    }
    interceptMessage[IllegalArgumentException]("requirement failed: Conflicting descriptions for operation group: reports") {
      httpApp { routes {
        group("reports") { description("First") }
        group("reports") { description("Second") }
      } }
    }
    interceptMessage[IllegalArgumentException]("requirement failed: Duplicate operationId") {
      httpApp { routes {
        operation[String]("reports.list") { handle { IO.pure("first") } }
        group("/api") { group("reports") {
          operation[String]("list") { handle { IO.pure("second") } }
        } }
      } }
    }
  }
