package groot.chrome

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import scala.concurrent.duration.*

/** References resolve to live DOM nodes; selectors traverse open shadow roots in the chosen frame. */
private[chrome] final class PageElements(page: ChromePage):
  import ChromeJson.*
  private case class Element(id: String, frame: PageFrame, reference: Boolean)
  private def resolve(target: String, frameId: Option[String]): Resource[IO, Element] = Resource.make(for
    _ <- IO.raiseWhen(target.isEmpty || target.length > 4096)(ChromeFailure("invalid_chrome", "target must contain 1–4096 characters"))
    ref <- if target.startsWith("ref:") then page.refs.get(target).map(Some(_))
      .liftTo[IO](ChromeFailure("chrome_stale_ref", "Reference expired; take a new snapshot")) else IO.pure(None)
    _ <- IO.raiseWhen(ref.exists(r => frameId.exists(_ != r.frame)))(ChromeFailure("invalid_chrome", "Reference belongs to another frame"))
    frame <- page.frame(ref.map(_.frame).orElse(frameId))
    _ <- IO.raiseWhen(ref.exists(_.loader != frame.loader))(ChromeFailure("chrome_stale_ref", "Page navigated; take a new snapshot"))
    remote <- ref match
      case Some(r) => page.cdp.call("DOM.resolveNode", obj("backendNodeId" -> Json.fromInt(r.backend)), Some(frame.session))
        .map(field(_, "object")).adaptError { case _: ChromeFailure => ChromeFailure("chrome_stale_ref", "Node no longer exists; take a new snapshot") }
      case None => page.remote(s"""(() => {
        const found=[];
        function visit(root) {
          found.push(...root.querySelectorAll(${str(target).noSpaces}));
          for (const node of root.querySelectorAll('*')) if (node.shadowRoot) visit(node.shadowRoot);
        }
        visit(document);
        if(found.length>1) throw new Error('Selector matches multiple elements; use a snapshot ref or a more specific selector');
        return found[0] ?? null;
      })()""", frame, byValue = false)
    id <- Option(text(remote, "objectId")).filter(_.nonEmpty).liftTo[IO](ChromeFailure("element_not_ready", "Element not found"))
  yield Element(id, frame, ref.nonEmpty))(e => page.cdp.call("Runtime.releaseObject", obj("objectId" -> str(e.id)), Some(e.frame.session)).void.timeout(2.seconds).attempt.void)

  private def retry[A](action: IO[A]): IO[A] = action.handleErrorWith {
    case e: ChromeFailure if e.code == "element_not_ready" => IO.sleep(75.millis) *> retry(action)
    case error => IO.raiseError(error)
  }
  private def inspect(e: Element, declaration: String): IO[Json] = page.function(e.id, e.frame, declaration)
  private val stateFunction = """function() {
    if (!this.isConnected) return {connected:false};
    const r=this.getBoundingClientRect?.();
    const s=this.nodeType===1 ? getComputedStyle(this) : null;
    return {connected:true,visible:!!r && r.width>0 && r.height>0 && s?.visibility!=='hidden' && s?.display!=='none',
      enabled: !this.matches?.(':disabled') && this.getAttribute?.('aria-disabled')!=='true',
      editable: this.isContentEditable || this.tagName==='TEXTAREA' || (this.tagName==='INPUT' && !['checkbox','radio','button','submit','file','hidden'].includes(this.type)),
      readOnly: !!this.readOnly};
  }"""
  private def ensure(e: Element, editable: Boolean = false): IO[Unit] = inspect(e, stateFunction).flatMap { s =>
    if !flag(s, "connected") then IO.raiseError(ChromeFailure(if e.reference then "chrome_stale_ref" else "element_not_ready", "Element is detached"))
    else if !flag(s, "visible") || !flag(s, "enabled") || (editable && flag(s, "readOnly")) then
      IO.raiseError(ChromeFailure("element_not_ready", "Element is not visible, enabled or editable"))
    else if editable && !flag(s, "editable") then IO.raiseError(ChromeFailure("invalid_chrome", "Element does not accept text input"))
    else IO.unit
  }
  private def scroll(e: Element): IO[Unit] = page.cdp.call("DOM.scrollIntoViewIfNeeded", obj("objectId" -> str(e.id)), Some(e.frame.session)).void

  private def point(e: Element): IO[(Double, Double)] = for
    _ <- ensure(e) *> scroll(e)
    hit <- inspect(e, """function() {
      const r=this.getBoundingClientRect();
      const x=Math.max(0,Math.min(innerWidth,r.right)+Math.max(0,r.left))/2;
      const y=Math.max(0,Math.min(innerHeight,r.bottom)+Math.max(0,r.top))/2;
      const hit=this.getRootNode().elementFromPoint(x,y);
      return hit===this || this.contains(hit);
    }""")
    _ <- IO.raiseUnless(hit.asBoolean.contains(true))(ChromeFailure("element_not_ready", "Element is covered by another element"))
    quads <- page.cdp.call("DOM.getContentQuads", obj("objectId" -> str(e.id)), Some(e.frame.session))
    quad <- array(quads, "quads").headOption.flatMap(_.asArray).filter(_.size == 8)
      .liftTo[IO](ChromeFailure("element_not_ready", "Element has no clickable box"))
    values = quad.map(_.asNumber.map(_.toDouble).getOrElse(0d))
  yield ((values(0) + values(2) + values(4) + values(6)) / 4, (values(1) + values(3) + values(5) + values(7)) / 4)

  private def pointer(e: Element, click: Boolean): IO[Unit] = point(e).flatMap { (x, y) =>
    val base = obj("x" -> Json.fromDoubleOrNull(x), "y" -> Json.fromDoubleOrNull(y))
    def dispatch(kind: String, button: String, count: Int) = page.cdp.call("Input.dispatchMouseEvent", base.deepMerge(obj(
      "type" -> str(kind), "button" -> str(button), "clickCount" -> Json.fromInt(count))), Some(e.frame.session)).void
    if click then dispatch("mousePressed", "left", 1).guarantee(dispatch("mouseReleased", "left", 1).timeout(2.seconds).attempt.void)
    else dispatch("mouseMoved", "none", 0)
  }
  def click(target: String, frame: Option[String]): IO[Unit] = retry(resolve(target, frame).use(pointer(_, true)))
  def hover(target: String, frame: Option[String]): IO[Unit] = retry(resolve(target, frame).use(pointer(_, false)))
  def input(target: String, frame: Option[String], value: String, clear: Boolean): IO[Unit] =
    IO.raiseWhen(value.length > 65536)(ChromeFailure("invalid_chrome", "Input exceeds 65536 characters")) *>
      retry(resolve(target, frame).use { e => for
        _ <- ensure(e, editable = true) *> scroll(e)
        _ <- page.cdp.call("DOM.focus", obj("objectId" -> str(e.id)), Some(e.frame.session))
        _ <- if clear then pressOn("Control+A", e.frame.session) *> pressOn("Backspace", e.frame.session) else IO.unit
        _ <- page.cdp.call("Input.insertText", obj("text" -> str(value)), Some(e.frame.session))
      yield () })
  def select(target: String, frame: Option[String], value: String): IO[Unit] = retry(resolve(target, frame).use { e =>
    ensure(e) *> page.function(e.id, e.frame, """function(value) {
      if(this.tagName!=='SELECT') throw new Error('Target is not a select element');
      const option=Array.from(this.options).find(o=>o.value===value);
      if(!option || option.disabled) throw new Error('Option not found or disabled');
      this.value=value;
      this.dispatchEvent(new Event('input',{bubbles:true}));
      this.dispatchEvent(new Event('change',{bubbles:true}));
    }""", Vector(str(value))).void
  })
  def check(target: String, frame: Option[String], checked: Boolean): IO[Unit] = retry(resolve(target, frame).use { e => for
    _ <- ensure(e)
    state <- inspect(e, """function() { if(this.tagName!=='INPUT' || !['checkbox','radio'].includes(this.type)) throw new Error('Target is not a checkbox or radio'); return {checked:this.checked,type:this.type}; }""")
    _ <- IO.raiseWhen(text(state, "type") == "radio" && !checked)(ChromeFailure("invalid_chrome", "A radio button can only be checked"))
    _ <- if flag(state, "checked") == checked then IO.unit else pointer(e, true)
    actual <- inspect(e, "function(){return this.checked}")
    _ <- IO.raiseUnless(actual.asBoolean.contains(checked))(ChromeFailure("chrome_action_failed", "Page did not retain the requested checked state"))
  yield () })

  def matches(target: String, frame: Option[String], state: String): IO[Boolean] = resolve(target, frame).use { e =>
    inspect(e, stateFunction).map { s => state match
      case "attached" => flag(s, "connected")
      case "detached" => !flag(s, "connected")
      case "visible" => flag(s, "connected") && flag(s, "visible")
      case "hidden" => !flag(s, "connected") || !flag(s, "visible")
    }
  }.handleErrorWith {
    case e: ChromeFailure if e.code == "element_not_ready" => IO.pure(state == "hidden" || state == "detached")
    case error => IO.raiseError(error)
  }

  def scrollPage(target: Option[String], frame: Option[String], x: Double, y: Double): IO[Unit] =
    if !x.isFinite || !y.isFinite || math.abs(x) > 100000 || math.abs(y) > 100000 then
      IO.raiseError(ChromeFailure("invalid_chrome", "Scroll deltas must be finite and at most 100000 pixels"))
    else target match
      case Some(t) => retry(resolve(t, frame).use(e => ensure(e) *> scroll(e)))
      case None => page.frame(frame).flatMap(f => page.cdp.call("Input.dispatchMouseEvent", obj("type" -> str("mouseWheel"),
        "x" -> Json.fromInt(100), "y" -> Json.fromInt(100), "deltaX" -> Json.fromDoubleOrNull(x), "deltaY" -> Json.fromDoubleOrNull(y)), Some(f.session)).void)

  def press(key: String): IO[Unit] = pressOn(key, page.session)
  private def pressOn(value: String, session: String): IO[Unit] = IO.defer {
    val parts = value.split("\\+", -1).toVector
    val modifiers = Map("Alt" -> 1, "Control" -> 2, "Ctrl" -> 2, "Meta" -> 4, "Shift" -> 8)
    val known = Map("Backspace" -> 8, "Tab" -> 9, "Enter" -> 13, "Escape" -> 27, "Space" -> 32,
      "PageUp" -> 33, "PageDown" -> 34, "End" -> 35, "Home" -> 36,
      "ArrowLeft" -> 37, "ArrowUp" -> 38, "ArrowRight" -> 39, "ArrowDown" -> 40, "Delete" -> 46)
    val key = parts.last
    if parts.init.exists(p => !modifiers.contains(p)) || !(known.contains(key) || key.length == 1 || key.matches("F([1-9]|1[0-2])")) then
      IO.raiseError(ChromeFailure("invalid_chrome", "Unknown key or modifier; examples: Enter, Control+A, Shift+Tab"))
    else
      val mask = parts.init.map(modifiers).foldLeft(0)(_ | _)
      val actual = if key == "Space" then " " else key
      val number = known.getOrElse(key, if key.startsWith("F") && key.length > 1 then 111 + key.drop(1).toInt else key.toUpperCase.charAt(0).toInt)
      val code = if key.length == 1 && key.head.isLetter then "Key" + key.toUpperCase else if key.length == 1 && key.head.isDigit then "Digit" + key else key
      val typed = if (mask & 7) != 0 then "" else if key == "Enter" then "\r" else if actual.length == 1 then actual else ""
      val base = obj("key" -> str(actual), "code" -> str(code), "windowsVirtualKeyCode" -> Json.fromInt(number), "modifiers" -> Json.fromInt(mask))
      def dispatch(up: Boolean) = page.cdp.call("Input.dispatchKeyEvent", base.deepMerge(obj("type" -> str(if up then "keyUp" else "keyDown"),
        "text" -> str(if up then "" else typed))), Some(session)).void
      dispatch(false).guarantee(dispatch(true).timeout(2.seconds).attempt.void)
  }
