package frameworktests

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.GraalCommandSdk
import io.circe.Json
import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import scala.compiletime.testing.typeCheckErrors

class AccessLogSuite extends CatsEffectSuite:
  private def request(path: String, method: Method = Method.GET): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))

  private def jsonRequest(path: String, body: String): Request[IO] =
    request(path, Method.POST).withEntity(body).putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))

  // Only this suite enables access logging. Capture the actual stdout sink, including
  // async fibers, and always restore it before reporting test results.
  private def captureWith[A](run: IO[Vector[Json]] => IO[A]): IO[(A, Vector[Json])] =
    Resource.make(IO {
      val previous = System.out
      val bytes = new ByteArrayOutputStream
      val stream = new PrintStream(bytes, true, UTF_8)
      System.setOut(stream)
      (previous, bytes, stream)
    }) { (previous, _, stream) => IO { System.setOut(previous); stream.close() } }.use { (_, bytes, _) =>
      val records = IO {
        bytes.toString(UTF_8).linesIterator.flatMap(line => io.circe.parser.parse(line).toOption)
          .filter(_.hcursor.get[String]("type") == Right("access")).toVector
      }
      run(records).flatMap(result => records.map(result -> _))
    }

  private def capture[A](run: IO[A]): IO[(A, Vector[Json])] = captureWith(_ => run)
  private def field(record: Json, name: String): String = record.hcursor.get[String](name).toOption.get
  private def params(record: Json): Json = record.hcursor.downField("params").focus.get

  test("accessLog is explicit, idempotent, frozen and restricted to the server scope") {
    var change: () => Unit = () => ()
    val quiet = httpApp { command[String]("read") { handle { IO.pure("ok") } } }
    val logged = httpApp {
      server { accessLog(); accessLog(); change = () => accessLog() }
      command[String]("read") { handle { IO.pure("ok") } }
    }
    assert(!quiet.serverSettings.accessLog)
    assert(logged.serverSettings.accessLog)
    intercept[IllegalArgumentException](change())
    assert(typeCheckErrors("""
      import framework.rpcDsl.*
      httpApp { accessLog() }
    """).nonEmpty)
    capture(quiet.httpApp(request("/read")) *> logged.httpApp(request("/read"))).map { (_, logs) =>
      assertEquals(logs.size, 1)
      assertEquals(field(logs.head, "command"), "read")
      assertEquals(field(logs.head, "source"), "http")
      assertEquals(logs.head.hcursor.get[Int]("status"), Right(200))
      assert(logs.head.hcursor.get[Long]("durationMs").toOption.get >= 0)
    }
  }

  test("late group and command skips include descendants and failures but not reopened siblings") {
    val app = httpApp {
      server { accessLog() }
      commands("quiet") {
        commands("nested") {
          command[String]("read") {
            val id = param[Int]("id", "ID")
            handle { IO.pure(id.value.toString) }
          }
          command[Int]("write") {
            val input = body[Int]("Input")
            handle { IO.pure(input.value) }
          }
          command[String]("fail") { handle { IO.raiseError(new IllegalArgumentException("access-log skipped failure")) } }
        }
        logSkip()
      }
      commands("quiet") { command[String]("visible") { handle { IO.pure("visible") } } }
      command[String]("health") { handle { IO.pure("ok") }; logSkip() }
    }
    ExpectedRequestErrors("access-log skipped failure") { capture(for
      ok <- app.httpApp(request("/quiet/nested/read?id=1"))
      bad <- app.httpApp(request("/quiet/nested/read?id=bad"))
      wrong <- app.httpApp(request("/quiet/nested/read", Method.POST))
      body <- app.httpApp(jsonRequest("/quiet/nested/write", "{"))
      failed <- app.httpApp(request("/quiet/nested/fail"))
      _ <- app.httpApp(request("/health"))
      _ <- app.httpApp(request("/quiet/visible"))
    yield List(ok, bad, wrong, body, failed).map(_.status.code)) }.map { (statuses, logs) =>
      assertEquals(statuses, List(200, 400, 405, 400, 500))
      assertEquals(logs.map(field(_, "command")), Vector("quiet.visible"))
    }
  }

  test("parameter scope wins over command scope and inherited skipped parameters never appear") {
    var change: () => Unit = () => ()
    val app = httpApp {
      server { accessLog() }
      commands("users") {
        val token = param[String]("token", "Token") { logSkip(); change = () => logSkip() }
        command[String]("get") {
          val id = param[Int]("id", "ID")
          val password = param[String]("password", "Password") { minLength(8); logSkip() }
          val tags = param[List[String]]("tag", "Tags")
          val limit = param[Int]("limit", "Limit") { default(10) }
          handle { IO.pure(s"${id.value}:${password.value}:${token.value}:${tags.value}:${limit.value}") }
        }
      }
    }
    intercept[IllegalArgumentException](change())
    capture(for
      ok <- app.httpApp(request("/users/get?id=7&token=token-secret&password=password-secret&tag=a&tag=b&extra=unknown-secret"))
        .flatMap(_.as[String])
      invalid <- app.httpApp(request("/users/get?id=bad&token=token-secret&password=short"))
    yield (ok, invalid.status)).map { case ((body, status), logs) =>
      assert(body.contains("password-secret"))
      assertEquals(status, Status.BadRequest)
      assertEquals(logs.size, 2)
      assertEquals(params(logs.head), Json.obj(
        "query.id" -> Json.fromString("7"),
        "query.tag" -> Json.arr(Json.fromString("a"), Json.fromString("b")),
        "query.limit" -> Json.fromInt(10)
      ))
      val text = logs.map(_.noSpaces).mkString
      List("token-secret", "password-secret", "unknown-secret", "short").foreach(value => assert(!text.contains(value), text))
      assertEquals(field(logs.head, "path"), "/users/get")
    }
  }

  test("JSON and text bodies can be skipped without hiding the request, including decoding failures") {
    val app = httpApp {
      server { accessLog() }
      command[Int]("number") {
        val input = body[Int]("Number") { logSkip() }
        handle { IO.pure(input.value) }
      }
      command[String]("text") {
        val input = body.text("Text") { logSkip() }
        handle { IO.pure(input.value) }
      }
    }
    capture(for
      _ <- app.httpApp(jsonRequest("/number", "123456"))
      _ <- app.httpApp(jsonRequest("/number", "\"invalid-secret\""))
      _ <- app.httpApp(request("/text", Method.POST).withEntity("text-secret")
        .putHeaders(Header.Raw(CIString("Content-Type"), "text/plain")))
    yield ()).map { (_, logs) =>
      assertEquals(logs.size, 3)
      logs.foreach(log => assertEquals(params(log), Json.obj()))
    }
  }

  test("HTTP path/header/cookie skips and root skips work with frozen declarations") {
    import framework.httpDsl.*
    val app = httpApp {
      server { accessLog() }
      routes {
        get("/users/{secret}") {
          val secret = path[String]("secret") { logSkip() }
          val token = header[String]("X-Token") { logSkip() }
          val session = cookie[String]("session") { logSkip() }
          val id = query[String]("id")
          val ok = response.text(200) {}
          handle { IO.pure(ok(secret.value + token.value + session.value + id.value)) }
        }
      }
      routes {
        get("/quiet") { val ok = response.empty(204) {}; handle { IO.pure(ok(())) } }
        logSkip()
      }
    }
    capture(for
      body <- app.httpApp(request("/users/path-secret?id=7").putHeaders(
        Header.Raw(CIString("X-Token"), "header-secret"),
        Header.Raw(CIString("Cookie"), "session=cookie-secret")
      )).flatMap(_.as[String])
      _ <- app.httpApp(request("/quiet"))
    yield body).map { (body, logs) =>
      assert(body.contains("path-secretheader-secretcookie-secret"))
      assertEquals(logs.size, 1)
      assertEquals(field(logs.head, "path"), "/users/{secret}")
      assertEquals(params(logs.head), Json.obj("query.id" -> Json.fromString("7")))
      assert(!logs.head.noSpaces.contains("-secret"))
    }
  }

  test("errors, docs, HEAD and CORS preflights each log once and preserve responses") {
    val app = httpApp {
      server { accessLog(); maxBodyBytes(16); cors {} }
      command[Int]("number") {
        val input = body[Int]("Number")
        handle { IO.pure(input.value) }
      }
      command[String]("fail") { handle { IO.raiseError(new IllegalArgumentException("access-log failure")) } }
      commands("quiet") { logSkip(); command[String]("read") { handle { IO.pure("ok") } } }
    }
    def preflight(path: String) = request(path, Method.OPTIONS).putHeaders(
      Header.Raw(CIString("Origin"), "https://example.test"),
      Header.Raw(CIString("Access-Control-Request-Method"), "GET")
    )
    ExpectedRequestErrors("access-log failure") { capture(for
      a <- app.httpApp(jsonRequest("/number", "{"))
      b <- app.httpApp(jsonRequest("/number", "1" * 17))
      c <- app.httpApp(request("/number", Method.POST).withEntity("1"))
      d <- app.httpApp(request("/missing?secret=unknown-secret"))
      e <- app.httpApp(request("/number"))
      f <- app.httpApp(request("/fail").putHeaders(Header.Raw(CIString("Origin"), "https://example.test")))
      g <- app.httpApp(request("/openapi.json"))
      h <- app.httpApp(request("/openapi.json", Method.HEAD))
      empty <- h.as[String]
      i <- app.httpApp(preflight("/fail"))
      _ <- app.httpApp(preflight("/quiet/read"))
    yield (List(a, b, c, d, e, f, g, h, i).map(_.status.code), empty, f)) }.map { case ((statuses, empty, failure), logs) =>
      assertEquals(statuses, List(400, 413, 415, 404, 405, 500, 200, 200, 200))
      assertEquals(logs.flatMap(_.hcursor.get[Int]("status").toOption).toList, statuses)
      assertEquals(logs.map(field(_, "requestId")).distinct.size, logs.size)
      assertEquals(empty, "")
      assert(failure.headers.headers.exists(_.name == CIString("Access-Control-Allow-Origin")))
      assert(!logs.map(_.noSpaces).mkString.contains("unknown-secret"))
      assertEquals(field(logs(5), "outcome"), "error")
    }
  }

  test("in-process GraalJS uses the same policy and emits one record per command") {
    val app = httpApp {
      server { accessLog() }
      command[String]("read") {
        exposeOn(graaljs)
        val token = param[String]("token", "Token") { logSkip() }
        handle { IO.pure(token.value) }
      }
      command[String]("quiet") { logSkip(); handle { IO.pure("quiet") } }
    }
    val sdk = GraalCommandSdk(app)
    capture(sdk.call("read", Json.obj("token" -> Json.fromString("sdk-secret"))) *> sdk.call("quiet")).map { (_, logs) =>
      assertEquals(logs.size, 1)
      assertEquals(field(logs.head, "source"), "in_process")
      assertEquals(field(logs.head, "command"), "read")
      assertEquals(params(logs.head), Json.obj())
    }
  }

  test("root skip reaches all commands but does not hide the independent OpenAPI endpoint") {
    val app = httpApp {
      server { accessLog() }
      command[String]("root") { handle { IO.pure("root") } }
      commands("nested") { command[String]("read") { handle { IO.pure("nested") } } }
      logSkip()
    }
    capture(for
      _ <- app.httpApp(request("/root"))
      _ <- app.httpApp(request("/nested/read"))
      _ <- app.httpApp(request("/openapi.json"))
    yield ()).map { (_, logs) =>
      assertEquals(logs.size, 1)
      assertEquals(field(logs.head, "path"), "/openapi.json")
      assertEquals(logs.head.hcursor.downField("command").focus, Some(Json.Null))
    }
  }

  test("logging follows actual route priority, method policy and OpenAPI dispatch") {
    import framework.httpDsl.*
    val app = httpApp {
      server { accessLog(); cors {} }
      routes {
        get("/{secret}") {
          logSkip()
          val secret = path[String]("secret")
          val ok = response.text(200) {}
          handle { IO.pure(ok(secret.value)) }
        }
        get("/same") {
          logSkip()
          val ok = response.text(200) {}
          handle { IO.pure(ok("get")) }
        }
        post("/same") {
          val ok = response.text(200) {}
          handle { IO.pure(ok("post")) }
        }
      }
    }
    def preflight(method: String) = request("/same", Method.OPTIONS).putHeaders(
      Header.Raw(CIString("Origin"), "https://example.test"),
      Header.Raw(CIString("Access-Control-Request-Method"), method)
    )
    capture(for
      _ <- app.httpApp(request("/path-secret"))
      _ <- app.httpApp(request("/same"))
      _ <- app.httpApp(request("/same", Method.POST))
      _ <- app.httpApp(request("/same", Method.DELETE))
      _ <- app.httpApp(preflight("GET"))
      _ <- app.httpApp(preflight("POST").withBodyStream(fs2.Stream.raiseError[IO](new Exception("must not read"))))
      docs <- app.httpApp(request("/openapi.json"))
      _ <- app.httpApp(request("/openapi.json").putHeaders(docs.headers.headers.filter(_.name == CIString("ETag"))
        .map(header => Header.Raw(CIString("If-None-Match"), header.value))*))
    yield ()).map { (_, logs) =>
      assertEquals(logs.flatMap(_.hcursor.get[Int]("status").toOption), Vector(200, 405, 200, 200, 304))
      assertEquals(logs.take(3).map(field(_, "command")).distinct, Vector("post_same"))
      logs.drop(3).foreach(log => assertEquals(log.hcursor.downField("command").focus, Some(Json.Null)))
    }
  }

  test("stream open/close is lazy and preserves bytes, failures and cancellation") {
    for
      reads <- Ref.of[IO, Int](0)
      waiting <- Deferred[IO, Unit]
      app = httpApp {
        server { accessLog() }
        command[SSE[Int]]("stream") {
          handle { IO.pure(SSE(fs2.Stream.eval(reads.update(_ + 1).as(42)))) }
        }
        command[SSE[Int]]("broken") {
          handle { IO.pure(SSE(fs2.Stream.raiseError[IO](new IllegalStateException("stream-secret")))) }
        }
        command[SSE[Int]]("wait") {
          handle { IO.pure(SSE(fs2.Stream.eval(waiting.complete(()) *> IO.never[Int]))) }
        }
      }
      captured <- captureWith { logs => for
        response <- app.httpApp(request("/stream"))
        opened <- logs
        before <- reads.get
        text <- response.as[String]
        _ <- app.httpApp(request("/broken")).flatMap(_.body.compile.drain).attempt
        pending <- app.httpApp(request("/wait")).flatMap(_.body.compile.drain).start
        _ <- waiting.get
        _ <- pending.cancel
      yield (opened, before, text) }
    yield
      val ((opened, before, text), logs) = captured
      assertEquals(opened.map(field(_, "event")), Vector("stream_open"))
      assertEquals(before, 0)
      assert(text.contains("42"))
      assertEquals(logs.map(field(_, "event")), Vector.fill(3)(Vector("stream_open", "stream_close")).flatten)
      assertEquals(logs.filter(field(_, "event") == "stream_close").map(field(_, "outcome")), Vector("completed", "error", "canceled"))
      assertEquals(logs.take(2).map(field(_, "requestId")).distinct.size, 1)
      assert(!logs.map(_.noSpaces).mkString.contains("stream-secret"))
  }

  test("cancellation before a response emits one terminal record") {
    for
      entered <- Deferred[IO, Unit]
      app = httpApp {
        server { accessLog() }
        command[String]("wait") { handle { entered.complete(()) *> IO.never[String] } }
      }
      captured <- capture(for
        fiber <- app.httpApp(request("/wait")).start
        _ <- entered.get
        _ <- fiber.cancel
      yield ())
    yield
      val (_, logs) = captured
      assertEquals(logs.size, 1)
      assertEquals(field(logs.head, "outcome"), "canceled")
      assertEquals(logs.head.hcursor.downField("status").focus, Some(Json.Null))
  }

  test("concurrent requests stay separate and oversized inputs produce bounded JSON lines") {
    val app = httpApp {
      server { accessLog() }
      command[String]("echo") {
        val id = param[String]("id", "ID")
        val input = body.text("Text")
        handle { IO.pure(id.value + input.value) }
      }
    }
    val text = ("😀\n\"" * 3000)
    capture((1 to 20).toList.parTraverse { id =>
      app.httpApp(request(s"/echo?id=$id", Method.POST).withEntity(text)
        .putHeaders(Header.Raw(CIString("Content-Type"), "text/plain"))).flatMap(_.as[String])
    }).map { (bodies, logs) =>
      assertEquals(bodies.size, 20)
      assertEquals(logs.size, 20)
      assertEquals(logs.map(field(_, "requestId")).distinct.size, 20)
      assertEquals(logs.map(log => params(log).hcursor.get[String]("query.id").toOption.get).toSet, (1 to 20).map(_.toString).toSet)
      logs.foreach { log =>
        assert(log.noSpaces.getBytes(UTF_8).length <= 8192)
        assertEquals(params(log).hcursor.downField("body").get[Boolean]("truncated"), Right(true))
      }
    }
  }

  test("the complete record is bounded even with many individually small inputs") {
    val app = httpApp {
      server { accessLog() }
      command[String]("many") {
        (1 to 30).foreach(i => param[String](s"field$i", "Field"))
        handle { IO.pure("ok") }
      }
    }
    val query = (1 to 30).map(i => s"field$i=${"x" * 1000}").mkString("&")
    capture(app.httpApp(request(s"/many?$query"))).map { (response, logs) =>
      assertEquals(response.status, Status.Ok)
      assertEquals(logs.size, 1)
      assert(logs.head.noSpaces.getBytes(UTF_8).length <= 8192)
      assertEquals(logs.head.hcursor.get[Boolean]("paramsTruncated"), Right(true))
    }
  }

  test("Ember emits the same stdout access records for real HTTP requests") {
    val app = httpApp {
      server { port(0); accessLog() }
      command[String]("read") {
        val token = param[String]("token", "Token") { logSkip() }
        handle { IO.pure(token.value) }
      }
    }
    capture((app.resource, EmberClientBuilder.default[IO].build).tupled.use { (server, client) =>
      client.expect[String](s"http://127.0.0.1:${server.address.getPort}/read?token=network-secret")
    }).map { (body, logs) =>
      assert(body.contains("network-secret"))
      assertEquals(logs.size, 1)
      assertEquals(field(logs.head, "command"), "read")
      assertEquals(params(logs.head), Json.obj())
      assert(!logs.head.noSpaces.contains("network-secret"))
    }
  }
