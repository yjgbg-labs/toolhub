package groot.github

import cats.effect.IO
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.compression.{Compression, InflateParams}
import fs2.concurrent.Signal
import groot.config.{GitHubConfig, GrootConfig}
import io.circe.Json
import io.circe.parser.parse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import org.http4s.{Header, Headers, Method, Query, Request, Response, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

final class GitHubClient(
    http: Client[IO],
    val apiBase: Uri = Uri.unsafeFromString("https://api.github.com"),
    val oauthBase: Uri = Uri.unsafeFromString("https://github.com"),
    requestTimeout: FiniteDuration = 30.seconds,
    configSignal: Option[Signal[IO, GitHubConfig]] = None
):
  import GitHubClient.*

  private def currentSettings: IO[(Uri, Uri, FiniteDuration, Int, Int)] = configSignal match
    case None => IO.pure((apiBase, oauthBase, requestTimeout, maxJsonBytes, maxRawBytes))
    case Some(sig) => sig.get.map { cfg =>
      (
        Uri.fromString(cfg.apiBase.trim).getOrElse(apiBase),
        Uri.fromString(cfg.oauthBase.trim).getOrElse(oauthBase),
        GrootConfig.parseDuration("github.timeout", cfg.timeout),
        cfg.maxJsonBytes,
        cfg.maxRawBytes
      )
    }

  def getJson(token: String, path: String, query: Map[String, String] = Map.empty): IO[(Map[String, String], Json)] =
    sendJson(token, "GET", path, query).map((_, h, j) => h -> j)

  def sendJson(token: String, methodText: String, path: String, query: Map[String, String] = Map.empty,
      body: Option[Json] = None, headers: Map[String, String] = Map.empty): IO[(Int, Map[String, String], Json)] =
    val upper = methodText.trim.toUpperCase(java.util.Locale.ROOT)
    Method.fromString(upper) match
      case Left(_) => IO.raiseError(GitHubFailure("invalid_github", s"Unsupported HTTP method: $methodText"))
      case Right(m) if (m == Method.GET || m == Method.HEAD) && body.nonEmpty =>
        IO.raiseError(GitHubFailure("invalid_github", s"$upper request cannot carry a JSON body"))
      case Right(m) =>
        currentSettings.flatMap { (curApi, _, curTimeout, curMaxJson, _) =>
          execute(Some(token), m, resolveApiUri(curApi, path, query), headers, body.map(_.noSpaces.getBytes(UTF_8)), curMaxJson, failOnTruncate = true, curTimeout).flatMap {
            case (st, hdrs, bytes) if st < 200 || st >= 300 => IO.raiseError(decodeError(st, hdrs, bytes))
            case (st, hdrs, bytes) =>
              val j = if bytes.isEmpty || st == 204 || st == 205 then Json.Null else
                val t = new String(bytes, UTF_8); parse(t).getOrElse(Json.fromString(t))
              IO.pure((st, hdrs, j))
          }
        }

  def paginateArray(token: String, path: String, query: Map[String, String] = Map.empty, maxPages: Int = 10): IO[(Map[String, String], Vector[Json])] =
    val cap = math.max(1, math.min(maxPages, 50))
    currentSettings.flatMap { (curApi, _, curTimeout, curMaxJson, _) =>
      def loop(uri: Uri, page: Int, acc: Vector[Json], h0: Option[Map[String, String]]): IO[(Map[String, String], Vector[Json])] =
        execute(Some(token), Method.GET, uri, Map.empty, None, curMaxJson, failOnTruncate = true, curTimeout).flatMap {
          case (st, hdrs, bytes) if st < 200 || st >= 300 => IO.raiseError(decodeError(st, hdrs, bytes))
          case (_, hdrs, bytes) =>
            val items = (if bytes.isEmpty then Json.arr() else parseJson(bytes)).asArray.getOrElse(
              throw GitHubFailure("invalid_github", s"paginate=true requires a JSON array response from ${uri.path.renderString}"))
            val merged = acc ++ items
            val firstH = h0.orElse(Some(hdrs))
            hdrs.get("link").flatMap(nextLinkRe.findFirstMatchIn(_).map(_.group(1))) match
              case Some(next) if page < cap && items.nonEmpty =>
                loop(Uri.fromString(next).fold(e => throw GitHubFailure("github_upstream", e.message), identity), page + 1, merged, firstH)
              case _ => IO.pure(firstH.getOrElse(hdrs) -> merged)
        }
      loop(resolveApiUri(curApi, path, query), 1, Vector.empty, None)
    }

  def getText(token: String, path: String, accept: String = "application/vnd.github+json", maxBytes: Int = maxTextBytes): IO[(String, Boolean)] =
    currentSettings.flatMap { (curApi, _, curTimeout, _, curMaxRaw) =>
      val cap = math.max(1024, math.min(maxBytes, curMaxRaw))
      execute(Some(token), Method.GET, resolveApiUri(curApi, path, Map.empty), Map("Accept" -> accept), None, cap, failOnTruncate = false, curTimeout).flatMap {
        case (st, hdrs, bytes) if st < 200 || st >= 300 => IO.raiseError(decodeError(st, hdrs, bytes))
        case (_, _, bytes) => IO.pure((new String(bytes.take(cap), UTF_8), bytes.length > cap))
      }
    }

  def graphql(token: String, query: String, variables: Map[String, Json] = Map.empty, operationName: Option[String] = None): IO[Json] =
    if query.trim.isEmpty then IO.raiseError(GitHubFailure("invalid_github", "GraphQL query must not be blank"))
    else
      val fields = List(Some("query" -> Json.fromString(query)),
        Option.when(variables.nonEmpty)("variables" -> Json.fromFields(variables)),
        operationName.map(_.trim).filter(_.nonEmpty).map("operationName" -> Json.fromString(_))).flatten
      sendJson(token, "POST", "/graphql", body = Some(Json.obj(fields*))).flatMap { (_, _, payload) =>
        val errs = payload.hcursor.downField("errors").focus.flatMap(_.asArray).getOrElse(Vector.empty)
        if errs.isEmpty then IO.pure(payload.hcursor.downField("data").focus.getOrElse(payload))
        else
          val msg = errs.flatMap(_.hcursor.get[String]("message").toOption).mkString("; ")
          val code = if errs.headOption.flatMap(_.hcursor.get[String]("type").toOption).contains("NOT_FOUND") then "github_not_found" else "github_graphql"
          IO.raiseError(GitHubFailure(code, if msg.nonEmpty then msg else payload.noSpaces.take(400)))
      }

  def postOAuthForm(path: String, form: Vector[(String, String)]): IO[Json] =
    currentSettings.flatMap { (_, curOauth, curTimeout, _, _) =>
      val uri = Uri.unsafeFromString(curOauth.renderString.stripSuffix("/") + (if path.startsWith("/") then path else s"/$path"))
      val encoded = form.map((k, v) => s"${URLEncoder.encode(k, UTF_8)}=${URLEncoder.encode(v, UTF_8)}").mkString("&").getBytes(UTF_8)
      execute(None, Method.POST, uri, Map("Accept" -> "application/json", "Content-Type" -> "application/x-www-form-urlencoded"), Some(encoded), 262144, failOnTruncate = true, curTimeout).flatMap {
        case (st, hdrs, bytes) if st < 200 || st >= 300 => IO.raiseError(decodeError(st, hdrs, bytes))
        case (_, _, bytes) => IO(parseJson(bytes))
      }
    }

  private def execute(token: Option[String], method: Method, uri: Uri, extras: Map[String, String],
      body: Option[Array[Byte]], cap: Int, failOnTruncate: Boolean, activeTimeout: FiniteDuration): IO[(Int, Map[String, String], Array[Byte])] =
    val accept = extras.collectFirst { case (k, v) if k.equalsIgnoreCase("accept") => v }.getOrElse("application/vnd.github+json")
    val ctype = extras.collectFirst { case (k, v) if k.equalsIgnoreCase("content-type") => v }.getOrElse("application/json; charset=utf-8")
    val base = Vector(token.map(t => "Authorization" -> s"Bearer $t"), Some("Accept" -> accept), Some("X-GitHub-Api-Version" -> apiVersion),
      Some("User-Agent" -> userAgent), Some("Accept-Encoding" -> "gzip, deflate"),
      body.map(_ => "Content-Type" -> ctype), body.map(b => "Content-Length" -> b.length.toString)).flatten
    val custom = extras.toVector.filterNot((k, _) => reservedHeaders(k.toLowerCase(java.util.Locale.ROOT)))
    val req0 = Request[IO](method, uri, headers = Headers((base ++ custom).map((k, v) => Header.Raw(CIString(k), v))))
    val req = body.fold(req0)(b => req0.withBodyStream(Stream.chunk(Chunk.array(b))))
    follow(req, 0, if failOnTruncate then cap else cap + 1, failOnTruncate)
      .timeoutTo(activeTimeout, IO.raiseError(GitHubFailure("github_timeout", s"GitHub request timed out after $activeTimeout")))
      .adaptError { case e if !e.isInstanceOf[GitHubFailure] => GitHubFailure("github_upstream", s"GitHub request failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}") }

  private def follow(req: Request[IO], redirects: Int, readCap: Int, failOnTruncate: Boolean): IO[(Int, Map[String, String], Array[Byte])] =
    http.run(req).use { resp =>
      resp.headers.get(CIString("Location")).map(_.head.value).filter(_ => Set(301, 302, 303, 307, 308)(resp.status.code)) match
        case Some(loc) => IO {
          if redirects >= 10 then throw GitHubFailure("github_upstream", "GitHub exceeded 10 redirects")
          val target = req.uri.resolve(Uri.fromString(loc).fold(e => throw GitHubFailure("github_upstream", e.message), identity))
          val sameOrigin = originOf(req.uri) == originOf(target)
          val toGet = (Set(301, 302)(resp.status.code) && req.method == Method.POST) || (resp.status.code == 303 && req.method != Method.GET && req.method != Method.HEAD)
          val kept = req.headers.headers.filterNot { h =>
            val k = h.name.toString.toLowerCase(java.util.Locale.ROOT)
            (!sameOrigin && Set("authorization", "cookie", "host", "x-github-api-version")(k)) || (toGet && Set("content-type", "content-length")(k))
          }
          val next = Request[IO](if toGet then Method.GET else req.method, target, headers = Headers(kept))
          Left(if toGet then next else next.withBodyStream(req.body))
        }
        case None =>
          val ok = resp.status.code >= 200 && resp.status.code < 300
          readBytes(resp, if ok then readCap else 65536, failOnTruncate && ok).map(b => Right((resp.status.code, flattenHeaders(resp.headers), b)))
    }.flatMap {
      case Left(next) => follow(next, redirects + 1, readCap, failOnTruncate)
      case Right(res) => IO.pure(res)
    }

  private def resolveApiUri(activeApiBase: Uri, rawPath: String, extraQuery: Map[String, String]): Uri =
    val p = rawPath.trim
    if p.isEmpty then throw GitHubFailure("invalid_github", "API path must not be blank")
    val uri = if p.startsWith("https://") || p.startsWith("http://") then
      val u = Uri.fromString(p).fold(e => throw GitHubFailure("invalid_github", e.message), identity)
      if originOf(u) != originOf(activeApiBase) then throw GitHubFailure("invalid_github", s"Refusing to send GitHub token to external origin: ${u.host.map(_.value).getOrElse(p)}")
      u
    else Uri.fromString(activeApiBase.renderString.stripSuffix("/") + (if p.startsWith("/") then p else s"/$p")).fold(e => throw GitHubFailure("invalid_github", e.message), identity)
    if extraQuery.isEmpty then uri else uri.copy(query = Query.fromVector(uri.query.pairs ++ extraQuery.toVector.map((k, v) => k -> Some(v))))

object GitHubClient:
  val apiVersion = "2022-11-28"
  val userAgent = "groot-http/1.0"
  val maxJsonBytes: Int = 4 * 1024 * 1024
  val maxTextBytes: Int = 256 * 1024
  val maxRawBytes: Int = 1024 * 1024
  private val nextLinkRe = """<([^>]+)>\s*;\s*rel="next"""".r
  private val reservedHeaders = Set("authorization", "accept", "x-github-api-version", "user-agent", "accept-encoding", "content-type", "content-length", "transfer-encoding")

  private def originOf(u: Uri) = (u.scheme, u.host.map(_.value.toLowerCase(java.util.Locale.ROOT)), u.port.getOrElse(if u.scheme.contains(Uri.Scheme.https) then 443 else 80))
  private def flattenHeaders(h: Headers): Map[String, String] = h.headers.groupMap(_.name.toString.toLowerCase(java.util.Locale.ROOT))(_.value).map((k, vs) => k -> vs.mkString(", "))
  private def parseJson(b: Array[Byte]): Json = parse(new String(b, UTF_8)).fold(e => throw GitHubFailure("github_upstream", s"Invalid JSON: ${e.message}"), identity)

  private def readBytes(resp: Response[IO], cap: Int, failOnTruncate: Boolean): IO[Array[Byte]] =
    if Set(204, 205, 304)(resp.status.code) then IO.pure(Array.emptyByteArray)
    else
      val encs = resp.headers.headers.filter(_.name == CIString("Content-Encoding")).flatMap(_.value.split(",").toList.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty))
      val stream = if encs.forall(Set("gzip", "x-gzip", "deflate", "identity")) then
        encs.reverse.foldLeft(resp.body) {
          case (s, "gzip" | "x-gzip") => s.through(Compression[IO].gunzip()).flatMap(_.content)
          case (s, "deflate") => s.through(Compression[IO].inflate(InflateParams()))
          case (s, _) => s
        }
      else resp.body
      val limit = if failOnTruncate then cap.toLong + 1L else cap.toLong
      stream.take(limit).compile.to(Array).flatMap { raw =>
        if failOnTruncate && raw.length > cap then IO.raiseError(GitHubFailure("github_limit", s"GitHub response exceeds ${cap / 1024} KiB limit"))
        else IO.pure(raw)
      }

  private def decodeError(status: Int, headers: Map[String, String], bytes: Array[Byte]): GitHubFailure =
    val text = new String(bytes, UTF_8)
    val parsed = parse(text).toOption
    val msg = parsed.flatMap(_.hcursor.get[String]("message").toOption).filter(_.nonEmpty).getOrElse(text.take(300).trim)
    val errs = parsed.flatMap(_.hcursor.downField("errors").focus.flatMap(_.asArray)).getOrElse(Vector.empty).flatMap { e =>
      val c = e.hcursor; c.get[String]("message").toOption.orElse(for f <- c.get[String]("field").toOption; cd <- c.get[String]("code").toOption yield s"$f ($cd)")
    }
    val detail = if errs.nonEmpty then s"$msg: ${errs.mkString("; ")}" else if msg.nonEmpty then msg else s"HTTP $status"
    status match
      case 401 => GitHubFailure("github_unauthenticated", s"GitHub authentication failed ($detail); run 'groot-cli github login'")
      case 403 | 429 if headers.get("x-ratelimit-remaining").contains("0") || headers.contains("retry-after") || detail.toLowerCase(java.util.Locale.ROOT).contains("rate limit") =>
        val reset = headers.get("x-ratelimit-reset").flatMap(_.toLongOption).fold("")(s => s" (resets at ${java.time.Instant.ofEpochSecond(s)})")
        GitHubFailure("github_rate_limited", s"GitHub rate limit exceeded: $detail$reset")
      case 403 | 429 => GitHubFailure("github_forbidden", s"GitHub access denied: $detail")
      case 404 => GitHubFailure("github_not_found", detail)
      case 409 => GitHubFailure("github_conflict", detail)
      case 422 => GitHubFailure("github_validation", detail)
      case s if s >= 400 && s < 500 => GitHubFailure("invalid_github", s"HTTP $s: $detail")
      case s => GitHubFailure("github_upstream", s"GitHub HTTP $s: $detail")
