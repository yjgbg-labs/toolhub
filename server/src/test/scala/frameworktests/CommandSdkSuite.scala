package frameworktests

import cats.effect.IO
import framework.httpDsl.*
import io.circe.Json
import munit.CatsEffectSuite

class CommandSdkSuite extends CatsEffectSuite:
  test("in-process commands preserve encoded path/query/header/cookie inputs and HTTP validation") {
    val app = httpApp {
      routes {
        get("/values/{id}") {
          operationId("values.read")
          val id = path[String]("id")
          val words = query[List[String]]("word")
          val token = header[String]("X-Token")
          val session = cookie[String]("session")
          val ok = response.json[List[String]](200) {}
          handle { IO.pure(ok(List(id.value, words.value.mkString("|"), token.value, session.value))) }
        }
      }
    }
    val input = Json.obj(
      "path" -> Json.obj("id" -> Json.fromString("a/b 中文?")),
      "query" -> Json.obj("word" -> Json.arr(Json.fromString("a&b"), Json.fromString("x=y"))),
      "header" -> Json.obj("X-Token" -> Json.fromString("token")),
      "cookie" -> Json.obj("session" -> Json.fromString("abc")))
    for
      response <- app.commandSdk.call("values.read", input)
      _ = assertEquals(response.hcursor.get[Int]("status"), Right(200))
      _ = assertEquals(response.hcursor.get[List[String]]("body"), Right(List("a/b 中文?", "a&b|x=y", "token", "abc")))
      missing <- app.commandSdk.call("values.read", input.mapObject(_.remove("header")))
      _ = assertEquals(missing.hcursor.get[Int]("status"), Right(400))
      invalid <- app.commandSdk.call("values.read", input.mapObject(_.add("header", Json.obj("X-Token" -> Json.fromString("x\r\ny"))))).attempt
      _ = assert(invalid.isLeft)
      unknown <- app.commandSdk.call("values.missing", Json.obj()).attempt
    yield assert(unknown.isLeft)
  }
