package groot

import cats.effect.{Deferred, IO, IOApp, Resource}
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalJsRoutes, GraalManager}
import groot.schedule.{Scheduler, ScheduleRoutes}
import groot.sqlite.{SqliteRoutes, SqliteService}
import groot.http.{HttpRoutes, HttpService}
import groot.fs.{FsRoutes, FsService}
import groot.shell.{ShellRoutes, ShellService}
import groot.widget.{WidgetRoutes, WidgetService}
import groot.state.{StateRoutes, StateService}
import groot.chrome.{ChromeRoutes, ChromeService}
import groot.wine.{WineRoutes, WineService}
import groot.notify.{NotifyRoutes, NotifyService, NotifyTriggerManager}
import groot.mihomo.{MihomoRoutes, MihomoService}
import groot.github.{GitHubRoutes, GitHubService}
import scala.concurrent.duration.*

object Main extends IOApp.Simple:
  def run: IO[Unit] = (for
    commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
    sqlite <- SqliteService.resource()
    http <- HttpService.resource
    widget <- WidgetService.resource()
    state <- StateService.resource()
    shell <- ShellService.resource()
    mihomo <- MihomoService.resource(shell)
    github <- GitHubService.resource()
    chrome <- ChromeService.resource()
    wine <- WineService.resource()
    notify <- NotifyService.resource()
    graaljs <- GraalManager.resource(commands = commands.get)
    notifyTriggers <- NotifyTriggerManager.resource(notify, graaljs, startLoop = false)
    scheduler <- Scheduler.resource(graaljs, startLoop = false)
  yield (scheduler, sqlite, http, widget, state, shell, mihomo, github, chrome, wine, notify, notifyTriggers, graaljs, commands)).use { (scheduler, sqlite, http, widget, state, shell, mihomo, github, chrome, wine, notify, notifyTriggers, graaljs, commands) =>
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
      GraalJsRoutes.register(graaljs)
      SqliteRoutes.register(sqlite)
      HttpRoutes.register(http)
      FsRoutes.register(new FsService)
      ShellRoutes.register(shell)
      MihomoRoutes.register(mihomo)
      GitHubRoutes.register(github)
      WidgetRoutes.register(widget)
      StateRoutes.register(state)
      ChromeRoutes.register(chrome)
      WineRoutes.register(wine)
      NotifyRoutes.register(notify, Some(notifyTriggers))
      command[PlainText]("health") {
        handle { IO.pure(PlainText("ok")) }
      }
    }
    commands.complete(GraalCommandSdk(app)) *>
      scheduler.loop.background.use(_ => notifyTriggers.loop.background.use(_ => app.serve))
  }
