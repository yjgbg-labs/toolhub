package groot.mihomo

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object MihomoRoutes:
  def register(service: MihomoService)(using CommandsScope): Unit = commands("mihomo") {
    description("Manage the router Mihomo service, YAML configuration, static DNS hosts and runtime proxies")
    exposeOn(cli, codemode, graaljs)

    val invalid = response.json[ErrorBody](400) { description("Invalid Mihomo arguments or configuration failed validation") }
    val missing = response.json[ErrorBody](404) { description("Proxy group, backup or shell environment not found") }
    val conflict = response.json[ErrorBody](409) { description("Non-Selector proxy group or protected external-dns host entry") }
    val large = response.json[ErrorBody](413) { description("Configuration or controller payload exceeds size limits") }
    val upstream = response.json[ErrorBody](502) { description("Router SSH command or Mihomo External Controller call failed") }
    val timedOut = response.json[ErrorBody](504) { description("Router SSH command or Mihomo External Controller timed out") }

    recover {
      case error: MihomoFailure =>
        val body = ErrorBody(error.code, error.getMessage)
        error.code match
          case "invalid_mihomo" | "mihomo_config_invalid" => invalid(body)
          case "mihomo_not_found" => missing(body)
          case "mihomo_conflict" => conflict(body)
          case "mihomo_limit" => large(body)
          case "mihomo_timeout" => timedOut(body)
          case _ => upstream(body)
    }

    command[MihomoStatus]("status") {
      description("Read systemd unit state, PID, memory usage and live Mihomo version")
      handle { service.status }
    }

    command[MihomoStatus]("start") {
      description("Start mihomo.service on the router and return its updated status")
      handle { service.start }
    }

    command[MihomoStatus]("stop") {
      description("Stop mihomo.service on the router and return its updated status")
      handle { service.stop }
    }

    command[MihomoStatus]("restart") {
      description("Restart mihomo.service on the router and return its updated status")
      handle { service.restart }
    }

    command[PlainText]("logs") {
      description("Read the last N lines of mihomo.service journal logs")
      val lines = param[Int]("lines", "Number of journal lines to return") { default(100); minimum(1); maximum(5000) }
      handle { service.logs(lines.value) }
    }

    command[SSE[PlainText]]("logsFollow") {
      exposeOn(cli)
      description("Stream live mihomo.service journal lines via SSE until disconnected")
      val lines = param[Int]("lines", "Initial backlog lines before following") { default(100); minimum(1); maximum(5000) }
      handle { service.logsFollow(lines.value) }
    }

    commands("config") {
      description("Read, validate, hot-reload and roll back /etc/mihomo/config.yaml")

      command[PlainText]("read") {
        description("Read the complete /etc/mihomo/config.yaml file as plain text")
        handle { service.readConfig }
      }

      command[ConfigValidateResult]("validate") {
        description("Validate candidate YAML on the router with mihomo -t without modifying the active config")
        val content = body.text("Candidate Mihomo YAML configuration") { minLength(1) }
        handle { service.validateConfig(content.value) }
      }

      command[ConfigWriteResult]("write") {
        description("Validate YAML with mihomo -t, create a backup, install atomically and hot-reload via PUT /configs?force=true")
        val content = body.text("Complete Mihomo YAML configuration") { minLength(1) }
        handle { service.writeConfig(content.value) }
      }

      command[ConfigRollbackResult]("rollback") {
        description("Validate and restore the latest config.yaml.bak.* backup, then hot-reload Mihomo")
        handle { service.rollbackConfig }
      }
    }

    commands("dns") {
      description("Manage static entries under top-level hosts: while preserving comments and external-dns-mihomo blocks")

      command[Vector[DnsHost]]("list") {
        description("List static host mappings under hosts: and whether each entry is managed by external-dns-mihomo")
        handle { service.listDns }
      }

      command[ConfigWriteResult]("set") {
        description("Add or update a static host entry under hosts:, validate with mihomo -t and hot-reload")
        val host = param[String]("host", "Hostname to resolve") { minLength(1); maxLength(253) }
        val value = param[String]("value", "Target IP address, alias hostname or YAML list") { minLength(1); maxLength(512) }
        val force = param[Boolean]("force", "Allow overriding an entry inside external-dns-mihomo managed block") { default(false) }
        handle { service.setDns(host.value, value.value, force.value) }
      }

      command[DnsDeleteResult]("delete") {
        description("Delete a static host entry under hosts:, validate with mihomo -t and hot-reload")
        val host = param[String]("host", "Hostname to remove") { minLength(1); maxLength(253) }
        val force = param[Boolean]("force", "Allow deleting an entry inside external-dns-mihomo managed block") { default(false) }
        handle { service.deleteDns(host.value, force.value) }
      }
    }

    commands("proxy") {
      description("Inspect and switch proxy groups, test latency and query active connections via Mihomo REST API")

      command[Vector[ProxyGroup]]("groups") {
        description("List proxy groups, their type, active selection and candidate count")
        handle { service.proxyGroups }
      }

      command[ProxyGroupDetail]("nodes") {
        description("List candidate nodes in a proxy group with current marker and last recorded delay")
        val group = param[String]("group", "Proxy group name") { minLength(1) }
        handle { service.proxyNodes(group.value) }
      }

      command[ProxySwitchResult]("use") {
        description("Switch a Selector proxy group to a specific candidate node")
        val group = param[String]("group", "Selector proxy group name") { minLength(1) }
        val node = param[String]("node", "Target candidate node name (preserve exact emoji and spacing)") { minLength(1) }
        handle { service.proxyUse(group.value, node.value) }
      }

      command[Vector[ProxyDelayItem]]("delay") {
        description("Test latency: set group to test all nodes in one group, or pass groups (or omit both) to test active nodes concurrently")
        val group = param[Option[String]]("group", "Single proxy group whose candidate nodes should all be tested")
        val groups = param[List[String]]("groups", "Selected proxy groups whose active (now) nodes should be tested concurrently")
        val url = param[String]("url", "Delay probe URL") { default(MihomoApi.DefaultDelayUrl); minLength(1) }
        val timeoutMs = param[Int]("timeoutMs", "Per-probe timeout in milliseconds") { default(MihomoApi.DefaultDelayTimeoutMs); minimum(100); maximum(60000) }
        handle { service.proxyDelay(group.value, groups.value, url.value, timeoutMs.value) }
      }

      command[ProxyConnectionsSnapshot]("connections") {
        description("List active connections sorted by traffic with total upload/download counters")
        val host = param[Option[String]]("host", "Case-insensitive substring filter on host or destination IP")
        val rule = param[Option[String]]("rule", "Case-insensitive substring filter on matched rule")
        val limit = param[Int]("limit", "Maximum connections to return") { default(50); minimum(1); maximum(500) }
        handle { service.proxyConnections(host.value, rule.value, limit.value) }
      }
    }
  }
