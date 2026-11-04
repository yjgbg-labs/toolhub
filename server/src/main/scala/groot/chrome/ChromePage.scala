package groot.chrome

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import java.nio.file.{Files, Path}
import java.util.{Base64, UUID}
import scala.collection.mutable
import scala.concurrent.duration.*

private[chrome] case class PageFrame(id: String, loader: String, session: String)
private[chrome] case class ElementRef(frame: String, loader: String, backend: Int)

private[chrome] final class ChromePage(val cdp: Cdp, val session: String, screenshots: Path):
  import ChromeJson.*
  private val initialized = mutable.Set.empty[String]
  val refs = mutable.Map.empty[String, ElementRef]
  val elements = new PageElements(this)
  def call(method: String, params: Json = Json.obj()): IO[Json] = cdp.call(method, params, Some(session))
  private def initializeSession(id: String): IO[Unit] = IO.defer {
    if initialized.contains(id) then IO.unit else
      cdp.call("Page.enable", session = Some(id)) *>
        cdp.call("Target.setAutoAttach", obj("autoAttach" -> Json.True, "waitForDebuggerOnStart" -> Json.False, "flatten" -> Json.True), Some(id)) *>
        IO { initialized += id; () }
  }
  def initialize: IO[Unit] = initializeSession(session)
  def activate: IO[Unit] = call("Page.bringToFront").void
  def info: IO[PageInfo] = evaluate("({url:location.href,title:document.title})", 10000).map(j => PageInfo(text(j, "url"), text(j, "title")))

  private def result(json: Json): IO[Json] =
    val error = field(json, "exceptionDetails")
    if !error.isNull then IO.raiseError(ChromeFailure("chrome_script_error",
      Option(text(field(error, "exception"), "description")).filter(_.nonEmpty).getOrElse(text(error, "text"))))
    else IO.pure(field(json, "result"))

  def remote(expression: String, frame: PageFrame, byValue: Boolean = true): IO[Json] = for
    world <- cdp.call("Page.createIsolatedWorld", obj("frameId" -> str(frame.id), "worldName" -> str("groot")), Some(frame.session))
    response <- cdp.call("Runtime.evaluate", obj("expression" -> str(expression), "contextId" -> field(world, "executionContextId"),
      "returnByValue" -> Json.fromBoolean(byValue)), Some(frame.session))
    value <- result(response)
  yield if byValue then field(value, "value") else value

  def function(id: String, frame: PageFrame, declaration: String, arguments: Vector[Json] = Vector.empty): IO[Json] =
    cdp.call("Runtime.callFunctionOn", obj("objectId" -> str(id), "functionDeclaration" -> str(declaration),
      "arguments" -> Json.fromValues(arguments.map(v => obj("value" -> v))), "returnByValue" -> Json.True), Some(frame.session))
      .flatMap(result).map(field(_, "value"))

  def evaluate(expression: String, timeoutMs: Int): IO[Json] =
    IO.raiseWhen(expression.length > 1024 * 1024)(ChromeFailure("invalid_chrome", "Expression exceeds 1 MiB")) *>
      call("Runtime.evaluate", obj("expression" -> str(expression), "returnByValue" -> Json.True,
        "awaitPromise" -> Json.True, "userGesture" -> Json.True, "timeout" -> Json.fromInt(timeoutMs))).flatMap(result).flatMap { remote =>
        if text(remote, "type") == "undefined" then IO.pure(Json.Null)
        else remote.hcursor.downField("value").focus.liftTo[IO](ChromeFailure("chrome_script_error", "Result must be JSON-serializable"))
      }.onCancel(call("Runtime.terminateExecution").void.timeout(2.seconds).attempt.void)

  private def frameNodes(tree: Json): Vector[Json] =
    Vector(field(tree, "frame")) ++ array(tree, "childFrames").flatMap(frameNodes)
  def frames: IO[Vector[Json]] =
    def loop(id: String, seen: Set[String]): IO[Vector[Json]] =
      if seen.contains(id) || seen.size > 64 then IO.raiseError(ChromeFailure("chrome_limit", "Frame nesting exceeds 64 sessions"))
      else for
        _ <- initializeSession(id)
        tree <- cdp.call("Page.getFrameTree", session = Some(id))
        children <- cdp.childFrames(id).traverse(child => loop(child, seen + id))
      yield frameNodes(field(tree, "frameTree")).map(_.deepMerge(obj("_session" -> str(id)))) ++ children.flatten
    IO { initialized.filterInPlace(id => id == session || cdp.hasSession(id)); () } *> loop(session, Set.empty).map { all =>
      val last = all.map(n => text(n, "id") -> n).toMap
      all.map(text(_, "id")).distinct.map(last)
    }
  private def asFrame(value: Json): PageFrame = PageFrame(text(value, "id"), text(value, "loaderId"), text(value, "_session"))
  def frame(requested: Option[String]): IO[PageFrame] = for
    all <- frames
    value <- requested.fold(all.headOption)(id => all.find(text(_, "id") == id))
      .liftTo[IO](ChromeFailure("chrome_not_found", "Frame does not belong to this tab"))
  yield asFrame(value)

  def snapshot(maxNodes: Int, format: String = "json", interactiveOnly: Boolean = false): IO[PageSnapshot] =
    new PageSnapshots(this).capture(maxNodes, format, interactiveOnly)

  private def ready(waitUntil: String, loader: Option[String] = None, previous: Option[String] = None): IO[PageInfo] =
    def check: IO[Boolean] = for
      current <- frame(None)
      state <- evaluate("document.readyState", 1000)
    yield loader.forall(_ == current.loader) && previous.forall(_ != current.loader) &&
      (waitUntil == "commit" || state.asString.contains("complete") || (waitUntil == "domcontentloaded" && state.asString.contains("interactive")))
    def loop: IO[PageInfo] = check.handleErrorWith {
      case e: ChromeFailure if Set("cdp_error", "chrome_script_error").contains(e.code) => IO.pure(false)
      case error => IO.raiseError(error)
    }.flatMap(done => if done then info else IO.sleep(75.millis) *> loop)
    loop

  def goto(url: String, waitUntil: String): IO[PageInfo] = for
    _ <- IO { ChromePage.validateUrl(url); ChromePage.validateWait(waitUntil) }
    response <- call("Page.navigate", obj("url" -> str(url)))
    _ <- IO.raiseWhen(text(response, "errorText").nonEmpty)(ChromeFailure("chrome_navigation_failed", text(response, "errorText")))
    value <- ready(waitUntil, response.hcursor.get[String]("loaderId").toOption)
  yield value
  def reload(waitUntil: String): IO[PageInfo] = for
    _ <- IO(ChromePage.validateWait(waitUntil))
    before <- frame(None)
    _ <- call("Page.reload")
    value <- ready(waitUntil, previous = Some(before.loader))
  yield value
  def history(delta: Int): IO[PageInfo] = for
    history <- call("Page.getNavigationHistory")
    current = history.hcursor.get[Int]("currentIndex").getOrElse(0)
    target <- array(history, "entries").lift(current + delta).liftTo[IO](ChromeFailure("chrome_conflict", "No history entry in that direction"))
    _ <- call("Page.navigateToHistoryEntry", obj("entryId" -> field(target, "id")))
    _ <- waitFor(None, None, None, Some(text(target, "url")), "visible")
    value <- ready("domcontentloaded")
  yield value

  def waitFor(target: Option[String], frameId: Option[String], containsText: Option[String], url: Option[String], state: String): IO[PageInfo] =
    if target.isEmpty && containsText.isEmpty && url.isEmpty then IO.raiseError(ChromeFailure("invalid_chrome", "Specify target, text or url"))
    else if !Set("visible", "hidden", "attached", "detached").contains(state) then IO.raiseError(ChromeFailure("invalid_chrome", "state must be visible, hidden, attached or detached"))
    else
      def loop: IO[PageInfo] = (for
        f <- frame(frameId)
        textOk <- containsText.fold(IO.pure(true))(t => remote(s"document.body?.innerText.includes(${str(t).noSpaces}) ?? false", f).map(_.asBoolean.contains(true)))
        urlOk <- url.fold(IO.pure(true))(u => remote(s"location.href === ${str(u).noSpaces}", f).map(_.asBoolean.contains(true)))
        targetOk <- target.fold(IO.pure(true))(t => elements.matches(t, frameId, state))
      yield textOk && urlOk && targetOk).flatMap(done => if done then info else IO.sleep(75.millis) *> loop)
      loop

  def screenshot(fullPage: Boolean): IO[Screenshot] = for
    layout <- call("Page.getLayoutMetrics")
    size = field(layout, if fullPage then "cssContentSize" else "cssLayoutViewport")
    width = math.ceil(size.hcursor.get[Double](if fullPage then "width" else "clientWidth").getOrElse(0d)).toInt
    height = math.ceil(size.hcursor.get[Double](if fullPage then "height" else "clientHeight").getOrElse(0d)).toInt
    _ <- IO.raiseWhen(width <= 0 || height <= 0 || width.toLong * height > 32000000)(ChromeFailure("chrome_limit", "Screenshot must contain 1–32 million pixels"))
    clip = if fullPage then obj("clip" -> obj("x" -> Json.fromInt(0), "y" -> Json.fromInt(0), "width" -> Json.fromInt(width),
      "height" -> Json.fromInt(height), "scale" -> Json.fromInt(1))) else obj()
    response <- call("Page.captureScreenshot", obj("format" -> str("png"), "captureBeyondViewport" -> Json.fromBoolean(fullPage)).deepMerge(clip))
    output <- IO.blocking {
      val path = screenshots.resolve(UUID.randomUUID().toString + ".png")
      val bytes = Base64.getDecoder.decode(text(response, "data"))
      val header = java.nio.ByteBuffer.wrap(bytes)
      val pixelWidth = header.getInt(16)
      val pixelHeight = header.getInt(20)
      Files.write(path, bytes)
      Screenshot(path.toString, "image/png", pixelWidth, pixelHeight)
    }
  yield output

private[chrome] object ChromePage:
  def validateUrl(url: String): Unit =
    if url.length > 1024 * 1024 || !url.matches("(?s)^(https?://.+|about:blank|data:.+|file:///.*)$") then
      throw ChromeFailure("invalid_chrome", "URL must use http, https, file, data or about:blank")
  def validateWait(value: String): Unit =
    if !Set("commit", "domcontentloaded", "load").contains(value) then
      throw ChromeFailure("invalid_chrome", "waitUntil must be commit, domcontentloaded or load")
