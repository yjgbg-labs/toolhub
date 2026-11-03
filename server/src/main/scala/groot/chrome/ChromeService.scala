package groot.chrome

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import framework.model.JsonModel
import io.circe.Json
import java.net.http.HttpClient
import java.nio.file.Path
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

/** Named persistent profiles. Lifecycle and page mutations are serialized per instance. */
final class ChromeService private (store: ChromeStore, client: HttpClient,
    entries: Ref[IO, Map[String, ChromeService.Entry]], lifecycle: Semaphore[IO], executable: () => String):
  import ChromeService.*
  import ChromeJson.*

  private def entry(name: String): IO[Entry] = IO(ChromeValidation.name(name)) *> entries.get.flatMap(_.get(name)
    .liftTo[IO](ChromeFailure("chrome_not_found", s"Unknown instance: $name")))
  private def save(e: Entry, state: SavedInstance): IO[Unit] = store.save(state) *> IO { e.state = state }
  private def process(e: Entry): Option[ProcessHandle] =
    e.running.map(_._1.process).filter(_.isAlive).orElse(store.owned(e.state))
  private def info(e: Entry): ChromeInstance =
    val handle = process(e)
    ChromeInstance(e.state.instanceName, if handle.nonEmpty then "running" else "stopped",
      e.state.kiosk, e.state.headless, handle.map(_.pid()), store.profile(e.state.instanceName).toString)

  private def connect(name: String, process: ProcessHandle): Resource[IO, Live] = for
    endpoint <- Resource.eval(store.endpoint(name, process))
    cdp <- Cdp.connect(client, endpoint)
    _ <- Resource.make(IO.unit)(_ => cdp.call("Browser.close").void.timeout(2.seconds).attempt.void *>
      IO.fromCompletableFuture(IO(process.onExit())).void.timeoutTo(5.seconds, IO.unit))
  yield new Live(process, cdp)

  private def live(e: Entry): IO[Live] = IO.defer {
    e.running match
      case Some((value, _)) if value.process.isAlive && value.cdp.isOpen => IO.pure(value)
      case _ => process(e) match
        case None => IO.raiseError(ChromeFailure("chrome_stopped", s"Start instance ${e.state.instanceName} first"))
        case Some(process) => IO.uncancelable { poll =>
          // Reconnect after a transport failure without terminating the owned browser.
          e.running.traverse_(_._1.cdp.close) *>
            poll(connect(e.state.instanceName, process).allocated).flatMap { allocated =>
              IO { e.running = Some((allocated._1, allocated._2.guarantee(ChromeStore.terminate(process)))); allocated._1 }
            }
        }
  }
  private def withLive[A](name: String)(f: (Entry, Live) => IO[A]): IO[A] = entry(name).flatMap { e =>
    e.gate.permit.use(_ => live(e).flatMap(value => f(e, value)))
  }

  def list: IO[Vector[ChromeInstance]] = entries.get.flatMap(_.values.toVector.traverse { e =>
    e.gate.permit.use(_ => IO.blocking(info(e)))
  }).map(_.sortBy(_.instanceName))

  def start(name: String, kiosk: Boolean, headless: Boolean): IO[ChromeInstance] =
    IO {
      ChromeValidation.name(name)
      if kiosk && headless then throw ChromeFailure("invalid_chrome", "kiosk and headless cannot both be true")
    } *> lifecycle.permit.use { _ => for
      existing <- entries.get.map(_.get(name))
      e <- existing.fold(for
        gate <- Semaphore[IO](1)
        value = new Entry(SavedInstance(name, kiosk, headless), gate)
        _ <- store.prepare(name) *> store.save(value.state)
        _ <- entries.update(_ + (name -> value))
      yield value)(IO.pure)
      result <- e.gate.permit.use { _ => IO.defer {
        process(e) match
          case Some(_) =>
            IO.raiseWhen(e.state.kiosk != kiosk || e.state.headless != headless)(ChromeFailure("chrome_conflict", "Stop the instance before changing kiosk/headless")) *>
              live(e).as(info(e))
          case None => IO.uncancelable { poll => for
            _ <- e.running.traverse_(_._2) *> IO { e.running = None }
            next = SavedInstance(name, kiosk, headless)
            allocated <- poll((for
              process <- Resource.make(ChromeStore.launch(executable(), store, next))(ChromeStore.terminate)
              live <- connect(name, process)
            yield live).allocated)
            (value, release) = allocated
            state = next.copy(pid = Some(value.process.pid()), startedAt = value.process.info().startInstant().toScala.map(_.toString))
            _ <- save(e, state).onError(_ => release)
            _ <- IO { e.running = Some(allocated) }
          yield info(e) }
      }}
    yield result }

  private def stopEntry(e: Entry): IO[ChromeInstance] = IO.uncancelable { _ =>
    val release = e.running.map(_._2).getOrElse(store.owned(e.state).traverse_(ChromeStore.terminate))
    release *> IO { e.running = None } *> save(e, e.state.copy(pid = None, startedAt = None, tabs = Map.empty)) *> IO.blocking(info(e))
  }
  def stop(name: String): IO[ChromeInstance] = lifecycle.permit.use { _ => entry(name).flatMap(e => e.gate.permit.use(_ => stopEntry(e))) }
  def purge(name: String): IO[Boolean] = IO(ChromeValidation.name(name)) *> lifecycle.permit.use { _ =>
    entries.get.map(_.get(name)).flatMap {
      case None => IO.pure(false)
      case Some(e) => e.gate.permit.use { _ => IO.uncancelable { _ =>
        stopEntry(e) *> store.remove(name) *> entries.update(_ - name).as(true)
      }}
    }
  }

  private def tabs(e: Entry, value: Live): IO[Vector[ChromeTab]] = value.cdp.call("Target.getTargets").flatMap { json =>
    val targets = array(json, "targetInfos").filter(t => text(t, "type") == "page")
    val ids = targets.map(text(_, "targetId")).toSet
    var names = e.state.tabs.filter((id, _) => ids.contains(id))
    targets.foreach { t =>
      val id = text(t, "targetId")
      if !names.contains(id) then
        val name = Iterator.from(1).map(i => s"tab-$i").find(n => !names.values.exists(_ == n)).get
        names += id -> name
    }
    value.pages.keys.filterNot(ids).toVector.foreach(value.pages.remove)
    val persist = if names == e.state.tabs then IO.unit else save(e, e.state.copy(tabs = names))
    persist.as(targets.map(t => ChromeTab(names(text(t, "targetId")), text(t, "targetId"), text(t, "url"), text(t, "title"))).sortBy(_.tabName))
  }
  def listTabs(name: String): IO[Vector[ChromeTab]] = withLive(name)(tabs)
  def newTab(name: String, tab: String, url: String): IO[ChromeTab] = IO(ChromeValidation.name(tab)) *> withLive(name) { (e, value) => for
    all <- tabs(e, value)
    _ <- IO.raiseWhen(all.exists(_.tabName == tab))(ChromeFailure("chrome_conflict", s"Tab already exists: $tab"))
    _ <- IO(ChromePage.validateUrl(url))
    created <- value.cdp.call("Target.createTarget", obj("url" -> str(url)))
    id = text(created, "targetId")
    _ <- save(e, e.state.copy(tabs = e.state.tabs + (id -> tab))).onError(_ => value.cdp.call("Target.closeTarget", obj("targetId" -> str(id))).void)
    current <- tabs(e, value)
    result <- current.find(_.tabName == tab).liftTo[IO](ChromeFailure("chrome_not_found", "New tab closed during creation"))
  yield result }
  def deleteTab(name: String, tab: String): IO[Boolean] = IO(ChromeValidation.name(tab)) *> withLive(name) { (e, value) => for
    all <- tabs(e, value)
    found = all.find(_.tabName == tab)
    _ <- found.traverse_(t => value.cdp.call("Target.closeTarget", obj("targetId" -> str(t.targetId))).void *>
      save(e, e.state.copy(tabs = e.state.tabs - t.targetId)) *> IO { value.pages.remove(t.targetId); () })
  yield found.nonEmpty }

  private[chrome] def page[A](name: String, tab: String, timeoutMs: Int)(f: ChromePage => IO[A]): IO[A] =
    IO { ChromeValidation.name(tab); ChromeValidation.timeout(timeoutMs) } *> withLive(name) { (e, value) => for
      all <- tabs(e, value)
      target <- all.find(_.tabName == tab).liftTo[IO](ChromeFailure("chrome_not_found", s"Unknown tab: $tab"))
      page <- value.pages.get(target.targetId).fold(for
        attached <- value.cdp.call("Target.attachToTarget", obj("targetId" -> str(target.targetId), "flatten" -> Json.True))
        p = new ChromePage(value.cdp, text(attached, "sessionId"), store.home.resolve("screenshots"))
        _ <- p.initialize
        _ <- IO { value.pages(target.targetId) = p }
      yield p)(IO.pure)
      result <- f(page)
    yield result }.timeoutTo(timeoutMs.millis, IO.raiseError(ChromeFailure("chrome_timeout", s"Page operation timed out after ${timeoutMs}ms")))

  def cookies(name: String, domain: String): IO[Vector[ChromeCookie]] = IO(ChromeValidation.domain(domain)).flatMap { host =>
    withLive(name) { (_, value) => value.cdp.call("Storage.getCookies").flatMap { json =>
      array(json, "cookies").filter(c => ChromeValidation.matchesDomain(text(c, "domain"), host)).traverse(c =>
        IO.fromEither(JsonModel[ChromeCookie].decoder.decodeJson(c)))
    }}
  }
  private def close: IO[Unit] = entries.get.flatMap(_.values.toVector.parTraverse_(e => e.gate.permit.use(_ => stopEntry(e)).attempt.void))

object ChromeService:
  private class Entry(var state: SavedInstance, val gate: Semaphore[IO]):
    var running: Option[(Live, IO[Unit])] = None
  private class Live(val process: ProcessHandle, val cdp: Cdp):
    val pages = mutable.Map.empty[String, ChromePage]
  def resource(home: Path = ChromeStore.defaultHome, executable: () => String = () => ChromeStore.binary): Resource[IO, ChromeService] = for
    store <- ChromeStore.resource(home)
    client <- Resource.fromAutoCloseable(IO(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()))
    states <- Resource.eval(store.load)
    initial <- Resource.eval(states.traverse(s => Semaphore[IO](1).map(gate => s.instanceName -> new Entry(s, gate))))
    entries <- Resource.eval(Ref.of[IO, Map[String, Entry]](initial.toMap))
    lifecycle <- Resource.eval(Semaphore[IO](1))
    service <- Resource.make(IO(new ChromeService(store, client, entries, lifecycle, executable)))(_.close)
  yield service
