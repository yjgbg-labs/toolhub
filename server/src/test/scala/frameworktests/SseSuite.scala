package frameworktests

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import framework.rpcDsl.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scala.compiletime.testing.{typeCheckErrors, typeChecks}
import scala.concurrent.duration.*

class SseSuite extends CatsEffectSuite:
  private def request(path: String): Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString(path))

  test("SSE frames each JSON value and JSON string as one event and documents the wire media type") {
    val users = List(User(1, "Ada"), User(2, "空\nline"))
    val app = httpApp {
      command[SSE[User]]("users") { handle { IO.pure(SSE(Stream.emits(users).covary[IO])) } }
      command[SSE[String]]("strings") { handle { IO.pure(SSE(Stream.emit("quoted \"value\"\nnext").covary[IO])) } }
    }
    val schema = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths").downField("/users")
      .downField("get").downField("responses").downField("200").downField("content").downField("text/event-stream").downField("schema")
    assertEquals(schema.get[String]("type"), Right("string"))
    for
      response <- app.httpApp(request("/users"))
      bytes <- response.as[String]
      strings <- app.httpApp(request("/strings")).flatMap(_.as[String])
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(response.headers.get[`Content-Type`].map(_.mediaType), org.http4s.MediaType.parse("text/event-stream").toOption)
      assertEquals(response.contentLength, None)
      assert(response.headers.headers.exists(h => h.name.toString == "Cache-Control" && h.value == "no-cache"))
      assertEquals(bytes, users.map(user => s"data: ${JsonModel[User].encoder(user).noSpaces}\n\n").mkString)
      assertEquals(strings, s"data: ${Json.fromString("quoted \"value\"\nnext").noSpaces}\n\n")
  }

  test("PlainText SSE preserves empty data, UTF-8, multiline text and field-like content without injecting events") {
    val values = List("", "空\r\nline\rdata: literal\n\nevent: literal\n")
    val app = httpApp {
      command[SSE[PlainText]]("text") { handle { IO.pure(SSE(Stream.emits(values.map[PlainText](PlainText(_))).covary[IO])) } }
      command[SSE[Unit]]("empty") { handle { IO.pure(SSE(Stream.emit(()).covary[IO])) } }
    }
    for
      text <- app.httpApp(request("/text")).flatMap(_.as[String])
      empty <- app.httpApp(request("/empty")).flatMap(_.as[String])
    yield
      assertEquals(text, "data: \n\ndata: 空\ndata: line\ndata: data: literal\ndata: \ndata: event: literal\ndata: \n\n")
      assertEquals(empty, "data: \n\n")
  }

  test("SSE is lazy, delivers the first event before the tail and finalizes when consumption stops") {
    for
      opened <- Ref.of[IO, Int](0)
      closed <- Ref.of[IO, Int](0)
      app = httpApp {
        val source: Stream[IO, PlainText] = Stream.bracket(opened.update(_ + 1))(_ => closed.update(_ + 1)).flatMap { _ =>
          Stream.emit[IO, PlainText](PlainText("first")) ++ Stream.eval(IO.never[PlainText])
        }
        command[SSE[PlainText]]("events") { handle { IO.pure(SSE(source)) } }
      }
      response <- app.httpApp(request("/events"))
      before <- opened.get
      first <- response.body.take("data: first\n\n".length.toLong).through(fs2.text.utf8.decode).compile.string.timeout(3.seconds)
      after <- opened.get
      finalized <- closed.get
    yield
      assertEquals(before, 0)
      assertEquals(first, "data: first\n\n")
      assertEquals(after, 1)
      assertEquals(finalized, 1)
  }

  test("real HTTP streaming flushes an event from an unbounded source and releases it on disconnect") {
    Deferred[IO, Unit].flatMap { closed =>
      val source: Stream[IO, PlainText] = Stream.bracket(IO.unit)(_ => closed.complete(()).void).flatMap { _ =>
        Stream.emit[IO, PlainText](PlainText("first")) ++ Stream.awakeEvery[IO](50.millis).as[PlainText](PlainText("tick"))
      }
      val app = httpApp {
        server { port(0) }
        command[SSE[PlainText]]("events") { handle { IO.pure(SSE(source)) } }
      }
      (app.resource, EmberClientBuilder.default[IO].build).tupled.use { (server, client) =>
        val uri = Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}/events")
        // Close rather than draining an infinite response for connection reuse.
        val req = Request[IO](Method.GET, uri).putHeaders(Header.Raw(CIString("Connection"), "close"))
        client.run(req).use { response =>
          response.body.take("data: first\n\n".length.toLong).through(fs2.text.utf8.decode).compile.string
            .map(value => assertEquals(value, "data: first\n\n"))
        } *> closed.get
      }.timeout(10.seconds)
    }
  }

  test("recover handles errors before headers; stream failures terminate the body and run finalizers") {
    for
      finalized <- Ref.of[IO, Int](0)
      received <- Ref.of[IO, String]("")
      failure = new IllegalStateException("stream failed")
      app = httpApp {
        val rejected = response.text(422) {}
        recover { case _: IllegalStateException => rejected("recovered") }
        command[SSE[PlainText]]("before") { handle { IO.raiseError(failure) } }
        command[SSE[PlainText]]("during") {
          val source = (Stream.emit[IO, PlainText](PlainText("first")) ++ Stream.raiseError[IO](failure)).onFinalize(finalized.update(_ + 1))
          handle { IO.pure(SSE(source)) }
        }
      }
      before <- app.httpApp(request("/before"))
      beforeBody <- before.as[String]
      response <- app.httpApp(request("/during"))
      result <- response.body.through(fs2.text.utf8.decode).evalMap(text => received.update(_ + text)).compile.drain.attempt
      bytes <- received.get
      closed <- finalized.get
    yield
      assertEquals(before.status, Status.UnprocessableContent)
      assertEquals(beforeBody, "recovered")
      assertEquals(response.status, Status.Ok)
      assertEquals(result, Left(failure))
      assertEquals(bytes, "data: first\n\n")
      assertEquals(closed, 1)
  }

  test("PlainText is a String subtype with explicit construction; SSE requires encodable non-streaming elements") {
    assert(typeChecks("""
      import framework.rpcDsl.*
      val text: PlainText = PlainText("ok")
      val string: String = text
      summon[SuccessResponse[PlainText]]
      summon[SuccessResponse[SSE[PlainText]]]
      summon[SuccessResponse[SSE[String]]]
      summon[SuccessResponse[SSE[Int]]]
    """))
    assert(typeCheckErrors("""
      import framework.rpcDsl.*
      val text: PlainText = "requires explicit construction"
    """).nonEmpty)
    assert(typeCheckErrors("""
      import framework.rpcDsl.*
      summon[SuccessResponse[SSE[SSE[String]]]]
    """).nonEmpty)
  }
