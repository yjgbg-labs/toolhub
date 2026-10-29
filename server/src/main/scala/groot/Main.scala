package groot

import cats.effect.{IO, IOApp}
import framework.rpcDsl.*
import groot.schedule.{Scheduler, ScheduleRoutes}

object Main extends IOApp.Simple:
  def run: IO[Unit] = Scheduler.resource().use { scheduler =>
    val app = httpApp {
      server {
        host("0.0.0.0")
        port(18080)
      }

      openapi {
        endpoint("/openapi.json")
        info {
          title("Sprout")
          version("1.0.0")
        }
      }

      ScheduleRoutes.register(scheduler)
      command[PlainText]("health") {
        handle { IO.pure(PlainText("ok")) }
      }
    }
    app.serve
  }
