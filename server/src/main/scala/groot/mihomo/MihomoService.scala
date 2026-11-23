package groot.mihomo

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Dispatcher, Mutex, Queue}
import cats.syntax.all.*
import framework.rpc.{PlainText, SSE}
import fs2.Stream
import groot.shell.{ShellEnvironment, ShellRequest, ShellResult, ShellService}
import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class MihomoService(
  runShell: (String, String) => IO[ShellResult],
  resolveShellEnv: IO[ShellEnvironment],
  api: MihomoApi,
  val settings: MihomoSettings,
  writeMutex: Mutex[IO],
  apiCache: Ref[IO, Option[ApiEndpoint]],
  customFollow: Option[Int => IO[Stream[IO, PlainText]]] = None,
  settingsIO: IO[MihomoSettings] = IO.pure(MihomoSettings()),
  dynamic: Boolean = false
):
  import MihomoService.*

  @volatile private var cachedSettingsKey: Option[MihomoSettings] = None
  private def currentSettings: IO[MihomoSettings] = if dynamic then settingsIO else IO.pure(settings)

  def status: IO[MihomoStatus] = currentSettings.flatMap: settings =>
    val script =
      s"""set -euo pipefail
         |export LC_ALL=C
         |sudo -n systemctl show ${shellQuote(settings.serviceUnit)} --no-pager \\
         |  --property=ActiveState,SubState,UnitFileState,MainPID,ActiveEnterTimestamp,MemoryCurrent
         |""".stripMargin
    for
      res <- execChecked(script, "10s")
      props = parseProperties(res.stdout)
      activeState = props.getOrElse("ActiveState", "unknown")
      subState = props.getOrElse("SubState", "unknown")
      unitFileState = props.getOrElse("UnitFileState", "unknown")
      mainPid = props.get("MainPID").flatMap(_.toIntOption).getOrElse(0)
      since = props.get("ActiveEnterTimestamp").map(_.trim).filter(v => v.nonEmpty && v != "n/a")
      memoryBytes = props.get("MemoryCurrent").flatMap(_.toLongOption).filter(v => v >= 0 && v < Long.MaxValue)
      version <- if activeState == "active" then withApi(api.version(_, 2.seconds)).option.map(_.flatten) else IO.pure(None)
    yield MihomoStatus(activeState, subState, unitFileState, mainPid, since, memoryBytes, version)

  def start: IO[MihomoStatus] = lifecycleAction("start", clearCache = false)
  def stop: IO[MihomoStatus] = lifecycleAction("stop", clearCache = true)
  def restart: IO[MihomoStatus] = lifecycleAction("restart", clearCache = true)

  private def lifecycleAction(action: String, clearCache: Boolean): IO[MihomoStatus] =
    writeMutex.lock.surround(currentSettings.flatMap { settings =>
      val script =
        s"""set -euo pipefail
           |export LC_ALL=C
           |sudo -n systemctl $action ${shellQuote(settings.serviceUnit)}
           |""".stripMargin
      execChecked(script, "25s") *>
        (if clearCache then apiCache.set(None) else IO.unit) *>
        status
    })

  def logs(lines: Int): IO[PlainText] =
    if lines < 1 || lines > 5000 then
      IO.raiseError(MihomoFailure("invalid_mihomo", "lines must be between 1 and 5000"))
    else currentSettings.flatMap: settings =>
      val script =
        s"""set -euo pipefail
           |export LC_ALL=C
           |sudo -n journalctl -u ${shellQuote(settings.serviceUnit)} -n $lines --no-pager
           |""".stripMargin
      execChecked(script, "15s").map(r => PlainText(r.stdout))

  def logsFollow(lines: Int): IO[SSE[PlainText]] =
    if lines < 1 || lines > 5000 then
      IO.raiseError(MihomoFailure("invalid_mihomo", "lines must be between 1 and 5000"))
    else
      customFollow match
        case Some(factory) => factory(lines).map(SSE(_))
        case None =>
          (resolveShellEnv, currentSettings).tupled.map { (env, settings) =>
            val command: Vector[String] = env.target match
              case Some(sshTarget) =>
                Vector(
                  "/usr/bin/setsid", "--", settings.sshExecutable,
                  "-T",
                  "-o", "BatchMode=yes",
                  "-o", "StrictHostKeyChecking=yes",
                  "-o", "ConnectTimeout=5",
                  "-o", "ConnectionAttempts=1",
                  "-o", "ServerAliveInterval=15",
                  "-o", "ServerAliveCountMax=2",
                  sshTarget,
                  s"exec sudo -n journalctl -u ${shellQuote(settings.serviceUnit)} -f -n $lines --no-pager"
                )
              case None =>
                Vector(
                  "/usr/bin/setsid", "--", "/bin/bash", "--noprofile", "--norc", "-c",
                  s"exec journalctl -u ${shellQuote(settings.serviceUnit)} -f -n $lines --no-pager"
                )
            SSE(streamProcessLines(command))
          }

  def readConfig: IO[PlainText] =
    readRemoteConfig.map(PlainText(_))

  def validateConfig(content: String): IO[ConfigValidateResult] =
    for
      settings <- currentSettings
      bytes <- validateConfigPayload(content)
      marker = s"__GROOT_MIHOMO_${UUID.randomUUID().toString.replace("-", "")}__"
      payload = if content.endsWith("\n") then content else content + "\n"
      script =
        s"""set -euo pipefail
           |export LC_ALL=C
           |tmp=$$(mktemp -d /tmp/groot-mihomo-val.XXXXXX)
           |chmod 700 "$$tmp"
           |trap 'rm -rf "$$tmp"' EXIT
           |cat <<'$marker' > "$$tmp/config.yaml"
           |$payload$marker
           |truncate -s ${bytes.length} "$$tmp/config.yaml"
           |test "$$(wc -c < "$$tmp/config.yaml")" -eq ${bytes.length}
           |set +e
           |out=$$(sudo -n ${shellQuote(settings.binaryPath)} -t -d ${shellQuote(settings.configDir)} -f "$$tmp/config.yaml" 2>&1)
           |rc=$$?
           |set -e
           |printf '%d\\n%s\\n' "$$rc" "$$out"
           |""".stripMargin
      res <- execChecked(script, "25s")
    yield
      val newline = res.stdout.indexOf('\n')
      val (rcText, outText) =
        if newline < 0 then (res.stdout.trim, "")
        else (res.stdout.substring(0, newline).trim, res.stdout.substring(newline + 1).trim)
      ConfigValidateResult(valid = rcText == "0", output = outText)

  def writeConfig(content: String): IO[ConfigWriteResult] =
    writeMutex.lock.surround {
      applyConfigChange(content)
    }

  def rollbackConfig: IO[ConfigRollbackResult] =
    writeMutex.lock.surround(currentSettings.flatMap { settings =>
      val fileName = fileNameOf(settings.configPath)
      val script =
        s"""set -euo pipefail
           |export LC_ALL=C
           |cfg=${shellQuote(settings.configPath)}
           |dir=${shellQuote(settings.configDir)}
           |bin=${shellQuote(settings.binaryPath)}
           |unit=${shellQuote(settings.serviceUnit)}
           |trap 'sudo -n rm -f "$$dir/.groot-config.tmp.$$" >/dev/null 2>&1 || true' EXIT
           |bak=$$(sudo -n find "$$dir" -maxdepth 1 -type f -name ${shellQuote(s"$fileName.bak.*")} -printf '%p\\n' | sort | tail -n 1)
           |if [ -z "$$bak" ]; then
           |  printf 'NO_BACKUP\\n'
           |  exit 0
           |fi
           |if ! out=$$(sudo -n "$$bin" -t -d "$$dir" -f "$$bak" 2>&1); then
           |  printf 'INVALID\\n%s\\n' "$$out"
           |  exit 0
           |fi
           |sudo -n install -o root -g root -m 600 "$$bak" "$$dir/.groot-config.tmp.$$"
           |sudo -n mv -f "$$dir/.groot-config.tmp.$$" "$$cfg"
           |active=$$(sudo -n systemctl is-active "$$unit" 2>/dev/null || true)
           |printf 'OK\\n%s\\n%s\\n' "$$bak" "$$active"
           |""".stripMargin
      for
        routerHost <- resolveRouterHost
        previousText <- readRemoteConfig.option
        res <- execChecked(script, "25s")
        lines = res.stdout.split("\n", -1).toVector
        outcome <- lines.headOption.getOrElse("") match
          case "NO_BACKUP" =>
            IO.raiseError(MihomoFailure("mihomo_not_found", s"No backup found for ${settings.configPath}"))
          case "INVALID" =>
            val detail = lines.drop(1).mkString("\n").trim
            IO.raiseError(MihomoFailure("mihomo_config_invalid", s"Latest backup failed mihomo -t validation: $detail"))
          case "OK" if lines.length >= 3 =>
            val restoredFrom = lines(1).trim
            val activeState = lines(2).trim
            for
              restoredText <- readRemoteConfig
              prevApi = previousText.flatMap(HostsEditor.parseApiEndpoint(_, routerHost, settings.apiUrlOverride))
              nextApi = HostsEditor.parseApiEndpoint(restoredText, routerHost, settings.apiUrlOverride)
              applied <- reloadOrRestart(activeState, prevApi, nextApi)
            yield ConfigRollbackResult(restoredFrom, reloaded = applied._1, restarted = applied._2)
          case _ =>
            IO.raiseError(MihomoFailure("mihomo_ssh", s"Unexpected rollback output: ${res.stdout.trim.take(200)}"))
      yield outcome
    })

  def listDns: IO[Vector[DnsHost]] =
    readRemoteConfig.map(HostsEditor.list)

  def setDns(host: String, value: String, force: Boolean): IO[ConfigWriteResult] =
    writeMutex.lock.surround {
      for
        before <- readRemoteConfig
        after <- IO(HostsEditor.upsert(before, host, value, force))
        result <-
          if after == before.replace("\r\n", "\n").replace('\r', '\n') then
            IO.pure(ConfigWriteResult(changed = false, reloaded = false, restarted = false, backupPath = None))
          else
            applyConfigWithKnownPrevious(before, after)
      yield result
    }

  def deleteDns(host: String, force: Boolean): IO[DnsDeleteResult] =
    writeMutex.lock.surround {
      for
        before <- readRemoteConfig
        (after, existed) <- IO(HostsEditor.delete(before, host, force))
        result <-
          if !existed then
            IO.pure(DnsDeleteResult(deleted = false, reloaded = false, restarted = false, backupPath = None))
          else
            applyConfigWithKnownPrevious(before, after).map { wr =>
              DnsDeleteResult(deleted = true, reloaded = wr.reloaded, restarted = wr.restarted, backupPath = wr.backupPath)
            }
      yield result
    }

  def proxyGroups: IO[Vector[ProxyGroup]] =
    withApi(api.groups)

  def proxyNodes(group: String): IO[ProxyGroupDetail] =
    withApi(api.nodes(_, group))

  def proxyUse(group: String, node: String): IO[ProxySwitchResult] =
    withApi(api.use(_, group, node))

  def proxyDelay(
    group: Option[String],
    groups: List[String],
    url: String,
    timeoutMs: Int
  ): IO[Vector[ProxyDelayItem]] =
    withApi(api.delay(_, group, groups, url, timeoutMs))

  def proxyConnections(
    host: Option[String],
    rule: Option[String],
    limit: Int
  ): IO[ProxyConnectionsSnapshot] =
    withApi(api.connections(_, host, rule, limit))

  private def applyConfigChange(nextContent: String): IO[ConfigWriteResult] =
    for
      _ <- validateConfigPayload(nextContent)
      before <- readRemoteConfig
      result <-
        if before == nextContent then
          IO.pure(ConfigWriteResult(changed = false, reloaded = false, restarted = false, backupPath = None))
        else
          applyConfigWithKnownPrevious(before, nextContent)
    yield result

  private def applyConfigWithKnownPrevious(previousContent: String, nextContent: String): IO[ConfigWriteResult] =
    for
      settings <- currentSettings
      bytes <- validateConfigPayload(nextContent)
      routerHost <- resolveRouterHost
      fileName = fileNameOf(settings.configPath)
      marker = s"__GROOT_MIHOMO_${UUID.randomUUID().toString.replace("-", "")}__"
      payload = if nextContent.endsWith("\n") then nextContent else nextContent + "\n"
      maxBackups = settings.maxBackups.max(1)
      script =
        s"""set -euo pipefail
           |export LC_ALL=C
           |cfg=${shellQuote(settings.configPath)}
           |dir=${shellQuote(settings.configDir)}
           |bin=${shellQuote(settings.binaryPath)}
           |unit=${shellQuote(settings.serviceUnit)}
           |tmp=$$(mktemp -d /tmp/groot-mihomo-write.XXXXXX)
           |chmod 700 "$$tmp"
           |trap 'rm -rf "$$tmp"; sudo -n rm -f "$$dir/.groot-config.tmp.$$" >/dev/null 2>&1 || true' EXIT
           |cat <<'$marker' > "$$tmp/config.yaml"
           |$payload$marker
           |truncate -s ${bytes.length} "$$tmp/config.yaml"
           |test "$$(wc -c < "$$tmp/config.yaml")" -eq ${bytes.length}
           |if ! out=$$(sudo -n "$$bin" -t -d "$$dir" -f "$$tmp/config.yaml" 2>&1); then
           |  printf 'INVALID\\n%s\\n' "$$out"
           |  exit 0
           |fi
           |ts=$$(date -u +%Y%m%d%H%M%S)
           |bak="$${cfg}.bak.$${ts}"
           |if sudo -n test -f "$$cfg"; then
           |  sudo -n cp --preserve=mode,ownership,timestamps "$$cfg" "$$bak"
           |else
           |  bak=""
           |fi
           |sudo -n install -o root -g root -m 600 "$$tmp/config.yaml" "$$dir/.groot-config.tmp.$$"
           |sudo -n mv -f "$$dir/.groot-config.tmp.$$" "$$cfg"
           |sudo -n find "$$dir" -maxdepth 1 -type f -name ${shellQuote(s"$fileName.bak.*")} -printf '%p\\n' | sort -r | tail -n +$$(( $maxBackups + 1 )) | xargs -r sudo -n rm -f
           |active=$$(sudo -n systemctl is-active "$$unit" 2>/dev/null || true)
           |printf 'OK\\n%s\\n%s\\n' "$$bak" "$$active"
           |""".stripMargin
      res <- execChecked(script, "30s")
      lines = res.stdout.split("\n", -1).toVector
      result <- lines.headOption.getOrElse("") match
        case "INVALID" =>
          val detail = lines.drop(1).mkString("\n").trim
          IO.raiseError(MihomoFailure("mihomo_config_invalid", s"mihomo -t validation failed: $detail"))
        case "OK" if lines.length >= 3 =>
          val backupPath = Option(lines(1).trim).filter(_.nonEmpty)
          val activeState = lines(2).trim
          val prevApi = HostsEditor.parseApiEndpoint(previousContent, routerHost, settings.apiUrlOverride)
          val nextApi = HostsEditor.parseApiEndpoint(nextContent, routerHost, settings.apiUrlOverride)
          reloadOrRestart(activeState, prevApi, nextApi).map { (reloaded, restarted) =>
            ConfigWriteResult(changed = true, reloaded = reloaded, restarted = restarted, backupPath = backupPath)
          }
        case _ =>
          IO.raiseError(MihomoFailure("mihomo_ssh", s"Unexpected config write output: ${res.stdout.trim.take(200)}"))
    yield result

  /** Hot-reload via `PUT /configs?force=true` when Mihomo is active and its controller endpoint address is unchanged. */
  private def reloadOrRestart(
    activeState: String,
    previousApi: Option[ApiEndpoint],
    nextApi: Option[ApiEndpoint]
  ): IO[(Boolean, Boolean)] = currentSettings.flatMap: settings =>
    if activeState == "active" then
      (previousApi, nextApi) match
        case (Some(prev), Some(next)) if prev.baseUrl == next.baseUrl =>
          api.reloadConfigs(prev, settings.configPath) *> apiCache.set(Some(next)).as((true, false))
        case _ =>
          val restartScript = s"set -euo pipefail\nsudo -n systemctl restart ${shellQuote(settings.serviceUnit)}\n"
          execChecked(restartScript, "25s") *> apiCache.set(nextApi).as((false, true))
    else
      val startScript = s"set -euo pipefail\nsudo -n systemctl start ${shellQuote(settings.serviceUnit)}\n"
      execChecked(startScript, "25s") *> apiCache.set(nextApi).as((false, true))

  private def withApi[A](action: ApiEndpoint => IO[A]): IO[A] =
    resolveApiEndpoint(forceRefresh = false).flatMap { endpoint =>
      action(endpoint).recoverWith {
        case err: MihomoFailure if err.code == "mihomo_auth" =>
          resolveApiEndpoint(forceRefresh = true).flatMap(action)
      }
    }

  private def resolveApiEndpoint(forceRefresh: Boolean): IO[ApiEndpoint] = currentSettings.flatMap: settings =>
    val validCache = !forceRefresh && cachedSettingsKey.forall(_ == settings)
    (if validCache then apiCache.get else IO.pure(None)).flatMap {
      case Some(cached) => IO.pure(cached)
      case None =>
        for
          routerHost <- resolveRouterHost
          configText <- readRemoteConfig
          endpoint <- IO.fromOption(HostsEditor.parseApiEndpoint(configText, routerHost, settings.apiUrlOverride))(
            MihomoFailure("invalid_mihomo", s"external-controller is not enabled in ${settings.configPath}")
          )
          _ <- IO { cachedSettingsKey = Some(settings) } *> apiCache.set(Some(endpoint))
        yield endpoint
    }

  private def resolveRouterHost: IO[String] =
    resolveShellEnv.map(env => HostsEditor.extractSshHost(env.target))

  private def readRemoteConfig: IO[String] = currentSettings.flatMap: settings =>
    val script =
      s"""set -euo pipefail
         |export LC_ALL=C
         |sudo -n cat ${shellQuote(settings.configPath)}
         |""".stripMargin
    execChecked(script, "15s").map(_.stdout)

  private def execChecked(script: String, timeout: String): IO[ShellResult] =
    runShell(script, timeout).adaptError {
      case err: MihomoFailure => err
      case other =>
        val msg = Option(other.getMessage).getOrElse(other.getClass.getSimpleName)
        if msg.contains("No shell environment") then MihomoFailure("mihomo_not_found", msg)
        else if msg.toLowerCase(java.util.Locale.ROOT).contains("exceeded") || msg.toLowerCase(java.util.Locale.ROOT).contains("timed out") then
          MihomoFailure("mihomo_timeout", msg)
        else MihomoFailure("mihomo_ssh", msg)
    }.flatMap { result =>
      if result.exitCode == 0 then IO.pure(result)
      else
        val detail = Option(result.stderr.trim).filter(_.nonEmpty).getOrElse(result.stdout.trim)
        IO.raiseError(MihomoFailure("mihomo_ssh", if detail.nonEmpty then detail else s"Remote command exited with ${result.exitCode}"))
    }

object MihomoService:
  val MaxConfigBytes = 60 * 1024

  def resource(
    shell: ShellService,
    settings: MihomoSettings = MihomoSettings()
  ): Resource[IO, MihomoService] =
    for
      httpClient <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build
      service <- Resource.eval(fromClient(shell, httpClient, settings))
    yield service

  def resourceWithConfig(
    shell: ShellService,
    mihomoCfg: IO[MihomoConfig],
    shellCfg: IO[groot.shell.ShellConfig]
  ): Resource[IO, MihomoService] =
    val mapped: IO[MihomoSettings] = (mihomoCfg, shellCfg).mapN { (m, s) =>
      MihomoSettings(m.shellEnv, m.configDir, m.configPath, m.binaryPath, m.serviceUnit,
        Option(m.apiUrl.trim).filter(_.nonEmpty), s.sshExecutable, m.maxBackups)
    }
    for
      httpClient <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build
      initSettings <- Resource.eval(mapped)
      mutex <- Resource.eval(Mutex[IO])
      cache <- Resource.eval(Ref.of[IO, Option[ApiEndpoint]](None))
    yield new MihomoService(
      runShell = (script, timeout) => mapped.flatMap(s => shell.execute(ShellRequest(s.shellEnv, script, timeout))),
      resolveShellEnv = mapped.flatMap(s => shell.list.flatMap(envs =>
        IO.fromOption(envs.find(_.name == s.shellEnv))(MihomoFailure("mihomo_not_found", s"Shell environment '${s.shellEnv}' is not registered")))),
      api = new MihomoApi(httpClient),
      settings = initSettings,
      writeMutex = mutex,
      apiCache = cache,
      settingsIO = mapped,
      dynamic = true
    )

  def fromClient(
    shell: ShellService,
    httpClient: Client[IO],
    settings: MihomoSettings = MihomoSettings()
  ): IO[MihomoService] =
    for
      mutex <- Mutex[IO]
      cache <- Ref.of[IO, Option[ApiEndpoint]](None)
    yield new MihomoService(
      runShell = (script, timeout) => shell.execute(ShellRequest(settings.shellEnv, script, timeout)),
      resolveShellEnv = shell.list.flatMap { envs =>
        IO.fromOption(envs.find(_.name == settings.shellEnv))(
          MihomoFailure("mihomo_not_found", s"Shell environment '${settings.shellEnv}' is not registered")
        )
      },
      api = new MihomoApi(httpClient),
      settings = settings,
      writeMutex = mutex,
      apiCache = cache
    )

  private def validateConfigPayload(content: String): IO[Array[Byte]] = IO {
    if content.stripPrefix("\uFEFF").trim.isEmpty || content.contains('\u0000') then
      throw MihomoFailure("invalid_mihomo", "Mihomo configuration must be nonblank UTF-8 text without NUL bytes")
    val bytes = content.getBytes(UTF_8)
    if bytes.length > MaxConfigBytes then
      throw MihomoFailure("mihomo_limit", s"Mihomo configuration exceeds ${MaxConfigBytes / 1024} KiB")
    bytes
  }

  private def parseProperties(text: String): Map[String, String] =
    text.linesIterator.flatMap { line =>
      val eqIdx = line.indexOf('=')
      if eqIdx <= 0 then None
      else Some(line.substring(0, eqIdx).trim -> line.substring(eqIdx + 1).trim)
    }.toMap

  private def fileNameOf(path: String): String =
    val slash = path.lastIndexOf('/')
    if slash < 0 then path else path.substring(slash + 1)

  private[mihomo] def shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

  private def streamProcessLines(command: Vector[String]): Stream[IO, PlainText] =
    Stream.resource(Dispatcher.sequential[IO]).flatMap { dispatcher =>
      Stream.eval(Queue.bounded[IO, Option[Either[Throwable, PlainText]]](256)).flatMap { queue =>
        val acquire = IO.blocking {
          val builder = new ProcessBuilder(command.asJava)
          builder.environment().remove("BASH_ENV")
          builder.redirectErrorStream(true)
          val child = builder.start()
          try child.getOutputStream.close() catch case NonFatal(_) => ()
          Thread.ofPlatform().daemon().name("mihomo-logs-follow").start(() => {
            try
              val reader = new BufferedReader(new InputStreamReader(child.getInputStream, UTF_8))
              var line = reader.readLine()
              while line != null do
                dispatcher.unsafeRunSync(queue.offer(Some(Right(PlainText(line)))))
                line = reader.readLine()
              val code = child.waitFor()
              if code != 0 then
                dispatcher.unsafeRunSync(queue.offer(Some(Left(MihomoFailure("mihomo_ssh", s"journalctl follow exited with $code")))))
              dispatcher.unsafeRunSync(queue.offer(None))
            catch
              case NonFatal(_) =>
                try dispatcher.unsafeRunSync(queue.offer(None)) catch case NonFatal(_) => ()
          })
          child
        }
        Stream.resource(Resource.make(acquire)(stopProcessGroup)).flatMap { _ =>
          Stream.fromQueueNoneTerminated(queue).rethrow
        }
      }
    }

  private def stopProcessGroup(child: java.lang.Process): IO[Unit] =
    def signal(pid: Long, sig: String): IO[Unit] = IO.blocking {
      val killProc = new ProcessBuilder("/bin/kill", s"-$sig", "--", s"-$pid")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      try killProc.getOutputStream.close() catch case NonFatal(_) => ()
      if !killProc.waitFor(1, TimeUnit.SECONDS) then killProc.destroyForcibly()
      ()
    }.handleErrorWith(_ => IO.unit)

    for
      pid <- IO(child.pid())
      _ <- signal(pid, "TERM")
      _ <- IO.blocking { child.toHandle.destroy(); () }.handleErrorWith(_ => IO.unit)
      _ <- IO.sleep(150.millis)
      _ <- signal(pid, "KILL")
      _ <- IO.blocking {
        if child.isAlive then child.toHandle.destroyForcibly()
        try child.getInputStream.close() catch case NonFatal(_) => ()
        try child.getErrorStream.close() catch case NonFatal(_) => ()
        child.waitFor(1, TimeUnit.SECONDS)
        ()
      }.handleErrorWith(_ => IO.unit)
    yield ()
