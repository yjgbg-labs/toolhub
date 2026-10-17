package groot

import cats.effect.{IO, IOApp}
import framework.rpcDsl.*
import groot.schedule.{Scheduler, ScheduleRoutes}
import java.nio.file.Path

object Main extends IOApp.Simple:
  def run: IO[Unit] = Scheduler.resource().use { scheduler =>
    val app = httpApp {
      server {
        sys.env.get("SPROUT_PORT").filter(_.nonEmpty) match
          case Some(value) => port(value.toInt)
          case None =>
            val runtime = sys.env.get("XDG_RUNTIME_DIR").filter(_.nonEmpty)
              .getOrElse(Path.of(sys.props("user.home"), ".local", "run").toString)
            socket(sys.env.get("SPROUT_SOCKET").filter(_.nonEmpty).getOrElse(Path.of(runtime, "sprout-http", "http.sock").toString))
      }

      openapi {
        endpoint("/openapi.json")
        info {
          title("Sprout")
          version("1.0.0")
        }
      }

      ScheduleRoutes.register(scheduler)
      command[String]("health") {
        handle { IO.pure("ok") }
      }
    }
    app.serve
  }
