package groot.chrome

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import framework.model.JsonModel
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
        reserved <- sdk.call("chrome.instance.start", Json.obj("instanceName" -> Json.fromString("test"),
          "extraArgs" -> Json.arr(Json.fromString("--remote-debugging-port=9222"))))
        _ = assertEquals(reserved.hcursor.get[Int]("status"), Right(400))
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

  test("custom Chrome arguments reject managed switches and old metadata has empty arguments") {
    ChromeValidation.extraArgs(Vector("--force-dark-mode", "--user-agent=Groot test agent", "--window-size=960,720"))
    Vector("", "--", "--user-data-dir=/other", "--remote-debugging-pipe", "--headless=new", "--kiosk",
      "about:blank", "--user-agent=bad\u0000value").foreach { arg =>
      intercept[ChromeFailure](ChromeValidation.extraArgs(Vector(arg)))
    }
    intercept[ChromeFailure](ChromeValidation.extraArgs(Vector.fill(129)("--force-dark-mode")))
    val old = Json.obj("instanceName" -> Json.fromString("old"), "kiosk" -> Json.False, "headless" -> Json.True)
    val decoded = JsonModel[SavedInstance].decoder.decodeJson(old).toOption.get
    assertEquals(decoded.extraArgs, Vector.empty)
    val configured = decoded.copy(extraArgs = Vector("--user-agent=Groot test agent"))
    assertEquals(JsonModel[SavedInstance].decoder.decodeJson(JsonModel[SavedInstance].encoder(configured)), Right(configured))
  }

  test("snapshot hierarchy is parent-first across ignored nodes, projections and truncation") {
    import ChromeJson.*
    import PageSnapshots.*
    def ax(id: String, parent: String, role: String, name: String, backend: Int, ignored: Boolean = false): Json =
      obj("nodeId" -> str(id), "parentId" -> str(parent), "role" -> obj("value" -> str(role)),
        "name" -> obj("value" -> str(name)), "backendDOMNodeId" -> Json.fromInt(backend), "ignored" -> Json.fromBoolean(ignored))
    // Deliberately not in tree order, like the protocol response on a dynamic page.
    val raw = Vector(ax("text", "wrapper", "StaticText", "Refresh\nQR", 4),
      ax("root", "", "RootWebArea", "Fixture", 1), ax("wrapper", "action", "none", "", 3, true),
      ax("action", "root", "generic", "", 2))
    val dom = Map(1 -> DomNode(9, None, false), 2 -> DomNode(1, Some("DIV"), true),
      3 -> DomNode(1, Some("SPAN"), false), 4 -> DomNode(3, None, false))
    val trees = Vector("main", "child").map(f => FrameTree(PageFrame(f, "loader", "session"), raw, dom))
    val full = build(trees, "ref:test:", 100, "json", false)
    assertEquals(full.nodes.map(_.role), Vector.fill(2)(Vector("RootWebArea", "generic", "StaticText")).flatten)
    assertEquals(full.nodes.map(_.depth), Vector(0, 1, 2, 0, 1, 2))
    assertEquals(full.nodes(2).parentRef, full.nodes(1).ref)
    assertEquals(full.nodes(1).name, "Refresh\nQR")
    assertEquals(full.nodes(3).parentRef, None)
    assertEquals(full.refs.size, 6)
    val limited = build(trees, "ref:limit:", 2, "json", false)
    assert(limited.truncated)
    assert(limited.nodes.forall(n => n.parentRef.forall(limited.refs.contains)))
    val controls = build(trees, "ref:controls:", 100, "text", true)
    assertEquals(controls.nodes.size, 2)
    assert(controls.nodes.forall(n => n.clickable && n.depth == 0 && n.parentRef.isEmpty))
    val text = render(controls.nodes, false)
    assert(text.contains("- div \"Refresh\\nQR\" [ref=ref:controls:2] [clickable]"))
    assert(!text.contains("StaticText"))
    assert(text.contains("frame \"child\""))
    val huge = trees.head.copy(nodes = Vector(ax("root", "", "RootWebArea", "Fixture", 1)) ++
      Vector.tabulate(1000)(i => ax(s"n$i", "root", "button", "界" * 4096, i + 2)))
    val capped = build(Vector(huge), "ref:bytes:", 5000, "json", false)
    assert(capped.truncated)
    assert(capped.nodes.forall(n => n.parentRef.forall(capped.refs.contains)))
    assert(capped.nodes.map(n => JsonModel[SnapshotNode].encoder(n).noSpaces.getBytes(UTF_8).length).sum <= 768 * 1024)
  }

  private def fixture: Resource[IO, String] = Resource.make(IO.blocking {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", exchange => {
      val html = if exchange.getRequestURI.getPath == "/cross" then
        "<title>Cross frame</title><label>Cross input<input id='cross-input'></label><div onclick=\"document.body.dataset.clicked='yes'\">Cross action</div>"
      else if exchange.getRequestURI.getPath == "/frame" then
        "<title>Frame</title><label>Frame input<input id='frame-input'></label>"
      else """<!doctype html><title>Chrome fixture</title>
        <label>Name<input id="name" value="old"></label>
        <button id="apply" onclick="document.querySelector('#result').textContent=document.querySelector('#name').value">Apply</button>
        <div id="result"></div><label>Choice<input id="check" type="checkbox"></label>
        <select id="select"><option value="a">A</option><option value="b">B</option></select>
        <div id="refresh"><span>Refresh QR</span></div><div id="refresh-result"></div>
        <button disabled onclick="window.disabledClicked=true">Disabled action</button>
        <div id="shadow"></div><script>
          document.querySelector('#refresh').addEventListener('click', event => {
            window.refreshClicks=(window.refreshClicks||0)+1; window.trustedClick=event.isTrusted;
            document.querySelector('#refresh-result').textContent=String(window.refreshClicks);
          });
          document.querySelector('#shadow').attachShadow({mode:'open'}).innerHTML='<label>Shadow input<input id="shadow-input"></label><div onclick="window.shadowClicked=true">Shadow action</div>';
        </script>
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

  if sys.env.get("CHROME_TEST").contains("1") then test("custom Chrome startup arguments reach Chromium and persist across service reloads") {
    temporary.use { home =>
      val args = Vector("--window-size=960,720", "--user-agent=Groot custom agent with spaces")
      val request = Json.obj("instanceName" -> Json.fromString("custom"), "headless" -> Json.True,
        "extraArgs" -> Json.fromValues(args.map(Json.fromString)))
      ChromeService.resource(home).use { service =>
        val sdk = GraalCommandSdk(httpApp { ChromeRoutes.register(service) })
        for
          response <- sdk.call("chrome.instance.start", request)
          _ = assertEquals(response.hcursor.get[Int]("status"), Right(200))
          started <- IO.fromEither(response.hcursor.downField("body").as[ChromeInstance](using JsonModel[ChromeInstance].decoder))
          _ = assertEquals(started.extraArgs, args)
          again <- service.start("custom", false, true, args)
          _ = assertEquals(again.pid, started.pid)
          conflict <- service.start("custom", false, true, Vector("--lang=fr")).attempt
          _ = assert(conflict.swap.toOption.exists { case e: ChromeFailure => e.code == "chrome_conflict"; case _ => false })
          _ <- service.newTab("custom", "main", "about:blank")
          actual <- service.page("custom", "main", 5000)(_.evaluate("[navigator.userAgent,outerWidth,outerHeight]", 1000))
          _ = assertEquals(actual, Json.arr(Json.fromString("Groot custom agent with spaces"), Json.fromInt(960), Json.fromInt(720)))
          stopped <- service.stop("custom")
          _ = assertEquals(stopped.extraArgs, args)
        yield ()
      } *> ChromeService.resource(home).use { service => for
        states <- service.list
        _ = assertEquals(states.head.extraArgs, args)
        next <- service.start("custom", false, true, Vector("--user-agent=Changed agent"))
        _ = assertEquals(next.extraArgs, Vector("--user-agent=Changed agent"))
        _ <- service.newTab("custom", "main", "about:blank")
        agent <- service.page("custom", "main", 5000)(_.evaluate("navigator.userAgent", 1000))
        _ = assertEquals(agent, Json.fromString("Changed agent"))
      yield () }
    }
  }

  if sys.env.get("CHROME_TEST").contains("1") then test("real snapshot click hints, text targets, compact refs and pointer checks") {
    (temporary, fixture).tupled.use { (home, url) => ChromeService.resource(home).use { service =>
      def on[A](f: ChromePage => IO[A]): IO[A] = service.page("test", "main", 15000)(f)
      val sdk = GraalCommandSdk(httpApp { ChromeRoutes.register(service) })
      for
        _ <- service.start("test", false, true)
        _ <- service.newTab("test", "main", "about:blank")
        _ <- on(_.goto(url, "load"))
        snap <- on(_.snapshot(1000))
        text = snap.nodes.find(n => n.role == "StaticText" && n.name == "Refresh QR").get
        action = snap.nodes.find(n => n.tagName.contains("DIV") && n.clickable && n.name == "Refresh QR").get
        _ = assertEquals(text.nodeType, Some(3))
        _ = assert(!text.clickable)
        _ = assertEquals(text.parentRef, action.ref)
        _ = snap.nodes.zipWithIndex.foreach { (n, i) => n.parentRef.foreach { parent =>
          assert(snap.nodes.take(i).exists(p => p.ref.contains(parent) && p.frameId == n.frameId && p.depth == n.depth - 1))
        }}
        _ <- on(_.elements.click(action.ref.get, None))
        _ <- on(_.elements.click(text.ref.get, None))
        _ <- on(_.elements.hover(text.ref.get, None))
        clicks <- on(_.evaluate("({clicks:window.refreshClicks,trusted:window.trustedClick})", 1000))
        _ = assertEquals(clicks, Json.obj("clicks" -> Json.fromInt(2), "trusted" -> Json.True))
        disabled = snap.nodes.find(n => n.role == "StaticText" && n.name == "Disabled action").flatMap(_.ref).get
        disabledClick <- service.page("test", "main", 500)(_.elements.click(disabled, None)).attempt
        _ = assert(disabledClick.swap.toOption.exists { case e: ChromeFailure => e.code == "chrome_timeout"; case _ => false })
        _ <- on(_.evaluate("(()=>{const cover=document.createElement('div');cover.id='cover';cover.style.cssText='position:fixed;inset:0;z-index:99999';document.body.append(cover)})()", 1000))
        covered <- service.page("test", "main", 500)(_.elements.click(text.ref.get, None)).attempt
          .guarantee(on(_.evaluate("document.querySelector('#cover').remove()", 1000)).void)
        _ = assert(covered.swap.toOption.exists { case e: ChromeFailure => e.code == "chrome_timeout"; case _ => false })
        unchanged <- on(_.evaluate("[window.refreshClicks,window.disabledClicked??false]", 1000))
        _ = assertEquals(unchanged, Json.arr(Json.fromInt(2), Json.False))
        cross = snap.nodes.find(n => n.role == "StaticText" && n.name == "Cross action").get
        _ <- on(_.elements.click(cross.ref.get, None))
        crossClicked <- on(p => p.frame(Some(cross.frameId)).flatMap(f => p.remote("document.body.dataset.clicked", f)))
        _ = assertEquals(crossClicked, Json.fromString("yes"))
        shadow = snap.nodes.find(n => n.role == "StaticText" && n.name == "Shadow action").flatMap(_.ref).get
        _ <- on(_.elements.click(shadow, None))
        shadowClicked <- on(_.evaluate("window.shadowClicked", 1000))
        _ = assertEquals(shadowClicked, Json.True)
        response <- sdk.call("chrome.page.snapshot", Json.obj("instanceName" -> Json.fromString("test"),
          "tabName" -> Json.fromString("main"), "format" -> Json.fromString("text"), "interactiveOnly" -> Json.True))
        _ = assertEquals(response.hcursor.get[Int]("status"), Right(200))
        compact <- IO.fromEither(response.hcursor.downField("body").as[PageSnapshot](using JsonModel[PageSnapshot].decoder))
        _ = assertEquals(compact.nodes, Vector.empty)
        rendered = compact.text.get
        _ = assert(!rendered.contains("- text "))
        row = rendered.linesIterator.find(_.contains("- div \"Refresh QR\"")).get
        _ = assert(row.contains("[clickable]"))
        compactRef = "\\[ref=([^\\]]+)\\]".r.findFirstMatchIn(row).get.group(1)
        stale <- on(_.elements.click(text.ref.get, None)).attempt
        _ = assert(stale.swap.toOption.exists { case e: ChromeFailure => e.code == "chrome_stale_ref"; case _ => false })
        _ <- on(_.elements.click(compactRef, None))
        refreshed <- on(_.evaluate("window.refreshClicks", 1000))
        _ = assertEquals(refreshed, Json.fromInt(3))
        invalid <- on(_.snapshot(1000, "xml")).attempt
        _ = assert(invalid.swap.toOption.exists { case e: ChromeFailure => e.code == "invalid_chrome"; case _ => false })
        controls <- on(_.snapshot(1000, "json", true))
        _ = assert(controls.text.isEmpty)
        _ = assert(controls.nodes.forall(_.nodeType.contains(1)))
        _ = assert(controls.nodes.exists(_.name == "Refresh QR"))
        tree <- on(_.snapshot(1000, "text"))
        _ = assert(tree.text.get.contains("- text \"Refresh QR\""))
        _ = assert(!tree.text.get.contains("InlineTextBox"))
        _ = assert(!tree.text.get.contains("- generic"))
      yield ()
    }}
  }

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
