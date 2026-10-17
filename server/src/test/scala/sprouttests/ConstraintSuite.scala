package sprouttests

import cats.effect.IO
import io.circe.Json
import java.util.concurrent.atomic.AtomicInteger
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.typelevel.ci.CIString
import sprout.{Application, ErrorBody, FieldError}
import sprout.httpDsl.*

class ConstraintSuite extends CatsEffectSuite:
  private def request(path: String, method: Method = Method.GET): Request[IO] =
    Request[IO](method = method, uri = Uri.unsafeFromString(path))

  private def jsonRequest(payload: String): Request[IO] =
    request("/", Method.POST).withEntity(payload).putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))

  private def errors(json: Json): List[FieldError] =
    JsonModel[ErrorBody].decoder.decodeJson(json).toOption.get.errors

  test("custom constraints validate all parameter locations alongside built-in rules before handling") {
    val calls = new AtomicInteger()
    val app = httpApp { routes {
      get("/{id}") {
        val id = path[Int]("id") { constraint[Int]("ID must be even")(_ % 2 == 0) }
        val count = query[Int]("count") {
          default(20)
          minimum(1)
          maximum(100)
          constraint[Int]("Count must be even")(_ % 2 == 0)
        }
        val token = header[String]("X-Token") {
          constraint[String]("Token is reserved")(_ != "reserved")
        }
        val session = cookie[Option[String]]("session") {
          constraint[Option[String]]("Invalid session prefix")(_.forall(_.startsWith("s-")))
        }
        val ok = response.json[List[Int]](200) {}
        handle {
          calls.incrementAndGet()
          IO.pure(ok(List(id.value, count.value)))
        }
      }
    } }
    val valid = request("/2").putHeaders(Header.Raw(CIString("X-Token"), "abc"))
    val invalid = request("/3?count=3").putHeaders(
      Header.Raw(CIString("X-Token"), "reserved"), Header.Raw(CIString("Cookie"), "session=bad"))
    for
      success <- app.httpApp(valid)
      payload <- success.as[Json]
      rejected <- app.httpApp(invalid)
      invalidPayload <- rejected.as[Json]
      tooLarge <- app.httpApp(valid.withUri(Uri.unsafeFromString("/2?count=102")))
      largePayload <- tooLarge.as[Json]
      malformed <- app.httpApp(valid.withUri(Uri.unsafeFromString("/not-an-int")))
    yield
      assertEquals(success.status, Status.Ok)
      assertEquals(payload, Json.arr(Json.fromInt(2), Json.fromInt(20)))
      assertEquals(rejected.status, Status.BadRequest)
      assertEquals(errors(invalidPayload), List(
        FieldError("path", "id", "ID must be even"),
        FieldError("query", "count", "Count must be even"),
        FieldError("header", "X-Token", "Token is reserved"),
        FieldError("cookie", "session", "Invalid session prefix")))
      assertEquals(tooLarge.status, Status.BadRequest)
      assertEquals(errors(largePayload), List(FieldError("query", "count", "Must be at most 100")))
      assertEquals(malformed.status, Status.BadRequest)
      assertEquals(calls.get(), 1)
  }

  test("JSON body constraints validate multiple fields once after successful decoding") {
    val checks = new AtomicInteger()
    val calls = new AtomicInteger()
    val app = httpApp { routes {
      post("/") {
        val input = body.json[Interval] {
          constraint[Interval]("Start must be before end") { value =>
            checks.incrementAndGet()
            value.start < value.end
          }
        }
        val ok = response.json[List[Int]](200) {}
        handle {
          calls.incrementAndGet()
          IO.pure(ok(List(input.value.start, input.value.end)))
        }
      }
    } }
    assertEquals(checks.get(), 0)
    for
      success <- app.httpApp(jsonRequest("""{"start":1,"end":5}"""))
      payload <- success.as[Json]
      rejected <- app.httpApp(jsonRequest("""{"start":5,"end":1}"""))
      invalidPayload <- rejected.as[Json]
      malformed <- app.httpApp(jsonRequest("""{"start":"invalid","end":5}"""))
    yield
      assertEquals(success.status, Status.Ok)
      assertEquals(payload, Json.arr(Json.fromInt(1), Json.fromInt(5)))
      assertEquals(rejected.status, Status.BadRequest)
      assertEquals(errors(invalidPayload), List(FieldError("body", "body", "Start must be before end")))
      assertEquals(malformed.status, Status.BadRequest)
      assertEquals(checks.get(), 2)
      assertEquals(calls.get(), 1)
  }

  test("custom constraints reject invalid parameter and body defaults during configuration") {
    interceptMessage[IllegalArgumentException]("requirement failed: Invalid default for count") {
      httpApp { routes { get() {
        val count = query[Int]("count") {
          default(3)
          constraint[Int]("Count must be even")(_ % 2 == 0)
        }
        val ok = response.json[Int](200) {}
        handle { IO.pure(ok(count.value)) }
      } } }
    }
    interceptMessage[IllegalArgumentException]("requirement failed: Invalid body default") {
      httpApp { routes { post("/") {
        val input = body.json[Interval] {
          default(Interval(5, 1))
          constraint[Interval]("Start must be before end")(value => value.start < value.end)
        }
        val ok = response.json[Interval](200) {}
        handle { IO.pure(ok(input.value)) }
      } } }
    }
  }

  test("runtime-only constraints preserve OpenAPI schemas and standard validation keywords") {
    def application(custom: Boolean): Application = httpApp { routes {
      get() {
        val count = query[Int]("count") {
          minimum(1)
          maximum(100)
          if custom then constraint[Int]("Count must be even")(_ % 2 == 0)
        }
        val name = query[String]("name") {
          minLength(3)
          maxLength(10)
          pattern("^[a-z]+$")
          if custom then constraint[String]("Name is reserved")(_ != "reserved")
        }
        val ok = response.text(200) {}
        handle { IO.pure(ok(s"${name.value}:${count.value}")) }
      }
      post("/") {
        val input = body.json[Interval] {
          if custom then constraint[Interval]("Start must be before end")(value => value.start < value.end)
        }
        val ok = response.json[Interval](200) {}
        handle { IO.pure(ok(input.value)) }
      }
    } }
    val app = application(true)
    assertEquals(app.openApiJson, application(false).openApiJson)
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val params = doc.hcursor.downField("paths").downField("/").downField("get").get[List[Json]]("parameters").toOption.get
    val schemas = params.map(p => p.hcursor.get[String]("name").toOption.get -> p.hcursor.downField("schema")).toMap
    assertEquals(schemas("count").get[Int]("minimum"), Right(1))
    assertEquals(schemas("count").get[Int]("maximum"), Right(100))
    assertEquals(schemas("name").get[Int]("minLength"), Right(3))
    assertEquals(schemas("name").get[Int]("maxLength"), Right(10))
    assertEquals(schemas("name").get[String]("pattern"), Right("^[a-z]+$"))
  }
