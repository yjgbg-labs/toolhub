package groot

import cats.effect.{Deferred, IO, IOApp, Resource}
import framework.rpcDsl.*
import groot.jobs.JobsRoutes
import groot.graaljs.{GraalCommandSdk, GraalJsRoutes, GraalManager}
import groot.schedule.{Scheduler, ScheduleRoutes}
import groot.sqlite.{SqliteRoutes, SqliteService}
import groot.fetch.{FetchRoutes, FetchService}
import groot.fs.{FsRoutes, FsService}
import groot.bash.{BashRoutes, BashService}
import groot.widget.{WidgetRoutes, WidgetService}
import groot.state.{StateRoutes, StateService}
import groot.powershell.{PowerShellRoutes, PowerShellService}

object Main extends IOApp.Simple:
  def run: IO[Unit] = (for
    commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
    sqlite <- SqliteService.resource()
    fetch <- FetchService.resource
    widget <- WidgetService.resource()
    state <- StateService.resource()
    powershell <- PowerShellService.resource()
    graaljs <- GraalManager.resource(commands = commands.get)
    scheduler <- Scheduler.resource(graaljs, startLoop = false)
  yield (scheduler, sqlite, fetch, widget, state, powershell, graaljs, commands)).use { (scheduler, sqlite, fetch, widget, state, powershell, graaljs, commands) =>
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
      FsRoutes.register(new FsService)
      BashRoutes.register(new BashService)
      WidgetRoutes.register(widget)
      StateRoutes.register(state)
      PowerShellRoutes.register(powershell)
      command[PlainText]("health") {
        handle { IO.pure(PlainText("ok")) }
      }
    }
    commands.complete(GraalCommandSdk(app)) *> scheduler.loop.background.use(_ => app.serve)
  }
