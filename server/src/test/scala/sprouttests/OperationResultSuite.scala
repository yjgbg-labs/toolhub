package sprouttests

import cats.effect.IO
import io.circe.Json
import java.util.concurrent.atomic.AtomicInteger
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.headers.`Content-Type`
import scala.compiletime.testing.typeCheckErrors
import sprout.dsl.*

class OperationResultSuite extends CatsEffectSuite:
  private def request(path: String): Request[IO] = Request[IO](Method.POST, Uri.unsafeFromString(path))

  test("the operation type declares HTTP 200 and encodes IO success values") {
    val user = User(7, "Ada")
    val app = httpApp { routes {
      group("users") {
        operation[User]("get") { handle { IO.pure(user) } }
        operation[Page[User]]("list") { handle { IO.pure(Page(List(user))) } }
      }
    } }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val schema = doc.hcursor.downField("paths").downField("/users/list").downField("post")
      .downField("responses").downField("200").downField("content").downField("application/json").downField("schema")
    val name = schema.get[String]("$ref").toOption.get.stripPrefix("#/components/schemas/")
    assert(doc.hcursor.downField("components").downField("schemas").downField(name)
      .downField("properties").downField("items").downField("items").get[String]("$ref").isRight)
    for
      pure <- app.httpApp(request("/users/get"))
      pureBody <- pure.as[Json]
      effect <- app.httpApp(request("/users/list"))
      effectBody <- effect.as[Json]
    yield
      assertEquals(pure.status, Status.Ok)
      assertEquals(effect.status, Status.Ok)
      assertEquals(pureBody, JsonModel[User].encoder(user))
      assertEquals(effectBody, JsonModel[Page[User]].encoder(Page(List(user))))
  }

  test("String is text, Unit is empty and nullable and scalar JSON keep their declared schemas") {
    val app = httpApp { routes {
      operation[String]("text") { handle { IO.pure("literal \"text\"\n") } }
      operation[Unit]("empty") { handle { IO.unit } }
      operation[Option[User]]("nullable") { handle { IO.pure(None) } }
      operation[Int]("count") { handle { IO.pure(7) } }
    } }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    def success(path: String) = doc.hcursor.downField("paths").downField(path).downField("post").downField("responses").downField("200")
    assertEquals(success("/text").downField("content").downField("text/plain").downField("schema").get[String]("type"), Right("string"))
    assert(!success("/empty").downField("content").succeeded)
    assertEquals(success("/count").downField("content").downField("application/json").downField("schema").get[String]("type"), Right("integer"))
    for
      text <- app.httpApp(request("/text"))
      textBody <- text.as[String]
      empty <- app.httpApp(request("/empty"))
      emptyBody <- empty.as[String]
      nullable <- app.httpApp(request("/nullable")).flatMap(_.as[Json])
      count <- app.httpApp(request("/count")).flatMap(_.as[Json])
    yield
      assertEquals(text.status, Status.Ok)
      assertEquals(text.headers.get[`Content-Type`].map(_.mediaType), Some(org.http4s.MediaType.text.plain))
      assertEquals(textBody, "literal \"text\"\n")
      assertEquals(empty.status, Status.Ok)
      assertEquals(emptyBody, "")
      assertEquals(empty.headers.get[`Content-Type`], None)
      assertEquals(nullable, Json.Null)
      assertEquals(count, Json.fromInt(7))
  }

  test("declared errors short-circuit typed IO handlers with their own status and schema") {
    val app = httpApp { routes {
      operation[User]("get") {
        val id = path[Int]("id")
        val missing = response.json[sprout.ErrorBody](404) { description("User not found") }
        handle {
          IO.pure(Option.when(id.value == 1)(User(1, "Ada"))).flatMap {
            case Some(user) => IO.pure(user)
            case None => missing.raise(sprout.ErrorBody("not_found", "User not found"))
          }
        }
      }
      operation[Int]("check") {
        val valid = query[Boolean]("valid")
        val bad = response.text(400) {}
        handle { if valid.value then IO.pure(1) else bad.raise("rejected") }
      }
      operation[Unit]("gone") {
        val gone = response.empty(410) {}
        handle { gone.raise(()) }
      }
    } }
    val doc = io.circe.parser.parse(app.openApiJson).toOption.get
    val responses = doc.hcursor.downField("paths").downField("/get/{id}").downField("post").downField("responses")
    assert(responses.downField("200").downField("content").downField("application/json").succeeded)
    assert(responses.downField("404").downField("content").downField("application/json").succeeded)
    for
      found <- app.httpApp(request("/get/1"))
      missing <- app.httpApp(request("/get/2"))
      error <- missing.as[Json]
      valid <- app.httpApp(request("/check?valid=true"))
      invalid <- app.httpApp(request("/check?valid=false"))
      rejected <- invalid.as[String]
      gone <- app.httpApp(request("/gone"))
      empty <- gone.as[String]
    yield
      assertEquals(found.status, Status.Ok)
      assertEquals(missing.status, Status.NotFound)
      assertEquals(error.hcursor.get[String]("code"), Right("not_found"))
      assertEquals(valid.status, Status.Ok)
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(rejected, "rejected")
      assertEquals(gone.status, Status.Gone)
      assertEquals(empty, "")
  }

  test("response encoding and IO construction stay suspended until each request executes") {
    val calls = new AtomicInteger()
    val app = httpApp { routes {
      operation[Int]("pure") { handle { IO.pure(calls.incrementAndGet()) } }
      operation[Int]("effect") { handle { val value = calls.incrementAndGet(); IO.pure(value) } }
    } }
    val pending = app.httpApp(request("/pure"))
    assertEquals(calls.get(), 0)
    for
      first <- pending.flatMap(_.as[Json])
      second <- app.httpApp(request("/effect")).flatMap(_.as[Json])
      third <- app.httpApp(request("/pure")).flatMap(_.as[Json])
    yield
      assertEquals(first, Json.fromInt(1))
      assertEquals(second, Json.fromInt(2))
      assertEquals(third, Json.fromInt(3))
      assertEquals(calls.get(), 3)
  }

  test("handle requires IO of the declared operation type and errors check their payload type") {
    val pure = typeCheckErrors("""
      import sprout.dsl.*
      httpApp { routes { operation[Int]("wrong") { handle { 1 } } } }
    """)
    val effect = typeCheckErrors("""
      import cats.effect.IO
      import sprout.dsl.*
      httpApp { routes { operation[Int]("wrong") { handle { IO.pure("wrong") } } } }
    """)
    val widen = typeCheckErrors("""
      import cats.effect.IO
      import sprout.dsl.*
      httpApp { routes { operation[Int]("wrong") { handle[Any] { IO.pure("wrong") } } } }
    """)
    val mixed = typeCheckErrors("""
      import cats.effect.IO
      import sprout.dsl.*
      httpApp { routes { operation[Int]("wrong") {
        val flag = query[Boolean]("flag")
        handle { IO.pure(if flag.value then 1 else "wrong") }
      } } }
    """)
    val raw = typeCheckErrors("""
      import cats.effect.IO
      import sprout.dsl.*
      httpApp { routes { get("/wrong") { handle { IO.pure(1) } } } }
    """)
    val unit = typeCheckErrors("""
      import cats.effect.IO
      import sprout.dsl.*
      httpApp { routes { operation[Unit]("wrong") { handle { IO.pure[String]("wrong") } } } }
    """)
    assert(pure.nonEmpty)
    assert(effect.nonEmpty)
    assert(widen.nonEmpty)
    assert(mixed.nonEmpty)
    assert(raw.nonEmpty)
    assert(unit.nonEmpty)
    val endpointResult = typeCheckErrors("""
      import cats.effect.IO
      import sprout.dsl.*
      httpApp { routes { operation[Int]("wrong") {
        val bad = response.text(400) {}
        handle { IO.pure(bad("wrong")) }
      } } }
    """)
    assert(endpointResult.nonEmpty)
    val errorPayload = typeCheckErrors("""
      import cats.effect.IO
      import sprout.dsl.*
      httpApp { routes { operation[Unit]("wrong") {
        val bad = response.json[Int](400) {}
        handle { bad.raise("wrong") }
      } } }
    """)
    assert(errorPayload.nonEmpty)
  }

  test("typed operations still require handlers and reject a second HTTP 200 declaration") {
    intercept[IllegalArgumentException] { httpApp { routes { operation[Int]("missing") {} } } }
    intercept[IllegalArgumentException] { httpApp { routes { operation[Int]("duplicate") {
      response.json[String](200) {}
      handle { IO.pure(1) }
    } } } }
  }
