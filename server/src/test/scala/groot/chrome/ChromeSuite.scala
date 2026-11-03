package groot.chrome

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.GraalCommandSdk
import io.circe.Json
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import com.sun.net.httpserver.HttpServer
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class ChromeSuite extends CatsEffectSuite:
  override val munitIOTimeout = 120.seconds
  private def temporary: Resource[IO, Path] = Resource.make(IO.blocking {
    Files.createTempDirectory(Path.of(System.getProperty("user.home")), "groot-chrome-test-")
  })(path => IO.blocking(Using.resource(Files.walk(path))(_.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists))))

  test("domain matching respects label boundaries and names cannot escape profile roots") {
    IO {
      assertEquals(ChromeValidation.domain(".EXAMPLE.com."), "example.com")
      assert(ChromeValidation.matchesDomain(".a.example.com", "example.com"))
      assert(!ChromeValidation.matchesDomain("badexample.com", "example.com"))
      assert(!ChromeValidation.matchesDomain("example.com.evil.test", "example.com"))
      Vector("../x", ".", "..", "/tmp/x", "a/b", "").foreach(n => intercept[ChromeFailure](ChromeValidation.name(n)))
      Vector("https://example.com", "example.com:80", "example.com/a", "x..com").foreach(n => intercept[ChromeFailure](ChromeValidation.domain(n)))
    }
  }
  test("ordinary generated SDK commands expose lists without tab/body arguments and reject invalid modes") {
    temporary.use(home => ChromeService.resource(home, () => "/bin/false").use { service =>
      val app = httpApp { ChromeRoutes.register(service) }
      val sdk = GraalCommandSdk(app)
      assert(app.endpoints.forall(_.exposedOn == Set(cli, codemode, graaljs)))
      for
        list <- sdk.call("chrome.instance.list", Json.obj())
        _ = assertEquals(list.hcursor.get[Int]("status"), Right(200))
        _ = assertEquals(list.hcursor.downField("body").as[Vector[Json]], Right(Vector.empty))
        bad <- sdk.call("chrome.instance.start", Json.obj("instanceName" -> Json.fromString("test"), "kiosk" -> Json.True, "headless" -> Json.True))
        _ = assertEquals(bad.hcursor.get[Int]("status"), Right(400))
        missing <- sdk.call("chrome.tab.list", Json.obj("instanceName" -> Json.fromString("absent")))
        _ = assertEquals(missing.hcursor.get[Int]("status"), Right(404))
        failed <- service.start("failed", false, true).attempt
        _ = assert(failed.isLeft)
        states <- service.list
        _ = assertEquals(states.map(_.status), Vector("stopped"))
        _ <- service.purge("failed")
        owned <- ChromeService.resource(home).use(_ => IO.unit).attempt
        _ = assert(owned.isLeft)
      yield ()
    })
  }

  private def fixture: Resource[IO, String] = Resource.make(IO.blocking {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", exchange => {
      val html = if exchange.getRequestURI.getPath == "/cross" then
        "<title>Cross frame</title><label>Cross input<input id='cross-input'></label>"
      else if exchange.getRequestURI.getPath == "/frame" then
        "<title>Frame</title><label>Frame input<input id='frame-input'></label>"
      else """<!doctype html><title>Chrome fixture</title>
        <label>Name<input id="name" value="old"></label>
        <button id="apply" onclick="document.querySelector('#result').textContent=document.querySelector('#name').value">Apply</button>
        <div id="result"></div><label>Choice<input id="check" type="checkbox"></label>
        <select id="select"><option value="a">A</option><option value="b">B</option></select>
        <div id="shadow"></div><script>document.querySelector('#shadow').attachShadow({mode:'open'}).innerHTML='<label>Shadow input<input id="shadow-input"></label>';</script>
        <iframe src="/frame"></iframe><iframe id="cross" src="CROSS_FRAME_URL"></iframe>
        <div style="height:1600px"></div><button id="bottom">Bottom</button>""".replace("CROSS_FRAME_URL", s"http://localhost:${server.getAddress.getPort}/cross")
      val bytes = html.getBytes(UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "text/html; charset=utf-8")
      exchange.getResponseHeaders.add("Set-Cookie", "groot_test=kept; Path=/; Max-Age=3600; HttpOnly; SameSite=Lax")
      exchange.sendResponseHeaders(200, bytes.length)
      Using.resource(exchange.getResponseBody)(_.write(bytes))
      exchange.close()
    })
    server.start()
    server
  })(server => IO.blocking(server.stop(0))).map(server => s"http://127.0.0.1:${server.getAddress.getPort}/")

  if sys.env.get("CHROME_TEST").contains("1") then test("real Chromium lifecycle, refs, frames, shadow DOM, input, history, screenshots, cookies and cancellation") {
    (temporary, fixture).tupled.use { (home, url) =>
      def on[A](s: ChromeService)(f: ChromePage => IO[A]): IO[A] = s.page("test", "main", 15000)(f)
      for
        _ <- ChromeService.resource(home).use { service => for
          starts <- List.fill(2)(service.start("test", false, true)).parSequence
          _ = assertEquals(starts.map(_.pid).distinct.size, 1)
          _ = assert(starts.forall(s => s.status == "running" && s.pid.nonEmpty), starts.toString)
          metadata <- new ChromeStore(home).load
          _ = assert(new ChromeStore(home).owned(metadata.head).nonEmpty)
          _ <- service.newTab("test", "main", "about:blank")
          duplicate <- service.newTab("test", "main", "about:blank").attempt
          _ = assert(duplicate.isLeft)
          page <- on(service)(_.goto(url, "load"))
          _ = assertEquals(page.title, "Chrome fixture")
          snap <- on(service)(_.snapshot(1000))
          ref = snap.nodes.find(n => n.role == "button" && n.name == "Apply").flatMap(_.ref).get
          _ <- on(service)(_.elements.input("#name", None, "hello 世界", true))
          _ <- on(service)(_.elements.click(ref, None))
          _ <- on(service)(_.waitFor(Some("#result"), None, Some("hello 世界"), None, "visible"))
          _ <- on(service)(_.elements.check("#check", None, true))
          _ <- on(service)(_.elements.select("#select", None, "b"))
          _ <- on(service)(_.elements.input("#shadow-input", None, "shadow", true))
          child = snap.nodes.find(n => n.role == "textbox" && n.name == "Frame input").get
          _ <- on(service)(_.elements.input(child.ref.get, None, "frame", true))
          cross = snap.nodes.find(n => n.role == "textbox" && n.name == "Cross input").get
          _ <- on(service)(_.elements.input(cross.ref.get, None, "cross", true))
          crossValue <- on(service)(p => p.frame(Some(cross.frameId)).flatMap(f => p.remote("document.querySelector('input').value", f)))
          _ = assertEquals(crossValue, Json.fromString("cross"))
          values <- on(service)(_.evaluate("[document.querySelector('#name').value,document.querySelector('#check').checked,document.querySelector('#select').value,document.querySelector('#shadow').shadowRoot.querySelector('input').value,document.querySelector('iframe').contentDocument.querySelector('input').value]", 5000))
          _ = assertEquals(values, Json.arr(Json.fromString("hello 世界"), Json.True, Json.fromString("b"), Json.fromString("shadow"), Json.fromString("frame")))
          _ <- on(service)(_.evaluate(s"new Promise(resolve=>{const f=document.querySelector('#cross');f.onload=()=>resolve(null);f.src=${Json.fromString(url + "frame?changed=1").noSpaces}})", 5000))
          afterFrameNavigation <- on(service)(_.snapshot(1000))
          _ = assertEquals(afterFrameNavigation.nodes.count(n => n.role == "textbox" && n.name == "Frame input"), 2)
          beforeNavigationRef = afterFrameNavigation.nodes.find(n => n.role == "button" && n.name == "Apply").flatMap(_.ref).get
          _ <- on(service)(_.elements.hover("#apply", None))
          _ <- on(service)(_.elements.press("Tab"))
          _ <- on(service)(_.elements.scrollPage(Some("#bottom"), None, 0, 0))
          shot <- on(service)(_.screenshot(true))
          _ = assert(shot.height > 1600)
          bytes <- IO.blocking(Files.readAllBytes(Path.of(shot.path)))
          _ = assertEquals(bytes.take(8).toVector, Vector(137,80,78,71,13,10,26,10).map(_.toByte))
          cookies <- service.cookies("test", "127.0.0.1")
          _ = assert(cookies.exists(c => c.name == "groot_test" && c.httpOnly))
          _ <- on(service)(_.goto(url + "?next=1", "domcontentloaded"))
          stale <- on(service)(_.elements.click(beforeNavigationRef, None)).attempt
          _ = assert(stale.swap.toOption.exists { case e: ChromeFailure => e.code == "chrome_stale_ref"; case _ => false })
          previous <- on(service)(_.history(-1))
          _ = assertEquals(previous.url, url)
          next <- on(service)(_.history(1))
          _ = assertEquals(next.url, url + "?next=1")
          _ <- on(service)(_.reload("load"))
          timed <- service.page("test", "main", 150)(_.waitFor(Some("#missing"), None, None, None, "visible")).attempt
          _ = assert(timed.swap.toOption.exists { case e: ChromeFailure => e.code == "chrome_timeout"; case _ => false })
          never <- service.page("test", "main", 150)(_.evaluate("new Promise(()=>{})", 150)).attempt
          _ = assert(never.isLeft)
          stillResponsive <- on(service)(_.evaluate("21*2", 1000))
          _ = assertEquals(stillResponsive, Json.fromInt(42))
          _ <- on(service)(_.evaluate("window.open('/frame','popup'); null", 3000))
          tabs <- service.listTabs("test")
          _ = assert(tabs.size >= 3)
          stopped <- service.stop("test")
          _ = assertEquals(stopped.status, "stopped")
          _ <- service.start("test", false, true)
          persisted <- service.cookies("test", "127.0.0.1")
          _ = assert(persisted.exists(_.name == "groot_test"))
          _ <- if sys.env.get("CHROME_KIOSK_TEST").contains("1") then for
            _ <- service.stop("test")
            window <- service.start("test", true, false)
            _ = assert(window.status == "running" && window.kiosk && !window.headless)
            cmd <- IO.blocking(Files.readString(Path.of(s"/proc/${window.pid.get}/cmdline")))
            _ = assert(cmd.contains("--kiosk") && !cmd.contains("--headless"))
          yield () else IO.unit
        yield () }.onError(_ => IO.blocking {
          val log = home.resolve("instances/test/chrome.log")
          if Files.exists(log) then Files.copy(log, Path.of("/tmp/opencode/chrome-integration-failure.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          ()
        })
        _ <- ChromeService.resource(home).use { service => for
          states <- service.list
          _ = assertEquals(states.map(_.status), Vector("stopped"))
          removed <- service.purge("test")
          _ = assert(removed)
          again <- service.purge("test")
          _ = assert(!again)
          _ = assert(!Files.exists(home.resolve("instances/test")))
        yield () }
      yield ()
    }
  }
