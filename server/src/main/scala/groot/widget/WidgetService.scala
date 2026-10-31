package groot.widget

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import framework.model.JsonModel
import fs2.Stream
import fs2.concurrent.SignallingRef
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.concurrent.duration.*

final class WidgetService private (
    store: WidgetStore, state: SignallingRef[IO, WidgetState], mutex: Mutex[IO], heartbeat: FiniteDuration
):
  import WidgetService.*

  def show: IO[Vector[WidgetItem]] = state.get.map(_.items)
  def config: IO[WidgetSettings] = state.get.map(_.settings)

  def set(input: WidgetUpdate): IO[WidgetItem] = update { (current, now) =>
    key(input.key)
    val label = input.label.trim
    val value = input.value.map(_.trim).filter(_.nonEmpty)
    requireValid(label.nonEmpty && count(label) <= 24, "Label must contain 1 to 24 characters")
    requireValid(count(label) + value.fold(0)(count) <= 40, "Label and value must total at most 40 characters")
    requireValid(!(label + value.getOrElse("")).exists(Character.isISOControl), "Label and value must be single-line text")
    val item = WidgetItem(input.key, label, value, input.color, input.order, now)
    val items = (current.items.filterNot(_.key == input.key) :+ item).sortBy(item => (item.order, item.key))
    if items.size > maxItems then throw WidgetLimit(s"Widget accepts at most $maxItems items")
    (current.copy(items = items), item)
  }

  def delete(name: String): IO[Boolean] = update { (current, _) =>
    key(name)
    val items = current.items.filterNot(_.key == name)
    (current.copy(items = items), items.size != current.items.size)
  }

  def writeConfig(settings: WidgetSettings): IO[WidgetSettings] = update { (current, _) =>
    validateSettings(settings)
    (current.copy(settings = settings), settings)
  }

  private def update[A](f: (WidgetState, String) => (WidgetState, A)): IO[A] = mutex.lock.surround(IO.uncancelable { _ =>
    for
      current <- state.get
      now <- IO.realTimeInstant
      changed <- IO(f(current, now.toString))
      (candidate, result) = changed
      _ <- if candidate == current then IO.unit else
        val next = candidate.copy(revision = Math.addExact(current.revision, 1L))
        IO(validateState(next)) *> store.save(next) *> state.set(next)
    yield result
  })

  /** Signals carry no old payload: every notification/heartbeat reads the newest committed snapshot. */
  def subscribe: Stream[IO, WidgetSnapshot] =
    state.discrete.void.merge(Stream.awakeEvery[IO](heartbeat).void).evalMap { _ =>
      for
        current <- state.get
        now <- IO.realTimeInstant
      yield WidgetSnapshot(current.revision.toString, now.toString, current.items, current.settings)
    }

object WidgetService:
  val maxItems = 256
  private def count(text: String): Int = text.codePointCount(0, text.length)
  private def requireValid(valid: Boolean, message: String): Unit = if !valid then throw InvalidWidget(message)
  private def key(value: String): Unit =
    requireValid(value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"), "Key must contain 1 to 128 letters, digits, dots, underscores or hyphens")

  private def validateSettings(value: WidgetSettings): Unit =
    requireValid(value.x.isDefined == value.y.isDefined, "x and y must be supplied together")
    requireValid((value.x.toList ++ value.y.toList).forall(n => n >= -65536 && n <= 65536), "Window position is out of range")
    requireValid(value.width >= 240 && value.width <= 1600, "Width must be between 240 and 1600")
    requireValid(value.opacity.isFinite && value.opacity >= 0.2 && value.opacity <= 1, "Opacity must be between 0.2 and 1")
    requireValid(value.fontSize >= 8 && value.fontSize <= 32, "Font size must be between 8 and 32")
    requireValid(value.rowHeight >= math.max(24, value.fontSize + 8) && value.rowHeight <= 120, "Row height must be between max(24, fontSize+8) and 120")

  private def validateState(state: WidgetState): Unit =
    validateSettings(state.settings)
    requireValid(state.revision >= 0, "Invalid widget revision")
    if JsonModel[WidgetState].encoder(state).noSpaces.getBytes(UTF_8).length > 512 * 1024 then
      throw WidgetLimit("Widget state exceeds 512 KiB")

  def resource(home: Path = groot.schedule.Store.defaultHome.resolve("widget"),
      heartbeat: FiniteDuration = 15.seconds): Resource[IO, WidgetService] = for
    _ <- Resource.eval(IO(require(heartbeat > Duration.Zero, "Heartbeat must be positive")))
    store <- WidgetStore.resource(home)
    initial <- Resource.eval(store.load.flatTap(value => IO(validateState(value))))
    state <- Resource.eval(SignallingRef[IO, WidgetState](initial))
    mutex <- Resource.eval(Mutex[IO])
  yield new WidgetService(store, state, mutex, heartbeat)
