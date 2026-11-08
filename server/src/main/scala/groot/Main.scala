package groot

import cats.effect.{Deferred, IO, IOApp, Resource}
import framework.rpcDsl.*
import groot.jobs.JobsRoutes
import groot.usejdk.UseJdkRoutes
import groot.graaljs.{GraalCommandSdk, GraalJsRoutes, GraalManager}
import groot.schedule.{Scheduler, ScheduleRoutes}
import groot.sqlite.{SqliteRoutes, SqliteService}
import groot.http.{HttpRoutes, HttpService}
import groot.fs.{FsRoutes, FsService}
import groot.bash.{BashRoutes, BashService}
import groot.widget.{WidgetRoutes, WidgetService}
import groot.state.{StateRoutes, StateService}
import groot.powershell.{PowerShellRoutes, PowerShellService}
import groot.zshonmac.{ZshOnMacRoutes, ZshOnMacService}
import groot.bashonr6s.{BashOnR6sRoutes, BashOnR6sService}
import groot.chrome.{ChromeRoutes, ChromeService}
import groot.wine.{WineRoutes, WineService}
import scala.concurrent.duration.*

object Main extends IOApp.Simple:
  def run: IO[Unit] = (for
    commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
    sqlite <- SqliteService.resource()
    http <- HttpService.resource
    widget <- WidgetService.resource()
    state <- StateService.resource()
    powershell <- PowerShellService.resource()
    zshOnMac <- ZshOnMacService.resource()
    bashOnR6s <- BashOnR6sService.resource()
    chrome <- ChromeService.resource()
    wine <- WineService.resource()
    graaljs <- GraalManager.resource(commands = commands.get)
    scheduler <- Scheduler.resource(graaljs, startLoop = false)
  yield (scheduler, sqlite, http, widget, state, powershell, zshOnMac, bashOnR6s, chrome, wine, graaljs, commands)).use { (scheduler, sqlite, http, widget, state, powershell, zshOnMac, bashOnR6s, chrome, wine, graaljs, commands) =>
    val app = httpApp {
      server {
        host("0.0.0.0")
        port(18080)
        cors {
          allowOrigins("*")
          allowMethods("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
          allowHeaders("*")
          exposeHeaders("ETag", "Allow")
          allowCredentials(false)
          maxAge(1.hour)
        }
      }

      openapi {
        endpoint("/openapi.json")
        info {
          title("Groot")
          version("1.0.0")
        }
      }

      ScheduleRoutes.register(scheduler)
      JobsRoutes.register()
      UseJdkRoutes.register()
      GraalJsRoutes.register(graaljs)
      SqliteRoutes.register(sqlite)
      HttpRoutes.register(http)
      FsRoutes.register(new FsService)
      BashRoutes.register(new BashService)
      WidgetRoutes.register(widget)
      StateRoutes.register(state)
      PowerShellRoutes.register(powershell)
      ZshOnMacRoutes.register(zshOnMac)
      BashOnR6sRoutes.register(bashOnR6s)
      ChromeRoutes.register(chrome)
      WineRoutes.register(wine)
      command[PlainText]("health") {
        handle { IO.pure(PlainText("ok")) }
      }
    }
    commands.complete(GraalCommandSdk(app)) *> scheduler.loop.background.use(_ => app.serve)
  }
