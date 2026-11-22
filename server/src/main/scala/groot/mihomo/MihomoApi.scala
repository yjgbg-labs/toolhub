package groot.mihomo

import cats.effect.IO
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Chunk
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import org.http4s.{Header, Headers, Method, Request, Response, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

/** Thin HTTP client for Mihomo's External Controller REST API. Never exposes controller secrets in errors or models. */
final class MihomoApi(client: Client[IO]):
  import MihomoApi.*

  def version(api: ApiEndpoint, timeout: FiniteDuration = 3.seconds): IO[Option[String]] =
    requestJson(api, Method.GET, baseUri(api) / "version", timeout = timeout).map { json =>
      json.hcursor.get[String]("version").toOption.map(_.trim).filter(_.nonEmpty)
    }

  def reloadConfigs(api: ApiEndpoint, remoteConfigPath: String, timeout: FiniteDuration = 20.seconds): IO[Unit] =
    val target = (baseUri(api) / "configs").withQueryParam("force", "true")
    val payload = Json.obj("path" -> Json.fromString(remoteConfigPath))
    sendRaw(api, Method.PUT, target, Some(payload), timeout).flatMap { (status, text) =>
      if status >= 200 && status < 300 then IO.unit
      else if status == 401 || status == 403 then
        IO.raiseError(MihomoFailure("mihomo_auth", s"Mihomo controller rejected authentication (HTTP $status)"))
      else
        IO.raiseError(MihomoFailure("mihomo_reload_failed", s"PUT /configs?force=true failed (HTTP $status): ${extractMessage(text)}"))
    }

  def groups(api: ApiEndpoint): IO[Vector[ProxyGroup]] =
    fetchProxiesMap(api).map { proxies =>
      proxies.valuesIterator
        .filter(entry => GroupTypes.contains(entry.kind))
        .map(entry => ProxyGroup(entry.name, entry.kind, entry.now, entry.all.size))
        .toVector
        .sortBy(_.name)
    }

  def nodes(api: ApiEndpoint, group: String): IO[ProxyGroupDetail] =
    val cleanGroup = requireNonBlank("group", group)
    fetchProxiesMap(api).flatMap { proxies =>
      proxies.get(cleanGroup).filter(e => GroupTypes.contains(e.kind)) match
        case None =>
          IO.raiseError(MihomoFailure("mihomo_not_found", s"No proxy group named '$cleanGroup'"))
        case Some(entry) =>
          val items = entry.all.map { nodeName =>
            val detail = proxies.get(nodeName)
            ProxyNode(
              name = nodeName,
              kind = detail.map(_.kind).getOrElse("Unknown"),
              alive = detail.map(_.alive).getOrElse(true),
              current = nodeName == entry.now,
              lastDelayMs = detail.flatMap(_.lastDelayMs)
            )
          }
          IO.pure(ProxyGroupDetail(entry.name, entry.kind, entry.now, items))
    }

  def use(api: ApiEndpoint, group: String, node: String): IO[ProxySwitchResult] =
    val cleanGroup = requireNonBlank("group", group)
    val cleanNode = requireNonBlank("node", node)
    fetchProxiesMap(api).flatMap { proxies =>
      proxies.get(cleanGroup).filter(e => GroupTypes.contains(e.kind)) match
        case None =>
          IO.raiseError(MihomoFailure("mihomo_not_found", s"No proxy group named '$cleanGroup'"))
        case Some(entry) if entry.kind != "Selector" =>
          IO.raiseError(MihomoFailure("mihomo_conflict", s"Group '$cleanGroup' is ${entry.kind}; only Selector groups can be switched manually"))
        case Some(entry) if !entry.all.contains(cleanNode) =>
          IO.raiseError(MihomoFailure("invalid_mihomo", s"Node '$cleanNode' is not a candidate in group '$cleanGroup'"))
        case Some(entry) =>
          val target = baseUri(api) / "proxies" / cleanGroup
          val payload = Json.obj("name" -> Json.fromString(cleanNode))
          sendRaw(api, Method.PUT, target, Some(payload), 10.seconds).flatMap { (status, text) =>
            if status >= 200 && status < 300 then
              IO.pure(ProxySwitchResult(cleanGroup, entry.now, cleanNode))
            else if status == 401 || status == 403 then
              IO.raiseError(MihomoFailure("mihomo_auth", s"Mihomo controller rejected authentication (HTTP $status)"))
            else
              IO.raiseError(MihomoFailure("mihomo_api", s"PUT /proxies/$cleanGroup failed (HTTP $status): ${extractMessage(text)}"))
          }
    }

  def delay(
    api: ApiEndpoint,
    group: Option[String],
    groups: List[String],
    testUrl: String,
    timeoutMs: Int
  ): IO[Vector[ProxyDelayItem]] =
    val cleanGroup = group.map(_.trim).filter(_.nonEmpty)
    val cleanGroups = groups.map(_.trim).filter(_.nonEmpty).distinct
    val cleanUrl = validateDelayUrl(testUrl)
    if timeoutMs < 100 || timeoutMs > 60000 then
      IO.raiseError(MihomoFailure("invalid_mihomo", "timeoutMs must be between 100 and 60000"))
    else if cleanGroup.nonEmpty && cleanGroups.nonEmpty then
      IO.raiseError(MihomoFailure("invalid_mihomo", "Specify either 'group' (all nodes of one group) or 'groups' (selected groups), not both"))
    else
      val requestDeadline = (timeoutMs.toLong + 5000L).millis
      cleanGroup match
        case Some(targetGroup) =>
          fetchSingleProxy(api, targetGroup).flatMap { entry =>
            if !GroupTypes.contains(entry.kind) then
              IO.raiseError(MihomoFailure("mihomo_not_found", s"No proxy group named '$targetGroup'"))
            else
              val uri = (baseUri(api) / "group" / targetGroup / "delay")
                .withQueryParam("url", cleanUrl)
                .withQueryParam("timeout", timeoutMs.toString)
              sendRaw(api, Method.GET, uri, None, requestDeadline).flatMap { (status, text) =>
                if status == 401 || status == 403 then
                  IO.raiseError(MihomoFailure("mihomo_auth", s"Mihomo controller rejected authentication (HTTP $status)"))
                else if status >= 200 && status < 300 then
                  parseJsonText(text).map { json =>
                    val obj = json.asObject.map(_.toMap).getOrElse(Map.empty)
                    val knownSet = entry.all.toSet
                    val allNames = entry.all ++ obj.keys.filterNot(knownSet.contains)
                    allNames.map { nodeName =>
                      obj.get(nodeName) match
                        case Some(v) =>
                          v.asNumber.flatMap(_.toInt).filter(_ > 0) match
                            case Some(ms) => ProxyDelayItem(Some(targetGroup), nodeName, Some(ms), None)
                            case None =>
                              val msg = v.hcursor.get[String]("message").toOption.getOrElse("timeout")
                              ProxyDelayItem(Some(targetGroup), nodeName, None, Some(msg))
                        case None =>
                          ProxyDelayItem(Some(targetGroup), nodeName, None, Some("timeout"))
                    }.sortBy(item => (item.delayMs.getOrElse(Int.MaxValue), item.node))
                  }
                else if status == 408 || status == 502 || status == 503 || status == 504 then
                  val msg = extractMessage(text)
                  IO.pure(entry.all.map(nodeName => ProxyDelayItem(Some(targetGroup), nodeName, None, Some(msg))))
                else
                  IO.raiseError(MihomoFailure("mihomo_api", s"GET /group/$targetGroup/delay failed (HTTP $status): ${extractMessage(text)}"))
              }
          }

        case None =>
          fetchProxiesMap(api).flatMap { proxies =>
            val selectedEntries: IO[Vector[RawProxy]] =
              if cleanGroups.nonEmpty then
                cleanGroups.toVector.traverse { name =>
                  proxies.get(name).filter(e => GroupTypes.contains(e.kind)) match
                    case Some(found) => IO.pure(found)
                    case None => IO.raiseError(MihomoFailure("mihomo_not_found", s"No proxy group named '$name'"))
                }
              else
                IO.pure(proxies.valuesIterator.filter(e => GroupTypes.contains(e.kind)).toVector.sortBy(_.name))

            selectedEntries.flatMap { entries =>
              entries.parTraverseN(8) { entry =>
                if entry.now.isEmpty then
                  IO.pure(ProxyDelayItem(Some(entry.name), "", None, Some("no active node")))
                else
                  val uri = (baseUri(api) / "proxies" / entry.now / "delay")
                    .withQueryParam("url", cleanUrl)
                    .withQueryParam("timeout", timeoutMs.toString)
                  sendRaw(api, Method.GET, uri, None, requestDeadline).flatMap { (status, text) =>
                    if status == 401 || status == 403 then
                      IO.raiseError(MihomoFailure("mihomo_auth", s"Mihomo controller rejected authentication (HTTP $status)"))
                    else if status >= 200 && status < 300 then
                      parseJsonText(text).map { json =>
                        json.hcursor.get[Int]("delay").toOption.filter(_ > 0) match
                          case Some(ms) => ProxyDelayItem(Some(entry.name), entry.now, Some(ms), None)
                          case None => ProxyDelayItem(Some(entry.name), entry.now, None, Some("invalid delay"))
                      }
                    else
                      IO.pure(ProxyDelayItem(Some(entry.name), entry.now, None, Some(extractMessage(text))))
                  }
              }
            }
          }

  def connections(
    api: ApiEndpoint,
    hostFilter: Option[String],
    ruleFilter: Option[String],
    limit: Int
  ): IO[ProxyConnectionsSnapshot] =
    if limit < 1 || limit > 500 then
      IO.raiseError(MihomoFailure("invalid_mihomo", "limit must be between 1 and 500"))
    else
      val hostNeedle = hostFilter.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty)
      val ruleNeedle = ruleFilter.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty)
      requestJson(api, Method.GET, baseUri(api) / "connections", timeout = 10.seconds).map { json =>
        val cursor = json.hcursor
        val downTotal = cursor.get[Long]("downloadTotal").getOrElse(0L)
        val upTotal = cursor.get[Long]("uploadTotal").getOrElse(0L)
        val rawItems = cursor.downField("connections").as[Vector[Json]].getOrElse(Vector.empty)
        val parsed = rawItems.map(decodeConnection)
        val filtered = parsed.filter { conn =>
          val hostOk = hostNeedle.forall { needle =>
            conn.host.toLowerCase(java.util.Locale.ROOT).contains(needle) ||
              conn.destinationIp.toLowerCase(java.util.Locale.ROOT).contains(needle)
          }
          val ruleOk = ruleNeedle.forall { needle =>
            conn.rule.toLowerCase(java.util.Locale.ROOT).contains(needle)
          }
          hostOk && ruleOk
        }
        val top = filtered
          .sortBy(c => (-(c.downloadBytes + c.uploadBytes), c.id))
          .take(limit)
        ProxyConnectionsSnapshot(
          totalConnections = parsed.size,
          downloadTotalBytes = downTotal,
          uploadTotalBytes = upTotal,
          connections = top
        )
      }

  private def fetchProxiesMap(api: ApiEndpoint): IO[Map[String, RawProxy]] =
    requestJson(api, Method.GET, baseUri(api) / "proxies", timeout = 10.seconds).flatMap { json =>
      json.hcursor.downField("proxies").as[Map[String, Json]] match
        case Left(err) => IO.raiseError(MihomoFailure("mihomo_api", s"Invalid /proxies payload: ${err.message}"))
        case Right(entries) => IO.pure(entries.map((k, v) => k -> decodeRawProxy(k, v)))
    }

  private def fetchSingleProxy(api: ApiEndpoint, name: String): IO[RawProxy] =
    requestJson(api, Method.GET, baseUri(api) / "proxies" / name, timeout = 10.seconds).map { json =>
      decodeRawProxy(name, json)
    }

  private def requestJson(
    api: ApiEndpoint,
    method: Method,
    uri: Uri,
    body: Option[Json] = None,
    timeout: FiniteDuration
  ): IO[Json] =
    sendRaw(api, method, uri, body, timeout).flatMap { (status, text) =>
      if status >= 200 && status < 300 then parseJsonText(text)
      else if status == 401 || status == 403 then
        IO.raiseError(MihomoFailure("mihomo_auth", s"Mihomo controller rejected authentication (HTTP $status)"))
      else if status == 404 then
        IO.raiseError(MihomoFailure("mihomo_not_found", s"Mihomo resource not found: ${extractMessage(text)}"))
      else
        IO.raiseError(MihomoFailure("mihomo_api", s"Mihomo API ${method.name} ${uri.path.renderString} -> HTTP $status: ${extractMessage(text)}"))
    }

  private def sendRaw(
    api: ApiEndpoint,
    method: Method,
    uri: Uri,
    body: Option[Json],
    timeout: FiniteDuration
  ): IO[(Int, String)] =
    val authHeaders =
      if api.secret.nonEmpty then List(Header.Raw(CIString("Authorization"), s"Bearer ${api.secret}"))
      else Nil
    val contentHeaders =
      if body.nonEmpty then List(Header.Raw(CIString("Content-Type"), "application/json"))
      else Nil
    val baseReq = Request[IO](
      method = method,
      uri = uri,
      headers = Headers(Header.Raw(CIString("Accept"), "application/json") :: authHeaders ::: contentHeaders)
    )
    val req = body.fold(baseReq) { json =>
      val bytes = json.noSpaces.getBytes(UTF_8)
      baseReq
        .withBodyStream(fs2.Stream.chunk(Chunk.array(bytes)))
        .putHeaders(Header.Raw(CIString("Content-Length"), bytes.length.toString))
    }
    client.run(req).use { response =>
      readBoundedText(response).map(text => (response.status.code, text))
    }.timeoutTo(
      timeout,
      IO.raiseError(MihomoFailure("mihomo_timeout", s"Mihomo controller request timed out after ${timeout.toSeconds}s"))
    ).adaptError {
      case err: MihomoFailure => err
      case other =>
        MihomoFailure("mihomo_api", s"Cannot reach Mihomo controller at ${api.baseUrl}: ${Option(other.getMessage).getOrElse(other.getClass.getSimpleName)}")
    }

  private def readBoundedText(response: Response[IO]): IO[String] =
    response.body.take(MaxResponseBytes + 1L).compile.to(Array).flatMap { bytes =>
      if bytes.length > MaxResponseBytes then
        IO.raiseError(MihomoFailure("mihomo_limit", "Mihomo controller response exceeds 8 MiB"))
      else
        IO.pure(new String(bytes, UTF_8))
    }

object MihomoApi:
  val DefaultDelayUrl = "http://www.gstatic.com/generate_204"
  val DefaultDelayTimeoutMs = 5000
  private val MaxResponseBytes = 8 * 1024 * 1024
  private val GroupTypes = Set("Selector", "URLTest", "Fallback", "LoadBalance")

  private final case class RawProxy(
    name: String,
    kind: String,
    now: String,
    all: Vector[String],
    alive: Boolean,
    lastDelayMs: Option[Int]
  )

  private def baseUri(api: ApiEndpoint): Uri =
    Uri.fromString(api.baseUrl).fold(
      _ => throw MihomoFailure("invalid_mihomo", s"Invalid Mihomo controller URL: ${api.baseUrl}"),
      identity
    )

  private def requireNonBlank(field: String, value: String): String =
    val trimmed = value.trim
    if trimmed.isEmpty || trimmed.contains('\u0000') then
      throw MihomoFailure("invalid_mihomo", s"$field must be nonblank")
    trimmed

  private def validateDelayUrl(raw: String): String =
    val trimmed = raw.trim
    val parsed = Uri.fromString(trimmed).toOption
    if !parsed.exists(u => u.scheme.exists(s => s == Uri.Scheme.http || s == Uri.Scheme.https) && u.host.nonEmpty) then
      throw MihomoFailure("invalid_mihomo", "Delay test url must be an absolute http:// or https:// URL")
    trimmed

  private def parseJsonText(text: String): IO[Json] =
    val trimmed = text.trim
    if trimmed.isEmpty then IO.pure(Json.Null)
    else IO.fromEither(parse(trimmed).leftMap(err => MihomoFailure("mihomo_api", s"Invalid JSON from Mihomo controller: ${err.message}")))

  private def extractMessage(text: String): String =
    val trimmed = text.trim
    if trimmed.isEmpty then "empty response"
    else
      parse(trimmed).toOption
        .flatMap(_.hcursor.get[String]("message").toOption)
        .filter(_.nonEmpty)
        .getOrElse(trimmed.take(240))

  private def decodeRawProxy(fallbackName: String, json: Json): RawProxy =
    val c: HCursor = json.hcursor
    val name = c.get[String]("name").getOrElse(fallbackName)
    val kind = c.get[String]("type").getOrElse("Unknown")
    val now = c.get[String]("now").getOrElse("")
    val all = c.downField("all").as[Vector[String]].getOrElse(Vector.empty)
    val alive = c.get[Boolean]("alive").getOrElse(true)
    val history = c.downField("history").as[Vector[Json]].getOrElse(Vector.empty)
    val lastDelay = history.lastOption
      .flatMap(_.hcursor.get[Int]("delay").toOption)
      .filter(_ > 0)
    RawProxy(name, kind, now, all, alive, lastDelay)

  private def decodeConnection(json: Json): ProxyConnection =
    val c = json.hcursor
    val meta = c.downField("metadata")
    val host = meta.get[String]("host").toOption.filter(_.nonEmpty)
      .orElse(meta.get[String]("sniffHost").toOption.filter(_.nonEmpty))
      .orElse(meta.get[String]("destinationIP").toOption.filter(_.nonEmpty))
      .getOrElse("")
    val chains = c.downField("chains").as[Vector[String]].getOrElse(Vector.empty).reverse
    val ruleName = c.get[String]("rule").getOrElse("")
    val rulePayload = c.get[String]("rulePayload").getOrElse("")
    val rule = if rulePayload.nonEmpty then s"$ruleName($rulePayload)" else ruleName
    ProxyConnection(
      id = c.get[String]("id").getOrElse(""),
      host = host,
      network = meta.get[String]("network").getOrElse(""),
      sourceIp = meta.get[String]("sourceIP").getOrElse(""),
      destinationIp = meta.get[String]("destinationIP").getOrElse(""),
      destinationPort = meta.get[String]("destinationPort").getOrElse(""),
      downloadBytes = c.get[Long]("download").getOrElse(0L),
      uploadBytes = c.get[Long]("upload").getOrElse(0L),
      chains = chains,
      rule = rule,
      start = c.get[String]("start").getOrElse("")
    )
