package sprouttests

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.{middleware as _, *}
import org.typelevel.ci.CIString
import scala.compiletime.testing.{typeCheckErrors, typeChecks}
import sprout.{CommandScope, CommandsScope}
import sprout.rpcDsl.*

class CommandSuite extends CatsEffectSuite:
  private def request(path: String, method: Method = Method.GET): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))

  private def jsonRequest(path: String, json: String): Request[IO] =
    request(path, Method.POST).withEntity(json).putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))

  test("commands without a body use GET and described, typed query parameters") {
    val app = httpApp {
      command[List[String]]("admin.users.get") {
        val id = param[Long]("id", "User ID") { minimum(1L) }
        val detail = param[Boolean]("detail", "Include details") { default(false) }
        val filter = param[Option[String]]("filter", "Optional filter")
        val labels = param[List[String]]("label", "Labels")
        handle { IO.pure(List(id.value.toString, detail.value.toString, filter.value.getOrElse("none")) ++ labels.value) }
      }
    }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val path = doc.hcursor.downField("paths").downField("/admin/users/get")
    val op = path.downField("get")
    assertEquals(op.get[String]("operationId"), Right("admin.users.get"))
    assert(!path.downField("post").succeeded)
    val params = op.get[List[Json]]("parameters").toOption.get
    assertEquals(params.map(_.hcursor.get[String]("in").toOption.get), List.fill(4)("query"))
    assertEquals(params.head.hcursor.get[String]("description"), Right("User ID"))
    assertEquals(params(1).hcursor.downField("schema").get[Boolean]("default"), Right(false))
    for
      result <- app.httpApp(request("/admin/users/get?id=7&label=a&label=b")).flatMap(_.as[Json])
      invalid <- app.httpApp(request("/admin/users/get?id=0"))
      missing <- app.httpApp(request("/admin/users/get"))
      wrongMethod <- app.httpApp(request("/admin/users/get?id=7", Method.POST))
    yield
      assertEquals(result, Json.fromValues(List("7", "false", "none", "a", "b").map(Json.fromString)))
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(missing.status, Status.BadRequest)
      assertEquals(wrongMethod.status, Status.MethodNotAllowed)
  }

  test("commands with a described JSON body use POST and retain query parameters and validation") {
    val app = httpApp { commands("users") {
      command[User]("create") {
        val id = param[Long]("id", "User ID")
        val input = body[CreateUser]("User payload") {
          constraint[CreateUser]("Name must not be blank")(_.name.trim.nonEmpty)
        }
        handle { IO.pure(User(id.value, input.value.name)) }
      }
    } }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val path = doc.hcursor.downField("paths").downField("/users/create")
    val op = path.downField("post")
    assertEquals(op.downField("requestBody").get[String]("description"), Right("User payload"))
    assert(!path.downField("get").succeeded)
    for
      result <- app.httpApp(jsonRequest("/users/create?id=7", """{"name":"Ada"}""")).flatMap(_.as[Json])
      invalid <- app.httpApp(jsonRequest("/users/create?id=7", """{"name":" "}"""))
      missing <- app.httpApp(request("/users/create?id=7", Method.POST))
      wrongMethod <- app.httpApp(request("/users/create?id=7"))
    yield
      assertEquals(result, JsonModel[User].encoder(User(7, "Ada")))
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(missing.status, Status.BadRequest)
      assertEquals(wrongMethod.status, Status.MethodNotAllowed)
  }

  test("late ancestor bodies determine the method even when optional or defaulted") {
    val app = httpApp {
      commands("optional") {
        commands("nested") { command[Int]("read") { handle { IO.pure(1) } } }
        body[Option[Int]]("Optional payload")
      }
      commands("defaulted") {
        command[Int]("read") { handle { IO.pure(2) } }
        body[Int]("Default payload") { default(3) }
      }
      command[String]("health") { handle { IO.pure("ok") } }
    }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    assert(doc.hcursor.downField("paths").downField("/health").downField("get").succeeded)
    for
      optional <- app.httpApp(request("/optional/nested/read", Method.POST))
      defaulted <- app.httpApp(request("/defaulted/read", Method.POST))
      wrong <- app.httpApp(request("/optional/nested/read"))
    yield
      assertEquals(optional.status, Status.Ok)
      assertEquals(defaulted.status, Status.Ok)
      assertEquals(wrong.status, Status.MethodNotAllowed)
      List("/optional/nested/read", "/defaulted/read").foreach { path =>
        val op = doc.hcursor.downField("paths").downField(path).downField("post")
        assertEquals(op.downField("requestBody").get[Boolean]("required"), Right(false))
      }
  }

  test("nested commands qualify operation IDs and publish inherited tags and descriptions") {
    val app = httpApp { commands("reports") {
      commands("daily") {
        description("Daily reports")
        command[String]("get") {
          summary("Get a report")
          description("Report details")
          val id = param[Int]("id", "Report ID")
          handle { IO.pure(id.value.toString) }
        }
      }
      description("Manage reports")
    } }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val tags = doc.hcursor.get[List[Json]]("tags").toOption.get.map { tag =>
      tag.hcursor.get[String]("name").toOption.get -> tag.hcursor.get[String]("description").toOption.get
    }.toMap
    assertEquals(tags, Map("reports" -> "Manage reports", "reports.daily" -> "Daily reports"))
    val op = doc.hcursor.downField("paths").downField("/reports/daily/get").downField("get")
    assertEquals(op.get[String]("operationId"), Right("reports.daily.get"))
    assertEquals(op.get[String]("description"), Right("Report details"))
    assertEquals(op.get[List[String]]("tags"), Right(List("reports", "reports.daily")))
    app.httpApp(request("/reports/daily/get?id=7")).flatMap(_.as[String]).map(value => assertEquals(value, "7"))
  }

  test("commands can be reopened with consistent metadata") {
    val app = httpApp {
      commands("reports") { description("Manage reports"); command[String]("list") { handle { IO.pure("list") } } }
      commands("reports") { command[String]("get") { handle { IO.pure("get") } } }
    }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    assertEquals(doc.hcursor.get[List[Json]]("tags").toOption.get.size, 1)
    app.httpApp(request("/reports/get")).flatMap(_.as[String]).map(value => assertEquals(value, "get"))
  }

  test("invalid names, conflicting metadata and duplicate command identities fail at startup") {
    List("", ".users", "users.", "users..list", "users/list").foreach { name =>
      intercept[IllegalArgumentException] { httpApp { command[String](name) {} } }
    }
    List("", "/users", "users.admin", "bad name").foreach { name =>
      intercept[IllegalArgumentException] { httpApp { commands(name) {} } }
    }
    intercept[IllegalArgumentException] { httpApp {
      commands("users") { description("First") }
      commands("users") { description("Second") }
    } }
    intercept[IllegalArgumentException] { httpApp {
      command[String]("users.get") { handle { IO.pure("one") } }
      commands("users") { command[String]("get") { body[Int]("Payload"); handle { IO.pure("two") } } }
    } }
  }

  test("HTTP and RPC declarations share the application and detect cross-DSL conflicts") {
    val app = httpApp {
      sprout.httpDsl.routes {
        import sprout.httpDsl.*
        group("/api") { put("/{id}") {
          val id = path[Int]("id")
          val ok = response.json[Int](201) {}
          handle { IO.pure(ok(id.value)) }
        } }
      }
      command[String]("health") { handle { IO.pure("ok") } }
    }
    intercept[IllegalArgumentException] { httpApp {
      sprout.httpDsl.routes {
        import sprout.httpDsl.*
        get("/health") { val ok = response.text(200) {}; handle { IO.pure(ok("manual")) } }
      }
      command[String]("health") { handle { IO.pure("rpc") } }
    } }
    for
      http <- app.httpApp(request("/api/7", Method.PUT))
      rpc <- app.httpApp(request("/health")).flatMap(_.as[String])
    yield
      assertEquals(http.status, Status.Created)
      assertEquals(rpc, "ok")
  }

  test("httpApp configures the server and shares root declarations with top-level and nested commands") {
    val app = httpApp {
      server { port(0); maxBodyBytes(128) }
      openapi { info { title("Root commands"); version("2.0") } }
      val tenant = param[String]("tenant", "Shared tenant") { minLength(1); default("default") }
      val denied = response.text(403) { description("Tenant denied") }
      command[String]("read") { handle { IO.pure(tenant.value) } }
      commands("nested") {
        command[String]("read") { handle { IO.pure(tenant.value) } }
      }
      // Root declarations made after commands must still affect both levels.
      val input = body[Option[Int]]("Optional root payload")
      middleware { next =>
        if input.value.contains(-1) then IO.pure(denied("denied")) else next
      }
    }
    assertEquals(app.serverSettings.port, 0)
    assertEquals(app.serverSettings.maxBodyBytes, 128L)
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    assertEquals(doc.hcursor.downField("info").get[String]("title"), Right("Root commands"))
    for
      root <- app.httpApp(request("/read", Method.POST)).flatMap(_.as[String])
      nested <- app.httpApp(jsonRequest("/nested/read?tenant=acme", "7")).flatMap(_.as[String])
      denied <- app.httpApp(jsonRequest("/read", "-1"))
      invalid <- app.httpApp(request("/nested/read?tenant=", Method.POST))
      wrongMethod <- app.httpApp(request("/read"))
    yield
      assertEquals(root, "default")
      assertEquals(nested, "acme")
      assertEquals(denied.status, Status.Forbidden)
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(wrongMethod.status, Status.MethodNotAllowed)
      List("/read", "/nested/read").foreach { path =>
        val operation = doc.hcursor.downField("paths").downField(path).downField("post")
        assertEquals(operation.downField("requestBody").get[String]("description"), Right("Optional root payload"))
        assert(operation.downField("responses").downField("403").succeeded)
      }
  }

  test("command contexts retain the result type and commands contexts cannot install handlers") {
    summon[CommandScope[Int] <:< CommandsScope]
    val root = typeCheckErrors("""
      import cats.effect.IO
      import sprout.rpcDsl.*
      httpApp { handle { IO.pure(1) } }
    """)
    val group = typeCheckErrors("""
      import cats.effect.IO
      import sprout.rpcDsl.*
      httpApp { commands("users") { handle { IO.pure(1) } } }
    """)
    assert(root.exists(_.message.contains("CommandScope")), root.toString)
    assert(group.exists(_.message.contains("CommandScope")), group.toString)
    assert(typeChecks("""
      import cats.effect.IO
      import sprout.{CommandScope, CommandsScope}
      import sprout.rpcDsl.*
      def common(using CommandsScope) = param[Int]("n", "Number") { default(1) }
      def run(using CommandScope[Int]): Unit = { val n = common; handle { IO.pure(n.value) } }
      httpApp { command[Int]("get") { run }; commands("users") { command[Int]("get") { run } } }
    """))
  }
