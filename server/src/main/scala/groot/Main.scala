package groot

import cats.effect.{Deferred, IO, IOApp, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import framework.runtime.{DynamicServer, Logging}
import framework.validation.Contracts
import groot.config.{ApplicationConfig, ConfigLoader}
import groot.graaljs.{GraalCommandSdk, GraalJsRoutes, GraalManager}
import groot.schedule.{Scheduler, ScheduleRoutes, Store}
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
import groot.fxtwitter.{FxTwitterRoutes, FxTwitterService}
import groot.telegram.{TelegramRoutes, TelegramService}
import scala.concurrent.duration.*

object Main extends IOApp.Simple:
  def run: IO[Unit] = (for
    home <- Resource.eval(IO(Store.defaultHome))
    cfgSignal <- ConfigLoader.resource[ApplicationConfig](
      resolvePath = IO(ApplicationConfig.resolveConfigFile(home)),
      default = ApplicationConfig(),
      pollInterval = 500.millis,
      onChange = (prev, next) =>
        if prev.log.level != next.log.level then Logging.applyLevel(next.log.level) else IO.unit
    )
    readCfg = cfgSignal.get
    initCfg <- Resource.eval(readCfg)
    _ <- Resource.eval(Logging.applyLevel(initCfg.log.level))
    commands <- Resource.eval(Deferred[IO, GraalCommandSdk])
    sqlite <- SqliteService.resource(home.resolve("sqlite"), config = readCfg.map(_.sqlite))
    http <- HttpService.resourceWithConfig(readCfg.map(_.http))
    widget <- WidgetService.resource(home.resolve("widget"), config = readCfg.map(_.widget))
    state <- StateService.resource(home.resolve("state"))
    shell <- ShellService.resource(home.resolve("shell"), config = readCfg.map(_.shell))
    mihomo <- MihomoService.resourceWithConfig(shell, readCfg.map(_.mihomo), readCfg.map(_.shell))
    github <- GitHubService.resource(home, config = readCfg.map(_.github))
    fxTwitter <- FxTwitterService.resource(config = readCfg.map(_.fxTwitter))
    telegram <- TelegramService.resource(home, config = readCfg.map(_.telegram))
    chrome <- ChromeService.resource(config = readCfg.map(_.chrome))
    wine <- WineService.resource(readCfg.map(_.wine))
    notify <- NotifyService.resource(config = readCfg.map(_.notifications))
    graaljs <- GraalManager.resource(home.resolve("graaljs"), commands = commands.get, config = cfgSignal.map(_.graaljs))
    notifyTriggers <- NotifyTriggerManager.resource(notify, graaljs, home = home, startLoop = false, config = readCfg.map(_.notifications))
    scheduler <- Scheduler.resource(graaljs, home = home, startLoop = false)
  yield (initCfg, cfgSignal, readCfg, scheduler, sqlite, http, widget, state, shell, mihomo, github, fxTwitter, telegram, chrome, wine, notify, notifyTriggers, graaljs, commands)).use {
    (initCfg, cfgSignal, readCfg, scheduler, sqlite, http, widget, state, shell, mihomo, github, fxTwitter, telegram, chrome, wine, notify, notifyTriggers, graaljs, commands) =>
      val app = httpApp {
        server {
          host(initCfg.server.host.trim)
          port(initCfg.server.port)
          maxBodyBytes(initCfg.server.maxBodyBytes)
          cors {
            val origins = initCfg.server.cors.allowOrigins.split(",").map(_.trim).filter(_.nonEmpty).toVector
            allowOrigins(origins.headOption.getOrElse("*"), origins.drop(1)*)
            allowMethods("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowHeaders("*")
            exposeHeaders("ETag", "Allow")
            allowCredentials(false)
            maxAge(Contracts.parseDuration(initCfg.server.cors.maxAge))
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
        FsRoutes.register(new FsService(readCfg.map(_.fs)))
        ShellRoutes.register(shell)
        MihomoRoutes.register(mihomo)
        GitHubRoutes.register(github)
        FxTwitterRoutes.register(fxTwitter)
        TelegramRoutes.register(telegram)
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
        scheduler.loop.background.use(_ =>
          notifyTriggers.loop.background.use(_ =>
            DynamicServer.serve(app, cfgSignal.map(_.server.toSettings))
          )
        )
  }
