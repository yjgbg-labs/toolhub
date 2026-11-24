package groot.mihomo

import framework.model.JsonModel
import framework.validation.*

final case class MihomoConfig(
    @nonBlank shellEnv: String = sys.env.getOrElse("MIHOMO_SHELL_ENV", "r6s"),
    @nonBlank configDir: String = sys.env.getOrElse("MIHOMO_CONFIG_DIR", "/etc/mihomo"),
    @nonBlank configPath: String = sys.env.getOrElse("MIHOMO_CONFIG_PATH", "/etc/mihomo/config.yaml"),
    @nonBlank binaryPath: String = sys.env.getOrElse("MIHOMO_BINARY", "/usr/local/bin/mihomo"),
    @nonBlank serviceUnit: String = sys.env.getOrElse("MIHOMO_SERVICE", "mihomo.service"),
    @contract[String](s => s.isEmpty || Contracts.isHttpUrl(s), "must be empty or an absolute http(s) URL")
    apiUrl: String = sys.env.getOrElse("MIHOMO_API_URL", "").trim,
    @positive maxBackups: Int = 20
) derives JsonModel, Validator

final case class MihomoStatus(
  activeState: String,
  subState: String,
  unitFileState: String,
  mainPid: Int,
  since: Option[String],
  memoryBytes: Option[Long],
  version: Option[String]
) derives JsonModel

final case class ConfigValidateResult(
  valid: Boolean,
  output: String
) derives JsonModel

final case class ConfigWriteResult(
  changed: Boolean,
  reloaded: Boolean,
  restarted: Boolean,
  backupPath: Option[String]
) derives JsonModel

final case class ConfigRollbackResult(
  restoredFrom: String,
  reloaded: Boolean,
  restarted: Boolean
) derives JsonModel

final case class DnsHost(
  host: String,
  value: String,
  managed: Boolean
) derives JsonModel

final case class DnsDeleteResult(
  deleted: Boolean,
  reloaded: Boolean,
  restarted: Boolean,
  backupPath: Option[String]
) derives JsonModel

final case class ProxyGroup(
  name: String,
  kind: String,
  now: String,
  nodeCount: Int
) derives JsonModel

final case class ProxyNode(
  name: String,
  kind: String,
  alive: Boolean,
  current: Boolean,
  lastDelayMs: Option[Int]
) derives JsonModel

final case class ProxyGroupDetail(
  name: String,
  kind: String,
  now: String,
  nodes: Vector[ProxyNode]
) derives JsonModel

final case class ProxySwitchResult(
  group: String,
  previous: String,
  now: String
) derives JsonModel

final case class ProxyDelayItem(
  group: Option[String],
  node: String,
  delayMs: Option[Int],
  error: Option[String]
) derives JsonModel

final case class ProxyConnection(
  id: String,
  host: String,
  network: String,
  sourceIp: String,
  destinationIp: String,
  destinationPort: String,
  downloadBytes: Long,
  uploadBytes: Long,
  chains: Vector[String],
  rule: String,
  start: String
) derives JsonModel

final case class ProxyConnectionsSnapshot(
  totalConnections: Int,
  downloadTotalBytes: Long,
  uploadTotalBytes: Long,
  connections: Vector[ProxyConnection]
) derives JsonModel

final case class MihomoSettings(
  shellEnv: String = sys.env.getOrElse("MIHOMO_SHELL_ENV", "r6s"),
  configDir: String = sys.env.getOrElse("MIHOMO_CONFIG_DIR", "/etc/mihomo"),
  configPath: String = sys.env.getOrElse("MIHOMO_CONFIG_PATH", "/etc/mihomo/config.yaml"),
  binaryPath: String = sys.env.getOrElse("MIHOMO_BINARY", "/usr/local/bin/mihomo"),
  serviceUnit: String = sys.env.getOrElse("MIHOMO_SERVICE", "mihomo.service"),
  apiUrlOverride: Option[String] = sys.env.get("MIHOMO_API_URL").map(_.trim).filter(_.nonEmpty),
  sshExecutable: String = "/usr/bin/ssh",
  maxBackups: Int = 20
)

private[mihomo] final case class ApiEndpoint(baseUrl: String, secret: String)

final case class MihomoFailure(code: String, message: String) extends RuntimeException(message)
