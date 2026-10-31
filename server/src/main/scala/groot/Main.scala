package groot

import cats.effect.{Deferred, IO, IOApp, Resource}
import framework.rpcDsl.*
import groot.jobs.JobsRoutes
import groot.graaljs.{GraalCommandSdk, GraalJsRoutes, GraalManager}
import groot.schedule.{Scheduler, ScheduleRoutes}
import groot.sqlite.{SqliteRoutes, SqliteService}
import groot.fetch.{FetchRoutes, FetchService}

object Main extends IOApp.Simple:
  def run: IO[Unit] = (for
    commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
    scheduler <- Scheduler.resource()
    sqlite <- SqliteService.resource()
    fetch <- FetchService.resource
    graaljs <- GraalManager.resource(commands = commands.get)
  yield (scheduler, sqlite, fetch, graaljs, commands)).use { (scheduler, sqlite, fetch, graaljs, commands) =>
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
      SqliteRoutes.register(sqlite)
      FetchRoutes.register(fetch)
      command[PlainText]("health") {
        handle { IO.pure(PlainText("ok")) }
      }
    }
    commands.complete(GraalCommandSdk(app)) *> app.serve
  }
