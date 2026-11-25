package groot.config

import framework.http.ServerConfig
import framework.model.JsonModel
import framework.runtime.LogConfig
import framework.validation.Validator
import groot.chrome.ChromeConfig
import groot.fs.FsConfig
import groot.fxtwitter.FxTwitterConfig
import groot.github.GitHubConfig
import groot.graaljs.GraalJsConfig
import groot.http.HttpConfig
import groot.kv.KvConfig
import groot.mihomo.MihomoConfig
import groot.notify.NotifyConfig
import groot.shell.ShellConfig
import groot.sqlite.SqliteConfig
import groot.telegram.TelegramConfig
import groot.widget.WidgetConfig
import groot.wine.WineSettings
import java.nio.file.{Files, Path}

final case class ConfigFailure(code: String, message: String) extends RuntimeException(message)

final case class ApplicationConfig(
    server: ServerConfig = ServerConfig(),
    log: LogConfig = LogConfig(),
    graaljs: GraalJsConfig = GraalJsConfig(),
    http: HttpConfig = HttpConfig(),
    shell: ShellConfig = ShellConfig(),
    fs: FsConfig = FsConfig(),
    sqlite: SqliteConfig = SqliteConfig(),
    notifications: NotifyConfig = NotifyConfig(),
    mihomo: MihomoConfig = MihomoConfig(),
    github: GitHubConfig = GitHubConfig(),
    fxTwitter: FxTwitterConfig = FxTwitterConfig(),
    telegram: TelegramConfig = TelegramConfig(),
    chrome: ChromeConfig = ChromeConfig(),
    wine: WineSettings = WineSettings(),
    widget: WidgetConfig = WidgetConfig(),
    kv: KvConfig = KvConfig()
) derives JsonModel, Validator

object ApplicationConfig:
  def resolveConfigFile(home: Path): Path =
    sys.env.get("GROOT_CONFIG").map(_.trim).filter(_.nonEmpty) match
      case Some(explicit) => Path.of(explicit).toAbsolutePath.normalize()
      case None =>
        val conf = home.resolve("application.conf").toAbsolutePath.normalize()
        val candidates = List(
          conf,
          home.resolve("application.hocon").toAbsolutePath.normalize(),
          home.resolve("application.json").toAbsolutePath.normalize()
        )
        candidates.find(Files.exists(_)).getOrElse(conf)
