package frameworktests

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import framework.runtime.Application
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import scala.compiletime.testing.typeChecks

class ExposureSuite extends CatsEffectSuite:
  private def exposure(app: Application, path: String): List[String] =
    io.circe.parser.parse(app.openApiJson).toOption.get.hcursor
      .downField("paths").downField(path).downField("get").get[List[String]]("x-expose-on").toOption.get

  test("default exposure and every client combination drive OpenAPI, HTTP and per-client in-process views") {
    val combinations = (1 to 3).toVector.flatMap(Client.values.toVector.combinations(_)).zipWithIndex
    val app = httpApp {
      command[Int]("default") { handle { IO.pure(42) } }
      combinations.foreach { (clients, index) =>
        command[Int](s"choice$index") {
          exposeOn(clients.head, clients.tail*)
          handle { IO.pure(index) }
        }
      }
    }
    assertEquals(exposure(app, "/default"), List("cli", "codemode", "graaljs"))
    combinations.traverse_ { (clients, index) =>
      val id = s"choice$index"
      assertEquals(exposure(app, s"/$id"), clients.map(_.toString).toList)
      for
        http <- app.httpApp(Request[IO](uri = Uri.unsafeFromString(s"/$id")))
        _ = assertEquals(http.status, if clients.exists(_ != graaljs) then Status.Ok else Status.NotFound)
        _ <- Client.values.toVector.traverse_ { client =>
          val api = app.inProcess(client)
          assertEquals(api.endpoints.exists(_.operationId == id), clients.contains(client))
          api.httpApp(Request[IO](uri = Uri.unsafeFromString(s"/$id"))).flatMap { response =>
            assertEquals(response.status, if clients.contains(client) then Status.Ok else Status.NotFound)
            response.as[String].map { text => if clients.contains(client) then assertEquals(text, index.toString) }
          }
        }
      yield ()
    }
  }

  test("late root and group declarations intersect; sibling and reopened groups retain their own scopes") {
    val app = httpApp {
      commands("mixed") {
        command[Int]("inherited") { handle { IO.pure(1) } }
        commands("nested") {
          command[Int]("narrow") { exposeOn(codemode, graaljs); handle { IO.pure(2) } }
        }
        // Late declarations must apply to already declared descendants, including nested groups.
        exposeOn(cli, graaljs)
      }
      commands("mixed") { command[Int]("reopened") { handle { IO.pure(3) } } }
      command[Int]("sibling") { handle { IO.pure(4) } }
      exposeOn(cli, graaljs)
    }
    assertEquals(exposure(app, "/mixed/inherited"), List("cli", "graaljs"))
    assertEquals(exposure(app, "/mixed/nested/narrow"), List("graaljs"))
    assertEquals(exposure(app, "/mixed/reopened"), List("cli", "graaljs"))
    assertEquals(exposure(app, "/sibling"), List("cli", "graaljs"))

    val independent = httpApp {
      commands("same") { exposeOn(cli); command[Int]("local") { handle { IO.pure(1) } } }
      commands("same") { command[Int]("shared") { handle { IO.pure(2) } } }
    }
    assertEquals(exposure(independent, "/same/local"), List("cli"))
    assertEquals(exposure(independent, "/same/shared"), List("cli", "codemode", "graaljs"))
  }

  test("invalid and repeated declarations fail, and exposure cannot mutate after freezing") {
    assert(!typeChecks("""
      import framework.rpcDsl.*
      httpApp { exposeOn() }
    """))
    assert(!typeChecks("""
      import framework.rpcDsl.*
      httpApp { exposeOn("cli") }
    """))
    val conflict = intercept[IllegalArgumentException] { httpApp {
      commands("private") {
        exposeOn(graaljs)
        command[Int]("read") { exposeOn(cli); handle { IO.pure(1) } }
      }
    } }
    assert(conflict.getMessage.contains("No common exposed client: private.read"))
    intercept[IllegalArgumentException] { httpApp { commands("duplicate") { exposeOn(cli); exposeOn(graaljs) } } }
    var late: () => Unit = () => ()
    httpApp { commands("frozen") {
      val scope = summon[CommandsScope]
      late = () => exposeOn(cli)(using scope)
    } }
    val frozen = intercept[IllegalArgumentException](late())
    assert(frozen.getMessage.contains("configuration is frozen"))
  }

  test("GraalJS-only commands retain validation and middleware but are unreachable over TCP") {
    for
      calls <- Ref.of[IO, Int](0)
      app = httpApp {
        server { port(0) }
        commands("internal") {
          exposeOn(graaljs)
          middleware { next => calls.update(_ + 1) *> next }
          command[Int]("read") {
            val number = param[Int]("number", "Positive number") { minimum(1) }
            val denied = response.text(409) { description("Conflict") }
            handle { if number.value == 2 then denied.raise("conflict") else IO.pure(number.value) }
          }
        }
      }
      _ <- (app.resource, EmberClientBuilder.default[IO].build).tupled.use { (server, client) =>
        val uri = Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}/internal/read?number=1")
        List(Method.GET, Method.POST, Method.HEAD, Method.OPTIONS).traverse_ { method =>
          client.run(Request[IO](method, uri).putHeaders(Header.Raw(CIString("X-RPC-Client"), "graaljs"))).use { response =>
            IO(assertEquals(response.status, Status.NotFound))
          }
        }
      }
      before <- calls.get
      api = app.inProcess(graaljs)
      valid <- api.httpApp(Request[IO](uri = Uri.unsafeFromString("/internal/read?number=1")))
      text <- valid.as[String]
      invalid <- api.httpApp(Request[IO](uri = Uri.unsafeFromString("/internal/read?number=0")))
      denied <- api.httpApp(Request[IO](uri = Uri.unsafeFromString("/internal/read?number=2")))
      after <- calls.get
    yield
      assertEquals(before, 0)
      assertEquals(valid.status, Status.Ok)
      assertEquals(text, "1")
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(denied.status, Status.Conflict)
      assertEquals(after, 2)
  }
