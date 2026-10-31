package groot.fetch

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager}
import fs2.{Chunk, Stream}
import io.circe.Json
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.zip.{DeflaterOutputStream, GZIPOutputStream}
import munit.CatsEffectSuite
import org.http4s.{Header, HttpApp, Method, Request, Response, Status, Uri}
import org.http4s.client.Client
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class FetchSuite extends CatsEffectSuite:
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "fetch-test-"))
  )(path => IO.blocking {
    val files = Files.walk(path)
    try files.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    finally files.close()
  })

  private def awaitUntil[A](action: IO[A])(check: A => Boolean): IO[A] =
    def loop: IO[A] = action.flatMap(value => if check(value) then IO.pure(value) else IO.sleep(10.millis) *> IO.defer(loop))
    loop.timeout(10.seconds)

  private def header(name: String, value: String): Header.Raw = Header.Raw(CIString(name), value)
  private def serve(app: HttpApp[IO]): Resource[IO, String] = EmberServerBuilder.default[IO]
    .withHost(ipv4"127.0.0.1").withPort(port"0").withHttpApp(app).build.map(s => s"http://127.0.0.1:${s.address.getPort}")
  private def sdk(service: FetchService): GraalCommandSdk = GraalCommandSdk(httpApp { FetchRoutes.register(service) })
  private def call(sdk: GraalCommandSdk, request: FetchRequest): IO[Json] =
    sdk.call("fetch", JsonModel[FetchRequest].encoder(request))
  private def fetch(sdk: GraalCommandSdk, request: FetchRequest): IO[FetchResponse] = call(sdk, request).flatMap { result =>
    assertEquals(result.hcursor.get[Int]("status"), Right(200), result.noSpaces)
    IO.fromEither(JsonModel[FetchResponse].decoder.decodeJson(result.hcursor.downField("body").focus.get))
  }
  private def bytes(response: FetchResponse): Array[Byte] = Base64.getDecoder.decode(response.bodyBase64)
  private def text(response: FetchResponse): String = new String(bytes(response), UTF_8)
  private def json(response: FetchResponse): Json = io.circe.parser.parse(text(response).stripPrefix("\ufeff")).toOption.get
  private def compress(bytes: Array[Byte], gzip: Boolean): Array[Byte] =
    val out = new ByteArrayOutputStream
    val encoder = if gzip then new GZIPOutputStream(out) else new DeflaterOutputStream(out)
    encoder.write(bytes)
    encoder.close()
    out.toByteArray
  private def run(manager: GraalManager, source: String): IO[Unit] = for
    submitted <- manager.start(source, "5s", "10s")
    task <- awaitUntil(manager.status(submitted.id))(_.terminal)
    log <- manager.logs(task.id).map(_.toString)
  yield assertEquals(task.state, "succeeded", s"${task.error}\n$log")

  private val echo = HttpApp[IO] { request => request.body.compile.to(Array).map { body =>
    val values = request.headers.headers.groupMap(_.name.toString.toLowerCase(java.util.Locale.ROOT))(_.value)
    val json = Json.obj("method" -> Json.fromString(request.method.name), "text" -> Json.fromString(new String(body, UTF_8)),
      "bytes" -> Json.fromValues(body.map(b => Json.fromInt(b & 0xff))),
      "headers" -> Json.fromFields(values.map((k, v) => k -> Json.fromString(v.mkString(", ")))))
    Response[IO](Status.Ok).withEntity(json.noSpaces).putHeaders(header("Content-Type", "application/json"), header("X-Reply", "yes"))
  } }

  test("the declared command preserves UTF-8, binary bodies, response metadata and request framing over HTTP") {
    (serve(echo), FetchService.resource).tupled.use { (url, service) =>
      val commands = sdk(service)
      for
        response <- fetch(commands, FetchRequest(s"$url/echo?x=1", "post", Map("X-Test" -> "value"), Some("中文🙂")))
        _ = assertEquals((response.status, response.statusText, response.ok, response.redirected, response.url),
          (200, "OK", true, false, s"$url/echo?x=1"))
        _ = assertEquals(response.headers("x-reply"), "yes")
        _ = assertEquals(response.setCookies, Nil)
        first = json(response).hcursor
        _ = assertEquals(first.get[String]("text"), Right("中文🙂"))
        _ = assertEquals(first.get[String]("method"), Right("POST"))
        _ = assertEquals(first.downField("headers").get[String]("x-test"), Right("value"))
        _ = assertEquals(first.downField("headers").get[String]("content-type"), Right("text/plain;charset=UTF-8"))
        binary <- fetch(commands, FetchRequest(url, "PUT", Map("x-duplicate" -> "one", "X-Duplicate" -> "two",
          "Content-Length" -> "999", "Transfer-Encoding" -> "chunked"), Some("AH+A/w=="), BodyEncoding.base64))
        data = json(binary).hcursor
        _ = assertEquals(data.get[List[Int]]("bytes"), Right(List(0, 127, 128, 255)))
        _ = assertEquals(data.downField("headers").get[String]("x-duplicate").map(_.split(", ").toSet), Right(Set("one", "two")))
        _ = assertEquals(data.downField("headers").get[String]("content-length"), Right("4"))
        _ = assert(!data.downField("headers").downField("content-type").succeeded)
        _ = assert(!data.downField("headers").downField("transfer-encoding").succeeded)
        reused <- fetch(commands, FetchRequest(url, headers = response.headers))
      yield assertEquals(json(reused).hcursor.downField("headers").get[String]("x-reply"), Right("yes"))
    }
  }

  test("redirects resolve relative URLs, rewrite POST, retain 307 bodies, strip cross-origin credentials and reject loops") {
    serve(echo).use { target =>
      val app = HttpApp[IO] { request => request.uri.path.renderString match
        case "/post" => IO.pure(Response[IO](Status.Found).putHeaders(header("Location", "./echo")))
        case "/keep" => IO.pure(Response[IO](Status.TemporaryRedirect).putHeaders(header("Location", "/echo")))
        case "/cross" => IO.pure(Response[IO](Status.Found).putHeaders(header("Location", target)))
        case "/loop" => IO.pure(Response[IO](Status.Found).putHeaders(header("Location", "/loop")))
        case _ => echo(request)
      }
      (serve(app), FetchService.resource).tupled.use { (url, service) =>
        val commands = sdk(service)
        for
          post <- fetch(commands, FetchRequest(s"$url/post", "POST", Map("Content-Type" -> "application/json"), Some("data")))
          _ = assert(post.redirected)
          _ = assertEquals(post.url, s"$url/echo")
          _ = assertEquals(json(post).hcursor.get[String]("method"), Right("GET"))
          _ = assertEquals(json(post).hcursor.get[String]("text"), Right(""))
          _ = assert(!json(post).hcursor.downField("headers").downField("content-type").succeeded)
          kept <- fetch(commands, FetchRequest(s"$url/keep", "POST", body = Some("keep")))
          _ = assertEquals(json(kept).hcursor.get[String]("method"), Right("POST"))
          _ = assertEquals(json(kept).hcursor.get[String]("text"), Right("keep"))
          cross <- fetch(commands, FetchRequest(s"$url/cross", headers = Map("Authorization" -> "Bearer secret", "Cookie" -> "a=b", "X-Keep" -> "safe")))
          headers = json(cross).hcursor.downField("headers")
          _ = assert(!headers.downField("authorization").succeeded && !headers.downField("cookie").succeeded)
          _ = assertEquals(headers.get[String]("x-keep"), Right("safe"))
          loop <- call(commands, FetchRequest(s"$url/loop"))
        yield
          assertEquals(loop.hcursor.get[Int]("status"), Right(400))
          assert(loop.hcursor.downField("body").get[String]("message").toOption.get.contains("20 redirects"))
      }
    }
  }

  test("HTTP errors and empty responses remain data, gzip/deflate decode, and binary bytes are preserved") {
    def compressed(gzip: Boolean): Array[Byte] = compress("\ufeff{\"text\":\"中文\"}".getBytes(UTF_8), gzip)
    val app = HttpApp[IO] { request => IO.pure(request.uri.path.renderString match
      case "/missing" => Response[IO](Status.NotFound).withEntity("missing")
      case "/empty" => Response[IO](Status.NoContent)
      case "/binary" => Response[IO](Status.Ok).withBodyStream(Stream.chunk(Chunk.array(Array[Byte](0, 127, -128, -1))))
      case path => Response[IO](Status.Ok).withBodyStream(Stream.chunk(Chunk.array(compressed(path == "/gzip"))))
        .putHeaders(header("Content-Encoding", if path == "/gzip" then "gzip" else "deflate"))) }
    (serve(app), FetchService.resource).tupled.use { (url, service) =>
      val commands = sdk(service)
      for
        missing <- fetch(commands, FetchRequest(s"$url/missing"))
        _ = assertEquals((missing.status, missing.ok, text(missing)), (404, false, "missing"))
        empty <- fetch(commands, FetchRequest(s"$url/empty"))
        _ = assertEquals((empty.status, empty.bodyBase64), (204, ""))
        _ <- List("gzip", "deflate").traverse_ { encoding => fetch(commands, FetchRequest(s"$url/$encoding")).map { response =>
          assertEquals(json(response).hcursor.get[String]("text"), Right("中文"))
          assertEquals(response.headers("content-encoding"), encoding)
        } }
        binary <- fetch(commands, FetchRequest(s"$url/binary"))
        _ = assertEquals(bytes(binary).toVector, Vector[Byte](0, 127, -128, -1))
        head <- fetch(commands, FetchRequest(s"$url/gzip", "HEAD"))
      yield assertEquals(head.bodyBase64, "")
    }
  }

  test("content-coding lists and repeated headers decode in reverse order over HTTP") {
    val plain = "中文🙂 layered response".getBytes(UTF_8)
    val gzip = compress(plain, gzip = true)
    val deflate = compress(plain, gzip = false)
    val cases = List(
      (compress(gzip, gzip = true), List("gzip, gzip")),
      (compress(gzip, gzip = true), List("gzip", "gzip")),
      (compress(gzip, gzip = false), List(" GZip , DEFLATE ")),
      (compress(deflate, gzip = true), List("deflate", "gzip")),
      (compress(compress(gzip, gzip = false), gzip = true), List("gzip, deflate", "x-gzip"))
    )
    val app = HttpApp[IO] { request =>
      val (encoded, encodings) = cases(request.uri.path.renderString.drop(1).toInt)
      IO.pure(Response[IO](Status.Ok).withBodyStream(Stream.chunk(Chunk.array(encoded)))
        .withHeaders(org.http4s.Headers(encodings.map(header("Content-Encoding", _)))))
    }
    (serve(app), FetchService.resource).tupled.use { (url, service) =>
      val commands = sdk(service)
      cases.indices.toList.traverse_ { index =>
        fetch(commands, FetchRequest(s"$url/$index")).map { response =>
          assertEquals(bytes(response).toVector, plain.toVector, s"case $index")
        }
      }
    }
  }

  test("layered decoding enforces the decoded size limit and leaves unsupported chains intact") {
    val large = compress(compress(Array.fill[Byte](FetchService.maxBytes + 1)(65), gzip = true), gzip = false)
    val opaque = Array[Byte](0, 127, -128, -1)
    Ref.of[IO, Int](0).flatMap { active =>
      val client = Client[IO](request =>
        val (body, encodings) = request.uri.path.renderString match
          case "/large" => (large, List("gzip", "deflate"))
          case "/broken" => (compress(opaque, gzip = true), List("deflate, gzip"))
          case "/unknown-list" => (opaque, List("gzip, br"))
          case _ => (opaque, List("gzip", "br"))
        Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).as(
          Response[IO](Status.Ok).withBodyStream(Stream.chunk(Chunk.array(body)))
            .withHeaders(org.http4s.Headers(encodings.map(header("Content-Encoding", _)))))
      )
      val commands = sdk(new FetchService(client))
      for
        result <- call(commands, FetchRequest("http://example.com/large"))
        _ = assertEquals(result.hcursor.get[Int]("status"), Right(413))
        broken <- call(commands, FetchRequest("http://example.com/broken"))
        _ = assertEquals(broken.hcursor.get[Int]("status"), Right(400))
        _ <- List("unknown-list", "unknown-fields").traverse_ { path =>
          fetch(commands, FetchRequest(s"http://example.com/$path")).map { response =>
            assertEquals(bytes(response).toVector, opaque.toVector)
            assertEquals(response.headers("content-encoding"), "gzip, br")
          }
        }
        count <- active.get
      yield assertEquals(count, 0)
    }
  }

  test("setCookies preserves response order and comma-containing values through HTTP and GraalJS") {
    val cookies = List("session=first; Expires=Wed, 21 Oct 2037 07:28:00 GMT; Path=/; HttpOnly",
      "session=second; Path=/api; SameSite=Lax", "other=value; extension=a, b=c")
    val app = HttpApp[IO] { request => request.bodyText.compile.string.map { body =>
      Response[IO](Status.Ok).withEntity(body).putHeaders(
        header("Set-Cookie", cookies(0)), header("set-cookie", cookies(1)), header("sEt-CoOkIe", cookies(2)),
        header("X-Value", "one"), header("x-value", "two"))
    } }
    (serve(app), FetchService.resource).tupled.use { (url, service) =>
      val commands = sdk(service)
      for
        response <- fetch(commands, FetchRequest(url))
        _ = assertEquals(response.setCookies, cookies)
        _ = assertEquals(response.headers("set-cookie"), cookies.mkString(", "))
        _ = assertEquals(response.headers("x-value"), "one, two")
        _ <- temporary.use { home => GraalManager.resource(home, IO.pure(commands)).use { manager => run(manager, s"""
          const result = await tools.fetch({url: "$url", method: "POST", body: "hello"});
          if (result.body.bodyBase64 !== "aGVsbG8=") throw Error("body-only command input");
          const expected = ${Json.fromValues(cookies.map(Json.fromString)).noSpaces};
          if (JSON.stringify(result.body.setCookies) !== JSON.stringify(expected)) throw Error("cookie boundaries");
        """) } }
      yield ()
    }
  }

  test("command validation and network failures have structured errors") {
    val client = Client[IO](_ => Resource.eval(IO.raiseError(new java.io.IOException("connection failed"))))
    val commands = sdk(new FetchService(client))
    val invalid = List(FetchRequest("relative"), FetchRequest("file:///tmp/file"), FetchRequest("ftp://example.com"),
      FetchRequest("http://user:pass@example.com"), FetchRequest("http://example.com", body = Some("")),
      FetchRequest("http://example.com", "CONNECT"), FetchRequest("http://example.com", headers = Map("bad name" -> "x")),
      FetchRequest("http://example.com", headers = Map("x-bad" -> "injected\r\nheader")),
      FetchRequest("http://example.com", "POST", body = Some("!"), bodyEncoding = BodyEncoding.base64), FetchRequest("http://example.com"))
    invalid.traverse_ { request => call(commands, request).map { result =>
      assertEquals(result.hcursor.get[Int]("status"), Right(400))
      assertEquals(result.hcursor.downField("body").get[String]("name"), Right("TypeError"))
    } }
  }

  test("transport/SDK size limits and deadlines release the streamed response resource") {
    Ref.of[IO, Int](0).flatMap { active =>
      val client = Client[IO](request => Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).as(
        Response[IO](Status.Ok).withBodyStream(request.uri.path.renderString match
          case "/large" => Stream.chunk(Chunk.array(Array.fill[Byte](FetchService.maxBytes + 1)(65)))
          case "/encoded" => Stream.chunk(Chunk.array(Array.fill[Byte](FetchService.maxBytes * 3 / 4)(65)))
          case _ => Stream.eval(IO.never[Byte]))))
      val commands = sdk(new FetchService(client, 150.millis))
      for
        large <- call(commands, FetchRequest("http://example.com/large"))
        _ = assertEquals(large.hcursor.get[Int]("status"), Right(413))
        encoded <- call(commands, FetchRequest("http://example.com/encoded")).attempt
        _ = assert(encoded.swap.toOption.exists(_.getMessage.contains("1 MiB")))
        slow <- call(commands, FetchRequest("http://example.com/slow"))
        _ = assertEquals(slow.hcursor.get[Int]("status"), Right(504))
        input <- call(commands, FetchRequest("http://example.com", "POST", body = Some("x" * (FetchService.maxBytes + 1))))
        _ = assertEquals(input.hcursor.get[Int]("status"), Right(413))
        count <- active.get
      yield assertEquals(count, 0)
    }
  }

  test("the generic GraalJS SDK runs fetch concurrently with other commands and cancels unawaited IO") {
    for
      arrivals <- Ref.of[IO, Int](0)
      active <- Ref.of[IO, Int](0)
      ready <- Deferred[IO, Unit]
      arrive = arrivals.updateAndGet(_ + 1).flatMap(n => (if n == 2 then ready.complete(()).void else IO.unit) *> ready.get)
      client = Client[IO](request => Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).evalMap { _ =>
        if request.uri.path.renderString == "/arrive" then arrive.as(Response[IO](Status.Ok).withEntity("fetch")) else IO.never[Response[IO]]
      })
      app = httpApp {
        FetchRoutes.register(new FetchService(client))
        command[String]("arrive") { handle { arrive.as("tool") } }
        command[Unit]("wait") { handle { IO.never[Unit] } }
      }
      _ <- temporary.use { home => GraalManager.resource(home, IO.pure(GraalCommandSdk(app))).use { manager =>
        run(manager, """
          if (typeof fetch !== "undefined") throw Error("unexpected business-specific global");
          const promise = tools.fetch({url:"http://example.com/arrive"});
          if (!(promise instanceof Promise)) throw Error("not a native Promise");
          const results = await Promise.all([promise, tools.arrive()]);
          if (results[0].body.bodyBase64 !== "ZmV0Y2g=" || results[1].body !== "tool") throw Error("concurrency");
          const pending = Array.from({length:64}, (_, i) => i % 2 ? tools.wait() : tools.fetch({url:"http://example.com/wait"}));
          try { await tools.fetch({url:"http://example.com/wait"}); throw Error("missing pending limit"); }
          catch(e) { if (!e.message.includes("64")) throw e; }
        """) *> active.get.map(count => assertEquals(count, 0))
      } }
    yield ()
  }

  test("task cancellation and wall timeout wait for command HTTP finalizers") {
    for
      started <- Deferred[IO, Unit]
      finalizing <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      active <- Ref.of[IO, Int](0)
      client = Client[IO](_ => Resource.make(active.update(_ + 1) *> started.complete(()))(_ =>
        finalizing.complete(()) *> release.get *> active.update(_ - 1)).evalMap(_ => IO.never[Response[IO]]))
      commands = sdk(new FetchService(client))
      _ <- temporary.use { home => GraalManager.resource(home, IO.pure(commands)).use { manager => (for
        first <- manager.start("await tools.fetch({url:'http://example.com/wait'});", "5s", "10s")
        _ <- started.get.timeout(5.seconds)
        _ <- manager.cancel(first.id)
        _ <- finalizing.get.timeout(5.seconds)
        current <- manager.status(first.id)
        _ = assertEquals(current.state, "cancelling")
        _ <- release.complete(())
        done <- awaitUntil(manager.status(first.id))(_.terminal)
        _ = assertEquals(done.state, "cancelled")
        timed <- manager.start("await tools.fetch({url:'http://example.com/wait'});", "5s", "150ms")
        timeout <- awaitUntil(manager.status(timed.id))(_.terminal)
        _ = assertEquals(timeout.error.map(_.code), Some("timeout"))
        count <- active.get
      yield assertEquals(count, 0)).guarantee(release.complete(()).void) } }
    yield ()
  }

  test("exposeOn(graaljs) controls discovery and public routes while SDK calls retain validation and middleware") {
    for
      calls <- Ref.of[IO, Int](0)
      sent <- Ref.of[IO, Int](0)
      client = Client[IO](_ => Resource.eval(sent.update(_ + 1).as(Response[IO](Status.NotFound).withEntity("missing"))))
      app = httpApp {
        middleware { next => calls.update(_ + 1) *> next }
        FetchRoutes.register(new FetchService(client))
      }
      document = io.circe.parser.parse(app.openApiJson).toOption.get
      _ = assertEquals(document.hcursor.downField("paths").downField("/fetch").downField("post").get[List[String]]("x-expose-on"), Right(List("graaljs")))
      commands = GraalCommandSdk(app)
      _ = assertEquals(commands.commands, Vector("fetch"))
      _ <- serve(app.httpApp).use { url => org.http4s.ember.client.EmberClientBuilder.default[IO].build.use { external =>
        List(Method.GET, Method.POST, Method.HEAD, Method.OPTIONS).traverse_ { method =>
          external.run(Request[IO](method, Uri.unsafeFromString(s"$url/fetch"))).use(r => IO(assertEquals(r.status, Status.NotFound)))
        }
      } }
      before <- calls.get
      _ = assertEquals(before, 0)
      invalid <- commands.call("fetch", Json.obj("url" -> Json.fromInt(42)))
      _ = assertEquals(invalid.hcursor.get[Int]("status"), Right(400))
      _ <- temporary.use { home => GraalManager.resource(home, IO.pure(commands)).use { manager => run(manager, """
        if (typeof fetch !== "undefined") throw Error("unexpected global fetch");
        const result = await tools.fetch({url:"http://example.com"});
        if (result.status !== 200 || result.body.status !== 404 || result.body.ok || result.body.bodyBase64 !== "bWlzc2luZw==") throw Error(JSON.stringify(result));
        for (const request of [{url:"relative"}, {url:"http://example.com", headers:{"bad name":"x"}},
          {url:"http://example.com", method:"POST", body:"!", bodyEncoding:"base64"}]) {
          try { await tools.fetch(request); throw Error("missing command validation"); }
          catch(e) { if (e.name !== "ApiError" || e.response.status !== 400) throw e; }
        }
      """) } }
      after <- calls.get
      executed <- sent.get
    yield
      assertEquals(after, 4)
      assertEquals(executed, 1)
  }

  test("the generic SDK omits commands excluded from the GraalJS catalog") {
    val hidden = httpApp { command[Unit]("fetch") { exposeOn(cli, codemode); handle { IO.unit } } }
    List(GraalCommandSdk.empty, GraalCommandSdk(hidden)).traverse_ { commands =>
      temporary.use { home => GraalManager.resource(home, IO.pure(commands)).use { manager =>
        run(manager, "if (typeof fetch !== 'undefined' || typeof tools.fetch !== 'undefined') throw Error('fetch exposure bypassed');")
      } }
    }
  }
