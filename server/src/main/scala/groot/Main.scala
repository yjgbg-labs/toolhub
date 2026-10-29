package groot

import cats.effect.{IO, IOApp}
import framework.rpcDsl.*
import groot.schedule.{Scheduler, ScheduleRoutes}

object Main extends IOApp.Simple:
  def run: IO[Unit] = Scheduler.resource().use { scheduler =>
    val app = httpApp {
      server {
        host("0.0.0.0")
        port(sys.env.get("SPROUT_PORT").filter(_.nonEmpty).getOrElse("8080").toInt)
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
