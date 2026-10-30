package groot

import cats.effect.{Deferred, IO, IOApp, Resource}
import framework.rpcDsl.*
import framework.runtime.CommandSdk
import groot.jobs.JobsRoutes
import groot.graaljs.{GraalJsRoutes, GraalManager}
import groot.schedule.{Scheduler, ScheduleRoutes}

object Main extends IOApp.Simple:
  def run: IO[Unit] = (for
    commands <- Resource.eval(Deferred[IO, CommandSdk])
    scheduler <- Scheduler.resource()
    graaljs <- GraalManager.resource(commands = commands.get)
  yield (scheduler, graaljs, commands)).use { (scheduler, graaljs, commands) =>
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
      JobsRoutes.register()
      GraalJsRoutes.register(graaljs)
      command[PlainText]("health") {
        handle { IO.pure(PlainText("ok")) }
      }
    }
    commands.complete(app.commandSdk) *> app.serve
  }
