package groot.chrome

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import io.circe.Json

object ChromeRoutes:
  def register(service: ChromeService)(using CommandsScope): Unit = commands("chrome") {
    description("Manage named Linux Chromium instances, tabs and pages through persistent CDP connections")
    val invalid = response.json[ErrorBody](400) { description("Invalid Chrome arguments") }
    val missing = response.json[ErrorBody](404) { description("Unknown instance, tab or frame") }
    val conflict = response.json[ErrorBody](409) { description("Stopped instance, duplicate name, stale reference or incompatible state") }
    val large = response.json[ErrorBody](413) { description("Browser result exceeds its limit") }
    val failed = response.json[ErrorBody](502) { description("Chrome protocol, navigation, script or action failed") }
    val unavailable = response.json[ErrorBody](503) { description("Chrome executable or display unavailable") }
    val timeout = response.json[ErrorBody](504) { description("Browser operation timed out") }
    recover { case e: ChromeFailure =>
      val body = ErrorBody(e.code, e.getMessage)
      e.code match
        case "invalid_chrome" => invalid(body)
        case "chrome_not_found" => missing(body)
        case "chrome_conflict" | "chrome_stopped" | "chrome_stale_ref" => conflict(body)
        case "chrome_limit" => large(body)
        case "chrome_unavailable" => unavailable(body)
        case "chrome_timeout" => timeout(body)
        case _ => failed(body)
    }

    commands("instance") {
      command[ChromeInstance]("start") {
        description("Start or reuse a named persistent profile; stop before changing its display mode or extraArgs")
        val name = param[String]("instanceName", "Persistent instance name")
        val kiosk = param[Boolean]("kiosk", "Full-screen kiosk window; incompatible with headless") { default(false) }
        val headless = param[Boolean]("headless", "Run without a graphical window") { default(false) }
        val args = param[List[String]]("extraArgs", "Additional Chrome switches, each --flag or --flag=value; appended after defaults. Profile, debugging and mode flags are managed") { default(List.empty[String]) }
        handle { service.start(name.value, kiosk.value, headless.value, args.value.toVector) }
      }
      command[Vector[ChromeInstance]]("list") {
        description("List managed running and stopped instances")
        handle { service.list }
      }
      command[ChromeInstance]("stop") {
        description("Close an instance and its tabs while retaining cookies and profile data")
        val name = param[String]("instanceName", "Instance name")
        handle { service.stop(name.value) }
      }
      command[Boolean]("purge") {
        description("Stop an instance and permanently remove its profile and metadata; false if absent")
        val name = param[String]("instanceName", "Instance name")
        handle { service.purge(name.value) }
      }
    }
    commands("tab") {
      val name = param[String]("instanceName", "Running instance name")
      command[ChromeTab]("new") {
        description("Create a named tab; names must be unique within the instance")
        val tab = param[String]("tabName", "New tab name")
        val url = param[String]("url", "Initial URL") { default("about:blank") }
        handle { service.newTab(name.value, tab.value, url.value) }
      }
      command[Vector[ChromeTab]]("list") {
        description("List every page tab; popups and manually opened tabs receive automatic names")
        handle { service.listTabs(name.value) }
      }
      command[Boolean]("delete") {
        description("Close a named tab; false if absent")
        val tab = param[String]("tabName", "Tab name")
        handle { service.deleteTab(name.value, tab.value) }
      }
      command[Unit]("activate") {
        description("Bring a named tab to the foreground")
        val tab = param[String]("tabName", "Tab name")
        handle { service.page(name.value, tab.value, 10000)(_.activate) }
      }
    }
    command[Vector[ChromeCookie]]("cookies") {
      description("Read cookies, including HttpOnly, for the exact domain and its subdomains")
      val name = param[String]("instanceName", "Running instance name")
      val domain = param[String]("domain", "Hostname without scheme, path or port")
      handle { service.cookies(name.value, domain.value) }
    }
    commands("page") {
      val name = param[String]("instanceName", "Running instance name")
      val tab = param[String]("tabName", "Tab name")
      val timeoutMs = param[Int]("timeoutMs", "Total operation timeout, including waiting for the instance") { default(30000); minimum(1); maximum(120000) }
      def onPage[A](f: ChromePage => cats.effect.IO[A])(using RequestContext): cats.effect.IO[A] =
        service.page(name.value, tab.value, timeoutMs.value)(f)
      command[PageInfo]("goto") {
        description("Navigate and wait for commit, domcontentloaded or load")
        val url = param[String]("url", "Destination URL")
        val wait = param[String]("waitUntil", "commit, domcontentloaded or load") { default("domcontentloaded") }
        handle { onPage(_.goto(url.value, wait.value)) }
      }
      command[PageSnapshot]("snapshot") {
        description("Read a tree with parent refs, DOM types and click hints; format=text is compact, interactiveOnly filters controls. Each snapshot replaces refs")
        val limit = param[Int]("maxNodes", "Maximum returned nodes") { default(1000); minimum(1); maximum(5000) }
        val format = param[String]("format", "json returns nodes; text returns a compact tree in text with an empty nodes array") { default("json") }
        val interactive = param[Boolean]("interactiveOnly", "Only controls and elements Chromium identifies as clickable; limits apply after filtering") { default(false) }
        handle { onPage(_.snapshot(limit.value, format.value, interactive.value)) }
      }
      command[Screenshot]("screenshot") {
        description("Save a PNG on the server; returns absolute path, MIME type and dimensions")
        val full = param[Boolean]("fullPage", "Capture the whole scrollable page") { default(false) }
        handle { onPage(_.screenshot(full.value)) }
      }
      for action <- Vector("click", "hover") do command[Unit](action) {
        description(s"${action.capitalize} a snapshot ref or unique CSS selector; text refs target their parent element; waits for visibility and actionability")
        val target = param[String]("target", "Snapshot ref or CSS selector, including open shadow roots")
        val frame = param[Option[String]]("frameId", "Frame from snapshot; default is the top frame; refs carry their frame")
        handle { onPage(p => if action == "click" then p.elements.click(target.value, frame.value) else p.elements.hover(target.value, frame.value)) }
      }
      command[Unit]("input") {
        description("Focus a text field and insert text; clear=true replaces its existing content")
        val target = param[String]("target", "Snapshot ref or unique CSS selector")
        val frame = param[Option[String]]("frameId", "Optional frame ID")
        val text = param[String]("text", "Text to insert, including empty text when clearing")
        val clear = param[Boolean]("clear", "Replace existing text") { default(true) }
        handle { onPage(_.elements.input(target.value, frame.value, text.value, clear.value)) }
      }
      command[Unit]("select") {
        description("Select an HTML select option by its value and emit input/change events")
        val target = param[String]("target", "Snapshot ref or unique CSS selector")
        val frame = param[Option[String]]("frameId", "Optional frame ID")
        val value = param[String]("value", "Option value")
        handle { onPage(_.elements.select(target.value, frame.value, value.value)) }
      }
      command[Unit]("check") {
        description("Set a checkbox or radio button to a desired checked state")
        val target = param[String]("target", "Snapshot ref or unique CSS selector")
        val frame = param[Option[String]]("frameId", "Optional frame ID")
        val checked = param[Boolean]("checked", "Desired state; radios only support true")
        handle { onPage(_.elements.check(target.value, frame.value, checked.value)) }
      }
      command[Unit]("press") {
        description("Send a key or chord to the focused element, e.g. Enter, Control+A or Shift+Tab")
        val key = param[String]("key", "Key or modifier chord")
        handle { onPage(_.elements.press(key.value)) }
      }
      command[Unit]("scroll") {
        description("Scroll to a target, or send a wheel event with pixel deltas when target is omitted")
        val target = param[Option[String]]("target", "Optional snapshot ref or unique CSS selector to reveal")
        val frame = param[Option[String]]("frameId", "Optional frame ID")
        val x = param[Double]("deltaX", "Horizontal scroll pixels") { default(0d) }
        val y = param[Double]("deltaY", "Vertical scroll pixels") { default(600d) }
        handle { onPage(_.elements.scrollPage(target.value, frame.value, x.value, y.value)) }
      }
      command[PageInfo]("wait") {
        description("Wait until all specified element, visible-text and exact-URL conditions hold")
        val target = param[Option[String]]("target", "Snapshot ref or unique CSS selector")
        val frame = param[Option[String]]("frameId", "Optional frame ID")
        val text = param[Option[String]]("text", "Text contained in the frame body")
        val url = param[Option[String]]("url", "Exact frame URL")
        val state = param[String]("state", "visible, hidden, attached or detached") { default("visible") }
        handle { onPage(_.waitFor(target.value, frame.value, text.value, url.value, state.value)) }
      }
      command[Json]("evaluate") {
        description("Evaluate JavaScript in the page main world, await promises and return JSON; undefined becomes null")
        val expression = body.text("JavaScript expression; this is the browser environment, without tools.*")
        handle { onPage(_.evaluate(expression.value, timeoutMs.value)) }
      }
      command[PageInfo]("reload") {
        description("Reload and wait for the new document")
        val wait = param[String]("waitUntil", "commit, domcontentloaded or load") { default("domcontentloaded") }
        handle { onPage(_.reload(wait.value)) }
      }
      for (action, delta) <- Vector("back" -> -1, "forward" -> 1) do command[PageInfo](action) {
        description(s"Navigate $action in this tab's history")
        handle { onPage(_.history(delta)) }
      }
    }
  }
