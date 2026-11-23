package groot.config

import framework.model.JsonModel
import groot.chrome.ChromeStore
import groot.graaljs.Validation
import groot.schedule.RetryPolicy
import groot.shell.{ShellEnvironment, ShellService}
import groot.wine.WineConfig
import java.nio.file.Path
import org.http4s.Uri
import scala.concurrent.duration.*

final case class ConfigFailure(code: String, message: String) extends RuntimeException(message)

enum LogLevel derives JsonModel:
  case trace, debug, info, warn, error

final case class LogConfig(level: LogLevel = LogLevel.info) derives JsonModel
final case class CorsConfig(allowOrigins: String = "*", maxAge: String = "1h") derives JsonModel
final case class ServerConfig(
    host: String = "0.0.0.0",
    port: Int = 18080,
    maxBodyBytes: Long = 1024L * 1024L,
    cors: CorsConfig = CorsConfig()
) derives JsonModel

final case class GraalJsConfig(
    engineBytes: Long = 64L * 1024 * 1024,
    taskBytes: Long = 4L * 1024 * 1024,
    maxQueued: Int = 1024,
    maxSourceBytes: Int = 1024 * 1024,
    maxLogBytes: Long = 8L * 1024 * 1024,
    maxPendingCalls: Int = 64,
    sdkMaxBytes: Int = 1024 * 1024
) derives JsonModel

final case class HttpConfig(
    timeout: String = "30s",
    maxBytes: Int = 1024 * 1024,
    maxRedirects: Int = 20,
    maxHeaderBytes: Int = 64 * 1024
) derives JsonModel

final case class ShellConfig(
    maxScriptBytes: Int = 64 * 1024,
    maxOutputBytes: Int = 64 * 1024,
    sshExecutable: String = "/usr/bin/ssh",
    seedEnvironments: Vector[ShellEnvironment] = ShellService.defaults
) derives JsonModel

final case class FsConfig(maxBytes: Int = 1024 * 1024, maxEntries: Int = 1000) derives JsonModel

final case class SqliteConfig(
    maxSqlBytes: Int = 1024 * 1024,
    maxResultBytes: Int = 512 * 1024,
    maxRows: Int = 10000,
    busyTimeoutMs: Int = 5000
) derives JsonModel

final case class NotifyConfig(
    heartbeat: String = "15s",
    subscriberCapacity: Int = 64,
    maxActiveTriggers: Int = 256,
    defaultEvery: String = "15s",
    defaultCpu: String = "2s",
    defaultScriptTimeout: String = "30s",
    deliveryRetry: RetryPolicy = RetryPolicy(maxRetries = 3, delay = "500ms", backoff = 2, maxDelay = "5s")
) derives JsonModel

final case class MihomoConfig(
    shellEnv: String = sys.env.getOrElse("MIHOMO_SHELL_ENV", "r6s"),
    configDir: String = sys.env.getOrElse("MIHOMO_CONFIG_DIR", "/etc/mihomo"),
    configPath: String = sys.env.getOrElse("MIHOMO_CONFIG_PATH", "/etc/mihomo/config.yaml"),
    binaryPath: String = sys.env.getOrElse("MIHOMO_BINARY", "/usr/local/bin/mihomo"),
    serviceUnit: String = sys.env.getOrElse("MIHOMO_SERVICE", "mihomo.service"),
    apiUrlOverride: Option[String] = sys.env.get("MIHOMO_API_URL").map(_.trim).filter(_.nonEmpty),
    maxBackups: Int = 20
) derives JsonModel

final case class GitHubConfig(
    apiBase: String = "https://api.github.com",
    oauthBase: String = "https://github.com",
    oauthClientId: String = sys.env.get("GITHUB_CLIENT_ID").filter(_.nonEmpty).getOrElse("178c6fc778ccc68e1d6a"),
    defaultScopes: String = "repo,workflow,gist,notifications,read:org",
    timeout: String = "30s",
    maxJsonBytes: Int = 4 * 1024 * 1024,
    maxRawBytes: Int = 1024 * 1024
) derives JsonModel

final case class FxTwitterConfig(
    baseUrl: String = "https://api.fxtwitter.com",
    timeout: String = "25s",
    maxResponseBytes: Int = 4 * 1024 * 1024
) derives JsonModel

final case class ChromeConfig(
    home: String = ChromeStore.defaultHome.toString,
    binary: Option[String] = sys.env.get("CHROME_BINARY").filter(_.nonEmpty),
    display: Option[String] = sys.env.get("CHROME_DISPLAY").filter(_.nonEmpty),
    waylandDisplay: Option[String] = sys.env.get("CHROME_WAYLAND_DISPLAY").filter(_.nonEmpty),
    defaultTimeoutMs: Int = 30000
) derives JsonModel

final case class WineSettings(
    home: String = WineConfig.default.home.toString,
    runtimeRoot: String = WineConfig.default.runtime.toString,
    helperRoot: String = WineConfig.default.helper.toString,
    maxResultBytes: Int = 1024 * 1024,
    maxLogBytes: Int = 64 * 1024
) derives JsonModel

final case class WidgetConfig(maxItems: Int = 256, heartbeat: String = "15s") derives JsonModel

final case class GrootConfig(
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
    chrome: ChromeConfig = ChromeConfig(),
    wine: WineSettings = WineSettings(),
    widget: WidgetConfig = WidgetConfig()
) derives JsonModel

object GrootConfig:
  def formatBytes(bytes: Long): String =
    if bytes >= 1024L * 1024L && bytes % (1024L * 1024L) == 0L then s"${bytes / (1024L * 1024L)} MiB"
    else if bytes >= 1024L && bytes % 1024L == 0L then s"${bytes / 1024L} KiB"
    else s"$bytes B"

  def parseDuration(name: String, raw: String): FiniteDuration =
    Validation.durationMs(name, raw.trim).millis

  def toServerSettings(s: ServerConfig): framework.http.ServerSettings =
    val origins = s.cors.allowOrigins.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet
    val cors = framework.http.CorsSettings(
      allowedOrigins = if origins.isEmpty then Set("*") else origins,
      allowedMethods = Set("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
      allowedHeaders = Set("*"),
      exposedHeaders = Set("ETag", "Allow"),
      allowCredentials = false,
      maxAge = parseDuration("server.cors.maxAge", s.cors.maxAge)
    )
    framework.http.ServerSettings(
      host = s.host.trim,
      port = s.port,
      maxBodyBytes = s.maxBodyBytes,
      cors = Some(cors)
    )

  def validate(cfg: GrootConfig): Either[String, GrootConfig] =
    try
      def pos(name: String, v: Long): Unit = if v <= 0 then throw new IllegalArgumentException(s"$name must be > 0 (got $v)")
      def nonBlank(name: String, v: String): Unit = if v.trim.isEmpty then throw new IllegalArgumentException(s"$name must not be blank")
      def checkUrl(name: String, v: String): Unit =
        val u = Uri.fromString(v.trim).fold(e => throw new IllegalArgumentException(s"$name invalid URL: ${e.message}"), identity)
        if !u.scheme.exists(s => s == Uri.Scheme.http || s == Uri.Scheme.https) || u.host.isEmpty then
          throw new IllegalArgumentException(s"$name must be an absolute http(s) URL")

      nonBlank("server.host", cfg.server.host)
      if com.comcast.ip4s.Host.fromString(cfg.server.host.trim).isEmpty then
        throw new IllegalArgumentException(s"server.host is not a valid IP or hostname: ${cfg.server.host}")
      if cfg.server.port < 1 || cfg.server.port > 65535 then
        throw new IllegalArgumentException(s"server.port must be in 1..65535 (got ${cfg.server.port})")
      pos("server.maxBodyBytes", cfg.server.maxBodyBytes)
      nonBlank("server.cors.allowOrigins", cfg.server.cors.allowOrigins)
      parseDuration("server.cors.maxAge", cfg.server.cors.maxAge)

      pos("graaljs.engineBytes", cfg.graaljs.engineBytes)
      pos("graaljs.taskBytes", cfg.graaljs.taskBytes)
      if cfg.graaljs.taskBytes > cfg.graaljs.engineBytes then
        throw new IllegalArgumentException("graaljs.taskBytes must not exceed graaljs.engineBytes")
      pos("graaljs.maxQueued", cfg.graaljs.maxQueued)
      pos("graaljs.maxSourceBytes", cfg.graaljs.maxSourceBytes)
      pos("graaljs.maxLogBytes", cfg.graaljs.maxLogBytes)
      pos("graaljs.maxPendingCalls", cfg.graaljs.maxPendingCalls)
      pos("graaljs.sdkMaxBytes", cfg.graaljs.sdkMaxBytes)

      parseDuration("http.timeout", cfg.http.timeout)
      pos("http.maxBytes", cfg.http.maxBytes)
      if cfg.http.maxRedirects < 0 || cfg.http.maxRedirects > 100 then
        throw new IllegalArgumentException("http.maxRedirects must be in 0..100")
      pos("http.maxHeaderBytes", cfg.http.maxHeaderBytes)

      pos("shell.maxScriptBytes", cfg.shell.maxScriptBytes)
      pos("shell.maxOutputBytes", cfg.shell.maxOutputBytes)
      nonBlank("shell.sshExecutable", cfg.shell.sshExecutable)

      pos("fs.maxBytes", cfg.fs.maxBytes)
      pos("fs.maxEntries", cfg.fs.maxEntries)

      pos("sqlite.maxSqlBytes", cfg.sqlite.maxSqlBytes)
      pos("sqlite.maxResultBytes", cfg.sqlite.maxResultBytes)
      pos("sqlite.maxRows", cfg.sqlite.maxRows)
      pos("sqlite.busyTimeoutMs", cfg.sqlite.busyTimeoutMs)

      parseDuration("notifications.heartbeat", cfg.notifications.heartbeat)
      pos("notifications.subscriberCapacity", cfg.notifications.subscriberCapacity)
      pos("notifications.maxActiveTriggers", cfg.notifications.maxActiveTriggers)
      parseDuration("notifications.defaultEvery", cfg.notifications.defaultEvery)
      parseDuration("notifications.defaultCpu", cfg.notifications.defaultCpu)
      parseDuration("notifications.defaultScriptTimeout", cfg.notifications.defaultScriptTimeout)
      if cfg.notifications.deliveryRetry.maxRetries < 0 || cfg.notifications.deliveryRetry.maxRetries > 100 then
        throw new IllegalArgumentException("notifications.deliveryRetry.maxRetries must be in 0..100")
      parseDuration("notifications.deliveryRetry.delay", cfg.notifications.deliveryRetry.delay)
      if !cfg.notifications.deliveryRetry.backoff.isFinite || cfg.notifications.deliveryRetry.backoff < 1.0 then
        throw new IllegalArgumentException("notifications.deliveryRetry.backoff must be >= 1.0")
      parseDuration("notifications.deliveryRetry.maxDelay", cfg.notifications.deliveryRetry.maxDelay)

      nonBlank("mihomo.shellEnv", cfg.mihomo.shellEnv)
      nonBlank("mihomo.configDir", cfg.mihomo.configDir)
      nonBlank("mihomo.configPath", cfg.mihomo.configPath)
      nonBlank("mihomo.binaryPath", cfg.mihomo.binaryPath)
      nonBlank("mihomo.serviceUnit", cfg.mihomo.serviceUnit)
      cfg.mihomo.apiUrlOverride.foreach(checkUrl("mihomo.apiUrlOverride", _))
      pos("mihomo.maxBackups", cfg.mihomo.maxBackups)

      checkUrl("github.apiBase", cfg.github.apiBase)
      checkUrl("github.oauthBase", cfg.github.oauthBase)
      nonBlank("github.oauthClientId", cfg.github.oauthClientId)
      nonBlank("github.defaultScopes", cfg.github.defaultScopes)
      parseDuration("github.timeout", cfg.github.timeout)
      pos("github.maxJsonBytes", cfg.github.maxJsonBytes)
      pos("github.maxRawBytes", cfg.github.maxRawBytes)

      checkUrl("fxTwitter.baseUrl", cfg.fxTwitter.baseUrl)
      parseDuration("fxTwitter.timeout", cfg.fxTwitter.timeout)
      pos("fxTwitter.maxResponseBytes", cfg.fxTwitter.maxResponseBytes)

      nonBlank("chrome.home", cfg.chrome.home)
      if cfg.chrome.defaultTimeoutMs < 1 || cfg.chrome.defaultTimeoutMs > 600000 then
        throw new IllegalArgumentException("chrome.defaultTimeoutMs must be in 1..600000")

      nonBlank("wine.home", cfg.wine.home)
      nonBlank("wine.runtimeRoot", cfg.wine.runtimeRoot)
      nonBlank("wine.helperRoot", cfg.wine.helperRoot)
      pos("wine.maxResultBytes", cfg.wine.maxResultBytes)
      pos("wine.maxLogBytes", cfg.wine.maxLogBytes)

      pos("widget.maxItems", cfg.widget.maxItems)
      parseDuration("widget.heartbeat", cfg.widget.heartbeat)

      Right(cfg)
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

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
        candidates.find(java.nio.file.Files.exists(_)).getOrElse(conf)
