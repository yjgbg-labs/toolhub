package framework.runtime

import cats.data.Kleisli
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import framework.http.ServerSettings
import fs2.concurrent.{Signal, SignallingRef}
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import scala.concurrent.duration.*

object DynamicServer:
  private final case class ActiveListener(host: String, configuredPort: Int, server: org.http4s.server.Server, release: IO[Unit])

  def httpApp(application: Application, settingsSignal: Signal[IO, ServerSettings]): HttpApp[IO] =
    val cache = new AtomicReference[(ServerSettings, HttpApp[IO])](null)
    def resolve(s: ServerSettings): HttpApp[IO] =
      val current = cache.get()
      if current != null && current._1 == s then current._2
      else
        val built = application.httpAppWith(s)
        cache.set(s -> built)
        built
    Kleisli(req => settingsSignal.get.flatMap(s => resolve(s).run(req)))

  def resource(application: Application, settingsSignal: Signal[IO, ServerSettings]): Resource[IO, Signal[IO, InetSocketAddress]] =
    val app = httpApp(application, settingsSignal)
    def bindOne(h: String, p: Int): IO[(org.http4s.server.Server, IO[Unit])] =
      for
        hostVal <- IO.fromOption(Host.fromString(h.trim))(new IllegalArgumentException(s"Invalid server.host: $h"))
        portVal <- IO.fromOption(Port.fromInt(p))(new IllegalArgumentException(s"Invalid server.port: $p"))
        allocated <- EmberServerBuilder.default[IO]
          .withHost(hostVal)
          .withPort(portVal)
          .withHttpApp(app)
          .withShutdownTimeout(2.seconds)
          .build
          .allocated
        _ <- IO(scribe.info(s"Groot HTTP listening at ${allocated._1.address}; OpenAPI: ${application.openApiSettings.endpoint}"))
      yield allocated

    for
      mutex <- Resource.eval(Mutex[IO])
      init <- Resource.eval(settingsSignal.get)
      initHost = init.host.trim
      first <- Resource.eval(bindOne(initHost, init.port))
      activeRef <- Resource.make(
        Ref.of[IO, Option[ActiveListener]](Some(ActiveListener(initHost, init.port, first._1, first._2)))
      )(ref => mutex.lock.surround(ref.getAndSet(None).flatMap(_.traverse_(_.release.attempt.void))))
      addrSignal <- Resource.eval(SignallingRef[IO, InetSocketAddress](first._1.address))
      rebindTo = (nextHost: String, nextPort: Int) =>
        mutex.lock.surround {
          activeRef.get.flatMap {
            case None => IO.unit
            case Some(cur) if cur.host == nextHost && (cur.configuredPort == nextPort || cur.server.address.getPort == nextPort) =>
              IO.unit
            case Some(cur) if nextPort != cur.server.address.getPort =>
              bindOne(nextHost, nextPort).attempt.flatMap {
                case Right((newSrv, newRel)) =>
                  cur.release.attempt.void *>
                    activeRef.set(Some(ActiveListener(nextHost, nextPort, newSrv, newRel))) *>
                    addrSignal.set(newSrv.address) *>
                    IO(scribe.info(s"Rebound Groot HTTP listener from ${cur.server.address} to ${newSrv.address}"))
                case Left(err) =>
                  IO(scribe.error(s"Failed to rebind Groot HTTP listener to $nextHost:$nextPort; keeping ${cur.server.address} active", err))
              }
            case Some(cur) =>
              cur.release.attempt.void *>
                bindOne(nextHost, nextPort).attempt.flatMap {
                  case Right((newSrv, newRel)) =>
                    activeRef.set(Some(ActiveListener(nextHost, nextPort, newSrv, newRel))) *>
                      addrSignal.set(newSrv.address) *>
                      IO(scribe.info(s"Rebound Groot HTTP listener from ${cur.host}:${cur.server.address.getPort} to ${newSrv.address}"))
                  case Left(err) =>
                    IO(scribe.error(s"Failed to bind Groot HTTP listener to $nextHost:$nextPort; restoring ${cur.host}:${cur.server.address.getPort}", err)) *>
                      bindOne(cur.host, cur.server.address.getPort).attempt.flatMap {
                        case Right((restoredSrv, restoredRel)) =>
                          activeRef.set(Some(ActiveListener(cur.host, cur.configuredPort, restoredSrv, restoredRel))) *>
                            addrSignal.set(restoredSrv.address)
                        case Left(fatal) =>
                          activeRef.set(None) *> IO(scribe.error("Failed to restore previous HTTP listener", fatal))
                      }
                }
          }
        }
      _ <- settingsSignal.map(s => (s.host.trim, s.port)).discrete.changes
        .evalMap((h, p) => rebindTo(h, p).handleErrorWith(e => IO(scribe.error("Unexpected HTTP listener rebind error", e))))
        .compile.drain.background
    yield addrSignal

  def serve(application: Application, settingsSignal: Signal[IO, ServerSettings]): IO[Nothing] =
    resource(application, settingsSignal).useForever
