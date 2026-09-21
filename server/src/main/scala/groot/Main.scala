package groot

import cats.effect.{IO, IOApp}
import sprout.dsl.*
import groot.schedule.{Scheduler, ScheduleRoutes}

object Main extends IOApp.Simple:
  def run: IO[Unit] = Scheduler.resource().use { scheduler =>
    val app = httpApp {
      server {
        host("127.0.0.1")
        port(sys.env.get("SPROUT_PORT").fold(8080)(_.toInt))
      }

      openapi {
        endpoint("/openapi.json")
        info {
          title("Sprout")
          version("1.0.0")
        }
      }

      routes {
        ScheduleRoutes.register(scheduler)
        operation[String]("health") {
          handle { IO.pure(ok("ok")) }
        }
      }
    }
    app.serve
  }
