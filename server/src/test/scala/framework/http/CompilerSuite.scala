package framework.http

import cats.effect.IO
import java.util.concurrent.atomic.AtomicInteger
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Uri}
import framework.rpcDsl.*
import framework.runtime.Application

class CompilerSuite extends CatsEffectSuite:
  test("a frozen declaration tree can be resolved and compiled repeatedly without running handlers") {
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
        middleware { next => next.recover { case _: IllegalStateException => failed("unavailable") } }
      }
    }
    val declaration = builder.get.freeze()
    intercept[IllegalArgumentException](configure())
    val groupDecl = declaration.routes.head.children.head.asInstanceOf[GroupDecl]
    assertEquals(groupDecl.path, "/users")
    assertEquals(groupDecl.tag.map(_.name), Some("users"))
    assertEquals(groupDecl.scope.inputs.size, 1)
    assertEquals(groupDecl.children.head.asInstanceOf[EndpointDecl].scope.inputs.size, 0)
    val resolved = Compiler.resolve(declaration)
    assertEquals(resolved.endpoints.map(_.spec.operationId), Vector("users.get", "users.admin.get"))
    assert(resolved.endpoints.forall(_.spec.inputs.head.key eq groupDecl.scope.inputs.head.key))
    assert(resolved.endpoints.forall(_.spec.responses.map(_.status).toSet == Set(200, 400, 500, 503)))
    val first = Application.build(resolved)
    val second = Application.build(Compiler.resolve(declaration))
    assertEquals(first.openApiJson, second.openApiJson)
    assertEquals(calls.get(), 0)
    for
      a <- first.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/users/get"))).flatMap(_.as[String])
      b <- second.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/users/get"))).flatMap(_.as[String])
    yield
      assertEquals(a, "default")
      assertEquals(b, "default")
      assertEquals(calls.get(), 2)
  }
