package framework.http

import cats.effect.IO
import java.util.concurrent.atomic.AtomicInteger
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Uri}
import framework.rpcDsl.*
import framework.runtime.Application

class CompilerSuite extends CatsEffectSuite:
  test("RPC nesting freezes into flat HTTP declarations that can be compiled repeatedly without running handlers") {
    val calls = new AtomicInteger()
    var builder = Option.empty[AppScope]
    var configure: () => Unit = () => ()
    httpApp {
      builder = Some(summon[AppScope])
      commands("users") {
        val tenant = param[String]("tenant", "Tenant") { default("default") }
        configure = () => tenant { default("changed") }
        command[String]("get") { handle { calls.incrementAndGet(); IO.pure(tenant.value) } }
        commands("admin") { command[String]("get") { handle { IO.pure(tenant.value) } } }
        val failed = response.text(503) {}
        recover { case _: IllegalStateException => failed("unavailable") }
      }
    }
    val declaration = builder.get.freeze()
    intercept[IllegalArgumentException](configure())
    assertEquals(declaration.endpoints.map(_.path), Vector("/users/get", "/users/admin/get"))
    val groups = declaration.extensions("x-rpc").hcursor.get[List[io.circe.Json]]("commands").toOption.get
    assertEquals(groups.map(_.hcursor.get[String]("name").toOption.get).toSet, Set("users", "users.admin"))
    assert(declaration.endpoints.forall(_.scope.inputs.size == 1))
    val sharedInput = declaration.endpoints.head.scope.inputs.head
    val resolved = Compiler.resolve(declaration)
    assertEquals(resolved.endpoints.map(_.spec.operationId), Vector("users.get", "users.admin.get"))
    assert(resolved.endpoints.forall(_.spec.inputs.head.key eq sharedInput.key))
    assert(resolved.endpoints.forall(_.spec.responses.map(_.status).toSet == Set(200, 400, 500, 503)))
    val first = Application.build(resolved)
    val second = Application.build(Compiler.resolve(declaration))
    assertEquals(first.openApiJson, second.openApiJson)
    assertEquals(calls.get(), 0)
    for
      a <- first.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/users/get"))).flatMap(_.as[String])
      b <- second.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/users/get"))).flatMap(_.as[String])
    yield
      assertEquals(a, "\"default\"")
      assertEquals(b, "\"default\"")
      assertEquals(calls.get(), 2)
  }
