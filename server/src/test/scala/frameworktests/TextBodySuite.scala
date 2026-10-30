package frameworktests

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import framework.httpDsl.*

class TextBodySuite extends CatsEffectSuite:
  private def request(path: String) = Request[IO](method = Method.POST, uri = Uri.unsafeFromString(path))

  test("HTTP text bodies preserve raw UTF-8, distinguish empty from missing and apply defaults and constraints") {
    val app = httpApp {
      routes {
        post("/text") {
          val input = body.text { default("fallback"); maxLength(80) }
          val ok = response.text(200) {}
          handle { IO.pure(ok(input.value)) }
        }
        post("/required") {
          val input = body.text { minLength(1) }
          val ok = response.text(200) {}
          handle { IO.pure(ok(input.value)) }
        }
      }
    }
    val source = "  const text = '你好🙂';\r\nconsole.log(\"quoted\\n\", text);\n"
    for
      raw <- app.httpApp(request("/text").withEntity(source)).flatMap(_.as[String])
      defaulted <- app.httpApp(request("/text")).flatMap(_.as[String])
      empty <- app.httpApp(request("/text").withEntity("")).flatMap(_.as[String])
      invalid <- app.httpApp(request("/text").withEntity("x" * 81))
      missing <- app.httpApp(request("/required"))
      blank <- app.httpApp(request("/required").withEntity(""))
    yield
      assertEquals(raw, source)
      assertEquals(defaulted, "fallback")
      assertEquals(empty, "")
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(missing.status, Status.BadRequest)
      assertEquals(blank.status, Status.BadRequest)
      val operation = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths").downField("/text").downField("post")
      val body = operation.downField("requestBody")
      assertEquals(body.get[Boolean]("required"), Right(false))
      val schema = body.downField("content").downField("text/plain").downField("schema")
      assertEquals(schema.get[String]("type"), Right("string"))
      assertEquals(schema.get[String]("default"), Right("fallback"))
      assertEquals(schema.get[Int]("maxLength"), Right(80))
      assertEquals(operation.downField("responses").downField("415").get[String]("description"), Right("Expected text/plain"))
  }

  test("inherited defaulted RPC text body selects POST and preserves JSON body syntax") {
    import framework.rpcDsl.*
    val app = framework.rpcDsl.httpApp {
      commands("notes") {
        command[PlainText]("save") { handle { IO.pure(PlainText("saved")) } }
        body.text("Note text") { default("untitled") }
      }
      command[String]("json") {
        val input = body[String]("JSON string")
        handle { IO.pure(input.value) }
      }
    }
    for
      saved <- app.httpApp(request("/notes/save"))
      wrongMethod <- app.httpApp(Request[IO](uri = Uri.unsafeFromString("/notes/save")))
    yield
      assertEquals(saved.status, Status.Ok)
      assertEquals(wrongMethod.status, Status.MethodNotAllowed)
      val paths = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths")
      assert(paths.downField("/notes/save").downField("post").downField("requestBody").downField("content").downField("text/plain").succeeded)
      assert(paths.downField("/json").downField("post").downField("requestBody").downField("content").downField("application/json").succeeded)
  }
