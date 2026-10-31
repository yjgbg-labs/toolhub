package groot.graaljs

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import framework.rpcDsl.*
import framework.rpc.CommandsScope
import framework.runtime.CommandSdk
import groot.fetch.{FetchRoutes, FetchService}
import fs2.{Chunk, Stream}
import io.circe.Json
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.zip.{DeflaterOutputStream, GZIPOutputStream}
import munit.CatsEffectSuite
import org.http4s.{Header, HttpApp, Method, Request, Response, Status, Uri}
import org.http4s.client.Client
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import GraalTestSupport.*

class GraalFetchSuite extends CatsEffectSuite:
  private def header(name: String, value: String): Header.Raw = Header.Raw(CIString(name), value)
  private def serve(app: HttpApp[IO]): Resource[IO, String] = EmberServerBuilder.default[IO]
    .withHost(ipv4"127.0.0.1").withPort(port"0").withHttpApp(app).build.map(s => s"http://127.0.0.1:${s.address.getPort}")
  private def run(manager: GraalManager, source: String): IO[String] = for
    submitted <- manager.start(source, "5s", "10s")
    task <- awaitUntil(manager.status(submitted.id))(_.terminal)
    log <- manager.logs(task.id).map(_.toString)
    _ = assertEquals(task.state, "succeeded", s"${task.error}\n$log")
  yield log

  private def manager(fetch: FetchService, register: CommandsScope ?=> Unit = ()): Resource[IO, GraalManager] =
    val app = httpApp { FetchRoutes.register(fetch); register }
    temporary.flatMap(home => GraalManager.managed(home, GraalRunner.resource(IO.pure(app.commandSdk)), Limits.taskBytes))

  private def liveManager(home: Path): Resource[IO, GraalManager] = FetchService.resource.flatMap { fetch =>
    val app = httpApp { FetchRoutes.register(fetch) }
    GraalManager.resource(home, IO.pure(app.commandSdk))
  }

  private val echo = HttpApp[IO] { request => request.body.compile.to(Array).map { body =>
    val values = request.headers.headers.groupMap(_.name.toString.toLowerCase(java.util.Locale.ROOT))(_.value)
    val json = Json.obj("method" -> Json.fromString(request.method.name), "text" -> Json.fromString(new String(body, UTF_8)),
      "bytes" -> Json.fromValues(body.map(b => Json.fromInt(b & 0xff))),
      "headers" -> Json.fromFields(values.map((k, v) => k -> Json.fromString(v.mkString(", ")))))
    Response[IO](Status.Ok).withEntity(json.noSpaces).putHeaders(header("Content-Type", "application/json"), header("X-Reply", "yes"))
  } }

  test("real HTTP fetch preserves the Code Mode response surface, UTF-8 and repeated body reads") {
    (serve(echo), temporary).tupled.use { (url, home) => liveManager(home).use { manager =>
      run(manager, s"""
        const original = fetch;
        try { globalThis.fetch = null; } catch (_) {}
        if (fetch !== original || typeof Java !== "undefined" || typeof search !== "undefined") throw Error("invalid globals");
        const pending = fetch("$url/echo?x=1", {method: "post", headers: {"X-Test": "value"}, body: "中文🙂"});
        if (!(pending instanceof Promise)) throw Error("not a Promise");
        const response = await pending;
        if (Object.keys(response).sort().join(",") !== "bytes,headers,json,ok,redirected,status,statusText,text,url") throw Error("response surface");
        if (!response.ok || response.status !== 200 || response.statusText !== "OK" || response.redirected || response.url !== "$url/echo?x=1") throw Error("response metadata");
        if (response.headers.get("X-REPLY") !== "yes" || !response.headers.has("Content-Type") || response.headers.get("missing") !== null) throw Error("headers");
        const entries = response.headers.entries();
        if (!Array.isArray(entries) || Object.fromEntries(entries)["x-reply"] !== "yes") throw Error("header entries");
        const first = await response.json(), second = await response.json();
        if (first === second || first.text !== "中文🙂" || first.method !== "POST" || first.headers["x-test"] !== "value" || first.headers["content-type"] !== "text/plain;charset=UTF-8") throw Error(JSON.stringify(first));
        const text = await response.text();
        if (text !== await response.text() || JSON.parse(text).text !== first.text || !(await response.bytes() instanceof Uint8Array)) throw Error("body methods");
        const bytes = new Uint8Array([0, 127, 128, 255]);
        const binary = await (await fetch("$url", {method: "PUT", headers: [["x-duplicate", "one"], ["X-Duplicate", "two"], ["Content-Length", "999"], ["Transfer-Encoding", "chunked"]], body: bytes})).json();
        if (binary.bytes.join(",") !== "0,127,128,255" || binary.headers["x-duplicate"] !== "one, two" || binary.headers["content-type"] !== undefined || binary.headers["content-length"] !== "4" || binary.headers["transfer-encoding"] !== undefined) throw Error(JSON.stringify(binary));
        const again = await (await fetch("$url", {headers: response.headers})).json();
        if (again.headers["x-reply"] !== "yes") throw Error("headers object");
        console.log("fetch contract passed");
      """).map(log => assert(log.contains("fetch contract passed"), log))
    } }
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
      (serve(app), temporary).tupled.use { (url, home) => liveManager(home).use { manager =>
        run(manager, s"""
          const post = await fetch("$url/post", {method:"POST", body:"data", headers:{"Content-Type":"application/json"}});
          const rewritten = await post.json();
          if (!post.redirected || post.url !== "$url/echo" || rewritten.method !== "GET" || rewritten.text !== "" || rewritten.headers["content-type"] !== undefined) throw Error(JSON.stringify(rewritten));
          const kept = await (await fetch("$url/keep", {method:"POST", body:"keep"})).json();
          if (kept.method !== "POST" || kept.text !== "keep") throw Error("307 lost body");
          const cross = await (await fetch("$url/cross", {headers:{Authorization:"Bearer secret", Cookie:"a=b", "X-Keep":"safe"}})).json();
          if (cross.headers.authorization || cross.headers.cookie || cross.headers["x-keep"] !== "safe") throw Error("redirect credentials");
          try { await fetch("$url/loop"); throw Error("accepted redirect loop"); }
          catch(e) { if (!(e instanceof TypeError) || !e.message.includes("20 redirects")) throw e; }
        """).void
      } }
    }
  }

  test("HTTP errors resolve normally, empty and malformed JSON reject on json(), gzip/deflate decode and bytes stay binary") {
    def compressed(gzip: Boolean): Array[Byte] =
      val out = new ByteArrayOutputStream
      val encoder = if gzip then new GZIPOutputStream(out) else new DeflaterOutputStream(out)
      encoder.write("\ufeff{\"text\":\"中文\"}".getBytes(UTF_8))
      encoder.close()
      out.toByteArray
    val app = HttpApp[IO] { request => IO.pure(request.uri.path.renderString match
      case "/missing" => Response[IO](Status.NotFound).withEntity("missing")
      case "/empty" => Response[IO](Status.NoContent)
      case "/binary" => Response[IO](Status.Ok).withBodyStream(Stream.chunk(Chunk.array(Array[Byte](0, 127, -128, -1))))
      case path => Response[IO](Status.Ok).withBodyStream(Stream.chunk(Chunk.array(compressed(path == "/gzip"))))
        .putHeaders(header("Content-Encoding", if path == "/gzip" then "gzip" else "deflate"))) }
    (serve(app), temporary).tupled.use { (url, home) => liveManager(home).use { manager =>
      run(manager, s"""
        const missing = await fetch("$url/missing");
        if (missing.ok || missing.status !== 404 || await missing.text() !== "missing") throw Error("HTTP error rejected");
        const empty = await fetch("$url/empty");
        if (empty.status !== 204 || await empty.text() !== "") throw Error("empty response");
        for (const r of [missing, empty]) {
          try { await r.json(); throw Error("accepted invalid JSON"); }
          catch(e) { if (!(e instanceof SyntaxError)) throw e; }
        }
        for (const encoding of ["gzip", "deflate"]) {
          const r = await fetch("$url/" + encoding);
          if ((await r.json()).text !== "中文" || r.headers.get("content-encoding") !== encoding) throw Error("compressed body");
        }
        const bytes = await (await fetch("$url/binary")).bytes();
        if (!(bytes instanceof Uint8Array) || [...bytes].join(",") !== "0,127,128,255") throw Error("invalid binary bytes");
        const head = await fetch("$url/gzip", {method:"HEAD"});
        if (await head.text() !== "") throw Error("HEAD has a body");
      """).void
    } }
  }

  test("validation and network failures are native rejected Promises") {
    val client = Client[IO](_ => Resource.eval(IO.raiseError(new java.io.IOException("connection failed"))))
    manager(new FetchService(client)).use { manager => run(manager, """
      const calls = [() => fetch(), () => fetch("relative"), () => fetch("file:///tmp/file"),
        () => fetch("ftp://example.com"), () => fetch("http://user:pass@example.com"),
        () => fetch("http://example.com", {method:"GET", body:""}),
        () => fetch("http://example.com", {method:"CONNECT"}),
        () => fetch("http://example.com", {headers:[["x"]]}),
        () => fetch("http://example.com", {headers:{"x-bad":"injected\r\nheader"}}),
        () => fetch("http://example.com", {headers:{"bad name":"x"}}),
        () => fetch("http://example.com")];
      for (const invoke of calls) {
        const promise = invoke();
        if (!(promise instanceof Promise)) throw Error("synchronous validation error");
        try { await promise; throw Error("accepted invalid request"); }
        catch(e) { if (!(e instanceof TypeError)) throw e; }
      }
    """).void }
  }

  test("response limits and deadlines cover the streamed body and release the client resource") {
    Ref.of[IO, Int](0).flatMap { active =>
      val client = Client[IO](request => Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).as(
        Response[IO](Status.Ok).withBodyStream(request.uri.path.renderString match
          case "/large" => Stream.chunk(Chunk.array(Array.fill[Byte](FetchService.maxBytes + 1)(65)))
          case "/encoded" => Stream.chunk(Chunk.array(Array.fill[Byte](FetchService.maxBytes * 3 / 4)(65)))
          case _ => Stream.eval(IO.never[Byte]))))
      manager(new FetchService(client, 150.millis)).use { manager =>
        run(manager, """
          for (const [path, name] of [["large", "RangeError"], ["encoded", "RangeError"], ["slow", "TimeoutError"]]) {
            try { await fetch("http://example.com/" + path); throw Error("missing limit"); }
            catch(e) { if (!(e instanceof Error) || e.name !== name) throw e; }
          }
          try { await fetch("http://example.com", {method:"POST", body:"x".repeat(1024 * 1024 + 1)}); throw Error("missing input limit"); }
          catch(e) { if (!(e instanceof RangeError)) throw e; }
        """) *> active.get.map(count => assertEquals(count, 0))
      }
    }
  }

  test("fetch and tools run concurrently, share the pending limit, and unawaited requests are cancelled") {
    for
      arrivals <- Ref.of[IO, Int](0)
      active <- Ref.of[IO, Int](0)
      ready <- Deferred[IO, Unit]
      arrive = arrivals.updateAndGet(_ + 1).flatMap(n => (if n == 2 then ready.complete(()).void else IO.unit) *> ready.get)
      register: (CommandsScope ?=> Unit) = {
        command[String]("arrive") { handle { arrive.as("tool") } }
        command[Unit]("wait") { handle { IO.never[Unit] } }
      }
      client = Client[IO](request => Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).evalMap { _ =>
        if request.uri.path.renderString == "/arrive" then arrive.as(Response[IO](Status.Ok).withEntity("fetch")) else IO.never[Response[IO]]
      })
      _ <- manager(new FetchService(client), register).use { manager =>
        run(manager, """
          const results = await Promise.all([fetch("http://example.com/arrive"), tools.arrive()]);
          if (await results[0].text() !== "fetch" || results[1].body !== "tool") throw Error("concurrency");
          const pending = Array.from({length: 64}, (_, i) => i % 2 ? tools.wait() : fetch("http://example.com/wait"));
          try { await fetch("http://example.com/wait"); throw Error("missing pending limit"); }
          catch(e) { if (!e.message.includes("64")) throw e; }
        """) *> active.get.map(count => assertEquals(count, 0))
      }
    yield ()
  }

  test("task cancellation and wall timeout await fetch finalizers before releasing task reservations") {
    for
      started <- Deferred[IO, Unit]
      finalizing <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      client = Client[IO](_ => Resource.make(started.complete(()))(_ => finalizing.complete(()) *> release.get)
        .evalMap(_ => IO.never[Response[IO]]))
      _ <- manager(new FetchService(client)).use { manager => (for
        first <- manager.start("await fetch('http://example.com/wait');", "5s", "10s")
        _ <- started.get.timeout(5.seconds)
        second <- manager.start("console.log('next');", "5s", "10s")
        _ <- manager.cancel(first.id)
        _ <- finalizing.get.timeout(5.seconds)
        pending <- manager.status(second.id)
        _ = assertEquals(pending.state, "queued")
        current <- manager.status(first.id)
        _ = assertEquals(current.state, "cancelling")
        _ <- release.complete(())
        done <- awaitUntil(manager.status(first.id))(_.terminal)
        _ = assertEquals(done.state, "cancelled")
        next <- awaitUntil(manager.status(second.id))(_.terminal)
        _ = assertEquals(next.state, "succeeded")
        timed <- manager.start("await fetch('http://example.com/wait');", "5s", "150ms")
        timeout <- awaitUntil(manager.status(timed.id))(_.terminal)
      yield assertEquals(timeout.error.map(_.code), Some("timeout"))).guarantee(release.complete(()).void) }
    yield ()
  }

  test("fetch is a GraalJS-only command: direct SDK and global adapter share validation and middleware, HTTP stays hidden") {
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
      _ = assertEquals(app.commandSdk.commands, Vector("fetch"))
      _ <- serve(app.httpApp).use { url => org.http4s.ember.client.EmberClientBuilder.default[IO].build.use { external =>
        List(Method.GET, Method.POST, Method.HEAD, Method.OPTIONS).traverse_ { method =>
          external.run(Request[IO](method, Uri.unsafeFromString(s"$url/fetch"))).use(r => IO(assertEquals(r.status, Status.NotFound)))
        }
      } }
      before <- calls.get
      _ = assertEquals(before, 0)
      invalid <- app.commandSdk.call("fetch", Json.obj("body" -> Json.obj("url" -> Json.fromInt(42))))
      _ = assertEquals(invalid.hcursor.get[Int]("status"), Right(400))
      _ <- temporary.use { home => GraalManager.resource(home, IO.pure(app.commandSdk)).use { manager => run(manager, """
        const direct = await tools.fetch({body: {url:"http://example.com"}});
        if (direct.status !== 200 || direct.body.status !== 404 || direct.body.ok || direct.body.bodyBase64 !== "bWlzc2luZw==") throw Error(JSON.stringify(direct));
        const adapted = await fetch("http://example.com");
        if (adapted.status !== 404 || await adapted.text() !== "missing") throw Error("adapter bypassed the command");
        for (const body of [{url:"relative"}, {url:"http://example.com", headers:{"bad name":"x"}},
          {url:"http://example.com", method:"POST", body:"!", bodyEncoding:"base64"}]) {
          try { await tools.fetch({body}); throw Error("missing command validation"); }
          catch(e) { if (e.name !== "ApiError" || e.response.status !== 400) throw e; }
        }
      """).void } }
      after <- calls.get
      executed <- sent.get
    yield
      assertEquals(after, 5)
      assertEquals(executed, 2)
  }

  test("the global adapter is absent when fetch is missing or excluded from the GraalJS catalog") {
    val hidden = httpApp { command[Unit]("fetch") { exposeOn(cli, codemode); handle { IO.unit } } }
    List(CommandSdk.empty, hidden.commandSdk).traverse_ { sdk =>
      temporary.use { home => GraalManager.resource(home, IO.pure(sdk)).use { manager =>
        run(manager, "if (typeof fetch !== 'undefined' || typeof tools.fetch !== 'undefined') throw Error('fetch exposure bypassed');").void
      } }
    }
  }
