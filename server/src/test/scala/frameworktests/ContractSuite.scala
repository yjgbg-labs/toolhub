package frameworktests

import cats.effect.IO
import munit.FunSuite
import framework.httpDsl.*
import scala.compiletime.testing.typeCheckErrors

class ContractSuite extends FunSuite:
  test("missing handlers, inputs, and responses fail during configuration") {
    interceptMessage[IllegalArgumentException]("requirement failed: Missing handler: GET /") {
      httpApp { routes { get("/") { response.text(200) {} } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes { get("/{id}") { val ok = response.text(200) {}; handle { IO.pure(ok("ok")) } } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes { get("/") { handle { throw new RuntimeException("not executed") } } } }
    }
  }

  test("ambiguous routes, duplicate operation IDs and reserved documentation paths fail at startup") {
    intercept[IllegalArgumentException] {
      httpApp {
        routes {
          get("/") { val ok = response.text(200) {}; handle { IO.pure(ok("first")) } }
          get("/") { val ok = response.text(200) {}; handle { IO.pure(ok("second")) } }
        }
      }
    }
    intercept[IllegalArgumentException] {
      httpApp {
        routes {
          get("/{id}") { val id = path[Int]("id"); val ok = response.json[Int](200) {}; handle { IO.pure(ok(id.value)) } }
          post("/{name}") { val name = path[String]("name"); val ok = response.text(200) {}; handle { IO.pure(ok(name.value)) } }
        }
      }
    }
    intercept[IllegalArgumentException] {
      httpApp {
        routes {
          get("/a") { operationId("same"); val ok = response.text(200) {}; handle { IO.pure(ok("a")) } }
          get("/b") { operationId("same"); val ok = response.text(200) {}; handle { IO.pure(ok("b")) } }
        }
      }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes { get("/openapi.json") { val ok = response.text(200) {}; handle { IO.pure(ok("bad")) } } } }
    }
  }

  test("invalid defaults, optional path inputs, duplicate inputs and illegal response bodies are rejected") {
    intercept[IllegalArgumentException] {
      httpApp { routes { get("/") {
        val n = query[Int]("n") { default(0); minimum(1) }
        val ok = response.json[Int](200) {}
        handle { IO.pure(ok(n.value)) }
      } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes { get("/{id}") {
        val id = path[Option[Int]]("id")
        val ok = response.json[Option[Int]](200) {}
        handle { IO.pure(ok(id.value)) }
      } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes { get("/") {
        header[Int]("X-Test")
        header[String]("x-test")
        val ok = response.text(200) {}
        handle { IO.pure(ok("ok")) }
      } } }
    }
    intercept[IllegalArgumentException] {
      httpApp { routes { get("/") { val ok = response.json[String](204) {}; handle { IO.pure(ok("invalid")) } } } }
    }
  }

  test("value access requires a request context at compile time") {
    val errors = typeCheckErrors("""
      import framework.httpDsl.*
      httpApp {
        routes {
          get("/{id}") {
            val id = path[Int]("id")
            val invalid = id.value
            val ok = response.json[Int](200) {}
            handle { IO.pure(ok(id.value)) }
          }
        }
      }
    """)
    assert(errors.exists(_.message.contains("RequestContext")), errors.toString)
  }

  test("HTTP paths are absolute and the DSL has no path-group abstraction") {
    val errors = typeCheckErrors("""
      import framework.httpDsl.*
      httpApp { routes { group("/users") {} } }
    """)
    assert(errors.exists(_.message.contains("group")), errors.toString)
    List("", "users", "users/get").foreach { value =>
      interceptMessage[IllegalArgumentException](s"requirement failed: Path must start with '/': $value") {
        httpApp { routes { get(value) {
          operationId("users.get")
          val ok = response.text(200) {}
          handle { IO.pure(ok("ok")) }
        } } }
      }
    }
  }

  test("OpenAPI-reserved headers cannot silently become ignored parameters") {
    intercept[IllegalArgumentException] {
      httpApp { routes { get("/") {
        val token = header[String]("Authorization")
        val ok = response.text(200) {}
        handle { IO.pure(ok(token.value)) }
      } } }
    }
  }

  test("parameter defaults and response values remain statically typed") {
    val defaultErrors = typeCheckErrors("""
      import framework.httpDsl.*
      httpApp { routes { get("/") {
        val n = query[Int]("n") { default("wrong") }
        val ok = response.json[Int](200) {}
        handle { IO.pure(ok(n.value)) }
      } } }
    """)
    val responseErrors = typeCheckErrors("""
      import framework.httpDsl.*
      httpApp { routes { get("/") {
        val ok = response.json[Int](200) {}
        handle { IO.pure(ok("wrong")) }
      } } }
    """)
    val constraintErrors = typeCheckErrors("""
      import framework.httpDsl.*
      httpApp { routes { get() {
        val n = query[Int]("n") {
          constraint[String]("Must not be empty")(_.nonEmpty)
        }
        val ok = response.json[Int](200) {}
        handle { IO.pure(ok(n.value)) }
      } } }
    """)
    assert(defaultErrors.nonEmpty)
    assert(responseErrors.nonEmpty)
    assert(constraintErrors.exists(_.message.contains("InputScope")), constraintErrors.toString)
  }
