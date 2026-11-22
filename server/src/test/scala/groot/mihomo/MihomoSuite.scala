package groot.mihomo

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Mutex
import framework.rpc.PlainText
import framework.rpcDsl.*
import fs2.Stream
import groot.graaljs.GraalCommandSdk
import groot.shell.{ShellEnvironment, ShellKind, ShellResult}
import io.circe.Json
import io.circe.parser.parse
import io.swagger.v3.parser.OpenAPIV3Parser
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class MihomoSuite extends CatsEffectSuite:

  private val sampleConfig =
    """proxy-groups:
      |  - name: AUTO
      |    type: url-test
      |  - name: AI
      |    type: url-test
      |external-controller: 0.0.0.0:9090
      |hosts:
      |  # external-dns-mihomo:start
      |  's3.yjgbg.lab': 10.0.0.30
      |  'redis.yjgbg.lab': 10.0.0.34
      |  # external-dns-mihomo:end
      |  '2048.yjgbg.lab': bucket-web.yjgbg.lab
      |dns:
      |  enable: true
      |secret: "init-secret"
      |
      |
      |""".stripMargin

  private def tempDir: Resource[IO, Path] = Resource.make(
    IO.blocking {
      val base = Path.of("/tmp/opencode")
      Files.createDirectories(base)
      Files.createTempDirectory(base, "mihomo-suite-")
    }
  )(dir => IO.blocking {
    Using.resource(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists))
  })

  private final class RouterHarness(
    val service: MihomoService,
    val reloadCalls: Ref[IO, Vector[String]],
    val selectedNode: Ref[IO, String],
    val activeSecret: Ref[IO, String],
    val configPath: Path
  )

  private def buildHarness(root: Path, maxBackups: Int = 3): IO[RouterHarness] =
    for
      reloadCalls <- Ref.of[IO, Vector[String]](Vector.empty)
      selectedNode <- Ref.of[IO, String]("🇭🇰 香港 01")
      activeSecret <- Ref.of[IO, String]("init-secret")
      activeStateRef <- Ref.of[IO, String]("active")
      binDir <- IO.blocking {
        val bin = root.resolve("bin")
        val etc = root.resolve("etc/mihomo")
        Files.createDirectories(bin)
        Files.createDirectories(etc)
        val cfg = etc.resolve("config.yaml")
        Files.writeString(cfg, sampleConfig, UTF_8)
        val stateFile = root.resolve("service-state")
        Files.writeString(stateFile, "active", UTF_8)

        def writeExec(name: String, body: String): Path =
          val file = bin.resolve(name)
          Files.writeString(file, body, UTF_8)
          Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
          file

        // Local sudo shim for non-root unit tests: strips `-n`, `-o root`, `-g root` from install.
        writeExec(
          "sudo",
          """#!/usr/bin/env bash
            |set -euo pipefail
            |if [ "${1:-}" = "-n" ]; then shift; fi
            |if [ "${1:-}" = "install" ]; then
            |  shift
            |  args=()
            |  while [ $# -gt 0 ]; do
            |    case "$1" in
            |      -o|-g) shift 2 ;;
            |      *) args+=("$1"); shift ;;
            |    esac
            |  done
            |  exec install "${args[@]}"
            |fi
            |exec "$@"
            |""".stripMargin
        )

        writeExec(
          "mihomo",
          """#!/usr/bin/env bash
            |set -euo pipefail
            |cfg=""
            |while [ $# -gt 0 ]; do
            |  case "$1" in
            |    -f) cfg="$2"; shift 2 ;;
            |    *) shift ;;
            |  esac
            |done
            |if grep -q "BROKEN_CONFIG" "$cfg"; then
            |  echo "configuration file $cfg test failed: broken rule" >&2
            |  exit 1
            |fi
            |echo "configuration file $cfg test is successful"
            |""".stripMargin
        )

        writeExec(
          "systemctl",
          s"""#!/usr/bin/env bash
             |set -euo pipefail
             |state_file="${stateFile.toString}"
             |cmd="$${1:-}"
             |case "$$cmd" in
             |  show)
             |    st=$$(cat "$$state_file")
             |    sub="running"
             |    pid="4242"
             |    if [ "$$st" != "active" ]; then sub="dead"; pid="0"; fi
             |    printf 'ActiveState=%s\\nSubState=%s\\nUnitFileState=enabled\\nMainPID=%s\\nActiveEnterTimestamp=Sun 2026-11-22 12:00:00 UTC\\nMemoryCurrent=104857600\\n' "$$st" "$$sub" "$$pid"
             |    ;;
             |  is-active)
             |    st=$$(cat "$$state_file")
             |    printf '%s\\n' "$$st"
             |    [ "$$st" = "active" ]
             |    ;;
             |  start|restart)
             |    printf 'active' > "$$state_file"
             |    ;;
             |  stop)
             |    printf 'inactive' > "$$state_file"
             |    ;;
             |  *)
             |    exit 2
             |    ;;
             |esac
             |""".stripMargin
        )

        writeExec(
          "journalctl",
          """#!/usr/bin/env bash
            |set -euo pipefail
            |printf 'Nov 22 12:00:01 r6s mihomo[4242]: started\nNov 22 12:00:02 r6s mihomo[4242]: ready\n'
            |""".stripMargin
        )

        (bin, etc, cfg)
      }
      (bin, etc, cfg) = binDir
      mockController = HttpApp[IO] { req =>
        activeSecret.get.flatMap { expectedSecret =>
          val auth = req.headers.get(CIString("Authorization")).map(_.head.value)
          if auth != Some(s"Bearer $expectedSecret") then
            IO.pure(Response[IO](Status.Unauthorized).withEntity("""{"message":"Unauthorized"}"""))
          else
            val segments = req.uri.path.segments.map(_.decoded())
            (req.method, segments) match
              case (Method.GET, Vector("version")) =>
                IO.pure(Response[IO](Status.Ok).withEntity("""{"version":"v1.19.15"}"""))

              case (Method.PUT, Vector("configs")) if req.uri.query.params.get("force").contains("true") =>
                req.as[String].flatMap { bodyText =>
                  val path = parse(bodyText).toOption.flatMap(_.hcursor.get[String]("path").toOption).getOrElse("")
                  val reloadedText = Files.readString(Path.of(path), UTF_8)
                  val updatedSecret = HostsEditor.parseApiEndpoint(reloadedText, "100.64.0.7")
                    .map(_.secret).getOrElse(expectedSecret)
                  reloadCalls.update(_ :+ path) *> activeSecret.set(updatedSecret) *>
                    IO.pure(Response[IO](Status.NoContent))
                }

              case (Method.GET, Vector("proxies")) =>
                selectedNode.get.map { currentSelect =>
                  val payload =
                    s"""{
                       |  "proxies": {
                       |    "AUTO": {"name":"AUTO","type":"URLTest","now":"🇯🇵 日本 03","all":["🇯🇵 日本 03","🇭🇰 香港 01"]},
                       |    "AI": {"name":"AI","type":"URLTest","now":"🇺🇸 美国 09 家宽","all":["🇺🇸 美国 09 家宽"]},
                       |    "SELECT": {"name":"SELECT","type":"Selector","now":"$currentSelect","all":["🇭🇰 香港 01","🇯🇵 日本 03"]},
                       |    "LB": {"name":"LB","type":"LoadBalance","now":"","all":["🇭🇰 香港 01"]},
                       |    "🇯🇵 日本 03": {"name":"🇯🇵 日本 03","type":"Trojan","alive":true,"history":[{"delay":320}]},
                       |    "🇭🇰 香港 01": {"name":"🇭🇰 香港 01","type":"Shadowsocks","alive":true,"history":[{"delay":180}]},
                       |    "🇺🇸 美国 09 家宽": {"name":"🇺🇸 美国 09 家宽","type":"Trojan","alive":true,"history":[{"delay":740}]}
                       |  }
                       |}""".stripMargin
                  Response[IO](Status.Ok).withEntity(payload)
                }

              case (Method.GET, Vector("proxies", name)) =>
                if name == "AUTO" then
                  IO.pure(Response[IO](Status.Ok).withEntity("""{"name":"AUTO","type":"URLTest","now":"🇯🇵 日本 03","all":["🇯🇵 日本 03","🇭🇰 香港 01","DEAD_NODE"]}"""))
                else
                  IO.pure(Response[IO](Status.NotFound).withEntity("""{"message":"Not Found"}"""))

              case (Method.PUT, Vector("proxies", "SELECT")) =>
                req.as[String].flatMap { bodyText =>
                  val next = parse(bodyText).toOption.flatMap(_.hcursor.get[String]("name").toOption).getOrElse("")
                  selectedNode.set(next).as(Response[IO](Status.NoContent))
                }

              case (Method.GET, Vector("proxies", nodeName, "delay")) =>
                nodeName match
                  case "🇯🇵 日本 03" => IO.pure(Response[IO](Status.Ok).withEntity("""{"delay":315}"""))
                  case "🇺🇸 美国 09 家宽" => IO.pure(Response[IO](Status.Ok).withEntity("""{"delay":742}"""))
                  case "🇭🇰 香港 01" => IO.pure(Response[IO](Status.Ok).withEntity("""{"delay":188}"""))
                  case _ => IO.pure(Response[IO](Status.GatewayTimeout).withEntity("""{"message":"Timeout"}"""))

              case (Method.GET, Vector("group", "AUTO", "delay")) =>
                IO.pure(Response[IO](Status.Ok).withEntity("""{"🇭🇰 香港 01":185,"🇯🇵 日本 03":310}"""))

              case (Method.GET, Vector("connections")) =>
                val payload =
                  """{
                    |  "downloadTotal": 9000,
                    |  "uploadTotal": 3000,
                    |  "connections": [
                    |    {
                    |      "id": "c1",
                    |      "download": 1000,
                    |      "upload": 500,
                    |      "chains": ["🇯🇵 日本 03", "AUTO"],
                    |      "rule": "DomainSuffix",
                    |      "rulePayload": "github.com",
                    |      "start": "2026-11-22T12:00:00Z",
                    |      "metadata": {"host":"api.github.com","network":"tcp","sourceIP":"10.0.0.238","destinationIP":"140.82.114.6","destinationPort":"443"}
                    |    },
                    |    {
                    |      "id": "c2",
                    |      "download": 8000,
                    |      "upload": 2500,
                    |      "chains": ["🇺🇸 美国 09 家宽", "AI"],
                    |      "rule": "DomainSuffix",
                    |      "rulePayload": "google.com",
                    |      "start": "2026-11-22T12:00:05Z",
                    |      "metadata": {"host":"mtalk.google.com","network":"tcp","sourceIP":"10.0.0.197","destinationIP":"142.250.1.188","destinationPort":"5228"}
                    |    }
                    |  ]
                    |}""".stripMargin
                IO.pure(Response[IO](Status.Ok).withEntity(payload))

              case _ =>
                IO.pure(Response[IO](Status.NotFound).withEntity("""{"message":"Unknown route"}"""))
        }
      }
      runLocalBash = (script: String, _: String) => IO.blocking {
        val pb = new ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", script)
        pb.environment().put("PATH", s"${bin.toString}:${pb.environment().getOrDefault("PATH", "/usr/bin:/bin")}")
        val proc = pb.start()
        proc.getOutputStream.close()
        val out = new String(proc.getInputStream.readAllBytes(), UTF_8)
        val err = new String(proc.getErrorStream.readAllBytes(), UTF_8)
        val code = proc.waitFor()
        ShellResult(code, out, err)
      }
      mutex <- Mutex[IO]
      cache <- Ref.of[IO, Option[ApiEndpoint]](None)
      settings = MihomoSettings(
        shellEnv = "r6s",
        configDir = etc.toString,
        configPath = cfg.toString,
        binaryPath = bin.resolve("mihomo").toString,
        serviceUnit = "mihomo.service",
        apiUrlOverride = None,
        maxBackups = maxBackups
      )
      service = new MihomoService(
        runShell = runLocalBash,
        resolveShellEnv = IO.pure(ShellEnvironment("r6s", ShellKind.bash, Some("router@100.64.0.7"))),
        api = new MihomoApi(Client.fromHttpApp(mockController)),
        settings = settings,
        writeMutex = mutex,
        apiCache = cache,
        customFollow = Some(lines => IO.pure(Stream.emits(List(PlainText(s"follow-$lines-1"), PlainText("follow-2"))).covary[IO]))
      )
      _ = activeStateRef
    yield new RouterHarness(service, reloadCalls, selectedNode, activeSecret, cfg)

  test("HostsEditor preserves comments, protects external-dns-mihomo entries and inserts manual hosts cleanly") {
    val initial = HostsEditor.list(sampleConfig)
    assertEquals(
      initial,
      Vector(
        DnsHost("s3.yjgbg.lab", "10.0.0.30", managed = true),
        DnsHost("redis.yjgbg.lab", "10.0.0.34", managed = true),
        DnsHost("2048.yjgbg.lab", "bucket-web.yjgbg.lab", managed = false)
      )
    )

    // Refuses modifying or deleting external-dns-mihomo entries without force=true
    intercept[MihomoFailure](HostsEditor.upsert(sampleConfig, "s3.yjgbg.lab", "10.0.0.99", force = false))
    intercept[MihomoFailure](HostsEditor.delete(sampleConfig, "s3.yjgbg.lab", force = false))

    // Inserts new manual host outside the managed block and before `dns:`
    val added = HostsEditor.upsert(sampleConfig, "nas.yjgbg.lab", "10.0.0.50")
    assert(added.contains("# external-dns-mihomo:end\n  '2048.yjgbg.lab': bucket-web.yjgbg.lab\n  'nas.yjgbg.lab': 10.0.0.50\ndns:"))
    assertEquals(HostsEditor.upsert(added, "nas.yjgbg.lab", "10.0.0.50"), added)

    // Force-updating a managed entry keeps it inside the managed block
    val forced = HostsEditor.upsert(added, "s3.yjgbg.lab", "10.0.0.31", force = true)
    assertEquals(HostsEditor.list(forced).find(_.host == "s3.yjgbg.lab"), Some(DnsHost("s3.yjgbg.lab", "10.0.0.31", managed = true)))

    // Deleting manual entry leaves comments intact
    val (afterDelete, deleted) = HostsEditor.delete(forced, "nas.yjgbg.lab")
    assert(deleted)
    assert(afterDelete.contains("# external-dns-mihomo:start"))
    assert(!afterDelete.contains("nas.yjgbg.lab"))

    // Controller endpoint resolution
    assertEquals(
      HostsEditor.parseApiEndpoint(sampleConfig, "100.64.0.7"),
      Some(ApiEndpoint("http://100.64.0.7:9090", "init-secret"))
    )
    assertEquals(
      HostsEditor.parseApiEndpoint("external-controller: 127.0.0.1:9191\nsecret: ''\n", "100.64.0.7"),
      Some(ApiEndpoint("http://127.0.0.1:9191", ""))
    )
  }

  test("config write validates with mihomo -t, prunes backups, hot-reloads via PUT /configs?force=true and refreshes rotated secrets") {
    tempDir.use { root =>
      for
        h <- buildHarness(root, maxBackups = 2)
        st <- h.service.status
        _ = assertEquals(st.activeState, "active")
        _ = assertEquals(st.mainPid, 4242)
        _ = assertEquals(st.version, Some("v1.19.15"))

        // Unchanged write is a no-op without backup or PUT /configs
        noop <- h.service.writeConfig(sampleConfig)
        _ = assertEquals(noop, ConfigWriteResult(changed = false, reloaded = false, restarted = false, backupPath = None))
        reloads0 <- h.reloadCalls.get
        _ = assertEquals(reloads0, Vector.empty)

        // Invalid config fails validation and leaves active file untouched
        badCheck <- h.service.validateConfig(sampleConfig + "\n# BROKEN_CONFIG\n")
        _ = assertEquals(badCheck.valid, false)
        badWrite <- h.service.writeConfig(sampleConfig + "\n# BROKEN_CONFIG\n").attempt
        _ = assert(badWrite.left.exists(_.asInstanceOf[MihomoFailure].code == "mihomo_config_invalid"))
        currentAfterBad <- h.service.readConfig
        _ = assertEquals(currentAfterBad: String, sampleConfig)

        // Multiple valid writes create backups, prune beyond maxBackups=2, and hot-reload even across secret rotation
        _ <- IO.sleep(5.millis)
        v1 = sampleConfig.replace("\"init-secret\"", "\"secret-v1\"")
        w1 <- h.service.writeConfig(v1)
        _ = assert(w1.changed && w1.reloaded && !w1.restarted && w1.backupPath.nonEmpty)
        _ <- IO.sleep(1100.millis)
        v2 = v1.replace("\"secret-v1\"", "\"secret-v2\"")
        _ <- h.service.writeConfig(v2)
        _ <- IO.sleep(1100.millis)
        v3 = v2.replace("\"secret-v2\"", "\"secret-v3\"")
        _ <- h.service.writeConfig(v3)

        backups <- IO.blocking {
          Using.resource(Files.list(h.configPath.getParent)) { s =>
            s.iterator().asScala.map(_.getFileName.toString).filter(_.startsWith("config.yaml.bak.")).toVector
          }
        }
        _ = assertEquals(backups.size, 2)
        reloads <- h.reloadCalls.get
        _ = assertEquals(reloads.size, 3)

        // Proxy call succeeds immediately with the rotated secret-v3
        groups <- h.service.proxyGroups
        _ = assertEquals(groups.map(_.name), Vector("AI", "AUTO", "LB", "SELECT"))

        // Rollback restores the latest backup (v2) and hot-reloads
        rb <- h.service.rollbackConfig
        _ = assert(rb.reloaded && !rb.restarted)
        secretNow <- h.activeSecret.get
        _ = assertEquals(secretNow, "secret-v2")
      yield ()
    }
  }

  test("proxy delay, selector switching, connections filtering, DNS mutations, SDK exposure and OpenAPI validation") {
    tempDir.use { root =>
      for
        h <- buildHarness(root)
        app = httpApp {
          openapi {
            endpoint("/openapi.json")
            info {
              title("Groot Mihomo Test")
              version("1.0.0")
            }
          }
          MihomoRoutes.register(h.service)
        }
        parsedOpenApi = new OpenAPIV3Parser().readContents(app.openApiJson)
        openApiMessages = if parsedOpenApi.getMessages == null then Nil else parsedOpenApi.getMessages.asScala.toList
        _ = assertEquals(openApiMessages, Nil)

        sdk = GraalCommandSdk(app)
        // logsFollow is CLI-only, all other mihomo commands are exposed to GraalJS and Code Mode
        _ = assert(!sdk.commands.contains("mihomo.logsFollow"))
        _ = assert(sdk.commands.contains("mihomo.proxy.delay"))
        _ = assertEquals(app.endpoints.find(_.path == "/mihomo/logsFollow").get.exposedOn, Set(cli))

        // Concurrent delay check on selected groups via GraalCommandSdk (emoji + spaces in node names)
        delayReply <- sdk.call(
          "mihomo.proxy.delay",
          Json.obj("groups" -> Json.arr(Json.fromString("AUTO"), Json.fromString("AI")))
        )
        _ = assertEquals(delayReply.hcursor.get[Int]("status"), Right(200))
        delayItems = framework.model.JsonModel[Vector[ProxyDelayItem]].decoder
          .decodeJson(delayReply.hcursor.downField("body").focus.get).toOption.get
        _ = assertEquals(
          delayItems,
          Vector(
            ProxyDelayItem(Some("AUTO"), "🇯🇵 日本 03", Some(315), None),
            ProxyDelayItem(Some("AI"), "🇺🇸 美国 09 家宽", Some(742), None)
          )
        )

        // Group-wide delay includes timed-out candidate nodes omitted by Mihomo's /group/{name}/delay map
        groupDelay <- h.service.proxyDelay(Some("AUTO"), Nil, MihomoApi.DefaultDelayUrl, 5000)
        _ = assertEquals(
          groupDelay,
          Vector(
            ProxyDelayItem(Some("AUTO"), "🇭🇰 香港 01", Some(185), None),
            ProxyDelayItem(Some("AUTO"), "🇯🇵 日本 03", Some(310), None),
            ProxyDelayItem(Some("AUTO"), "DEAD_NODE", None, Some("timeout"))
          )
        )

        // Selector switch validation
        nonSelector <- h.service.proxyUse("AUTO", "🇯🇵 日本 03").attempt
        _ = assert(nonSelector.left.exists(_.asInstanceOf[MihomoFailure].code == "mihomo_conflict"))
        switched <- h.service.proxyUse("SELECT", "🇯🇵 日本 03")
        _ = assertEquals(switched, ProxySwitchResult("SELECT", "🇭🇰 香港 01", "🇯🇵 日本 03"))

        // Connections filtering & traffic ordering
        conns <- h.service.proxyConnections(None, None, 10)
        _ = assertEquals(conns.totalConnections, 2)
        _ = assertEquals(conns.connections.map(_.id), Vector("c2", "c1"))
        filtered <- h.service.proxyConnections(Some("github"), None, 10)
        _ = assertEquals(filtered.connections.map(_.id), Vector("c1"))

        // DNS set & delete via service
        dnsWrite <- h.service.setDns("grafana.yjgbg.lab", "10.0.0.60", force = false)
        _ = assert(dnsWrite.changed && dnsWrite.reloaded)
        dnsEntries <- h.service.listDns
        _ = assert(dnsEntries.contains(DnsHost("grafana.yjgbg.lab", "10.0.0.60", managed = false)))
        dnsDel <- h.service.deleteDns("grafana.yjgbg.lab", force = false)
        _ = assert(dnsDel.deleted && dnsDel.reloaded)

        // SSE logsFollow endpoint
        sseResp <- app.httpApp(Request[IO](Method.GET, Uri.unsafeFromString("/mihomo/logsFollow?lines=25")))
        _ = assertEquals(sseResp.status, Status.Ok)
        sseBody <- sseResp.body.through(fs2.text.utf8.decode).compile.string.timeout(3.seconds)
        _ = assert(sseBody.contains("data: follow-25-1"))
      yield ()
    }
  }
