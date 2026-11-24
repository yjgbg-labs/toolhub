package groot.fxtwitter

import cats.effect.{IO, Resource}
import fs2.compression.{Compression, InflateParams}
import io.circe.{ACursor, HCursor, Json}
import io.circe.parser.parse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import org.http4s.{Header, Headers, Method, Query, Request, Response, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

final class FxTwitterService(
    http: Client[IO],
    val baseUri: Uri = FxTwitterService.defaultBaseUri,
    requestTimeout: FiniteDuration = 25.seconds,
    config: IO[FxTwitterConfig] = IO.pure(FxTwitterConfig())
):
  import FxTwitterService.*

  def status(rawId: String, lang: Option[String], aboutAccount: Boolean): IO[FxTweet] =
    for
      id <- IO(parseTweetId(rawId))
      q = optParam("lang", lang) ++ (if aboutAccount then Map("about_account" -> "1") else Map.empty)
      json <- getJson(s"/2/status/$id", q)
      st <- IO.fromOption(json.hcursor.downField("status").focus.filterNot(_.isNull))(
        FxTwitterFailure("fx_twitter_not_found", s"Tweet $id not found"))
    yield parseTweetOrTombstone(st.hcursor)

  def thread(rawId: String, lang: Option[String], aboutAccount: Boolean): IO[FxThreadResult] =
    for
      id <- IO(parseTweetId(rawId))
      q = optParam("lang", lang) ++ (if aboutAccount then Map("about_account" -> "1") else Map.empty)
      json <- getJson(s"/2/thread/$id", q)
      c = json.hcursor
      st <- IO.fromOption(c.downField("status").focus.filterNot(_.isNull))(
        FxTwitterFailure("fx_twitter_not_found", s"Thread $id not found"))
      author = c.downField("author").focus.filterNot(_.isNull).map(a => parseUser(a.hcursor))
      items = arr(c.downField("thread").focus.getOrElse(Json.arr())).map(t => parseTweetOrTombstone(t.hcursor))
    yield FxThreadResult(parseTweetOrTombstone(st.hcursor), author, items)

  def conversation(rawId: String, rankingMode: RankingMode, cursor: Option[String], limit: Int,
      lang: Option[String], aboutAccount: Boolean): IO[FxConversationResult] =
    for
      id <- IO(parseTweetId(rawId))
      q = Map("ranking_mode" -> rankingMode.toString) ++ optParam("cursor", cursor) ++ optParam("lang", lang) ++
        (if aboutAccount then Map("about_account" -> "1") else Map.empty)
      json <- getJson(s"/2/conversation/$id", q)
      c = json.hcursor
      st <- IO.fromOption(c.downField("status").focus.filterNot(_.isNull))(
        FxTwitterFailure("fx_twitter_not_found", s"Conversation $id not found"))
      threadItems = arr(c.downField("thread").focus.getOrElse(Json.arr())).map(t => parseTweetOrTombstone(t.hcursor))
      replies = arr(c.downField("replies").focus.getOrElse(Json.arr())).take(clamp(limit, 1, 100)).map(r => parseTweetOrTombstone(r.hcursor))
    yield FxConversationResult(parseTweetOrTombstone(st.hcursor), threadItems, replies, parseCursor(c.downField("cursor")))

  def reposts(rawId: String, count: Int, cursor: Option[String]): IO[FxUsersPage] =
    for
      id <- IO(parseTweetId(rawId))
      q = Map("count" -> clamp(count, 1, 100).toString) ++ optParam("cursor", cursor)
      json <- getJson(s"/2/status/$id/reposts", q)
      c = json.hcursor
    yield FxUsersPage(arr(c.downField("results").focus.getOrElse(Json.arr())).map(u => parseUser(u.hcursor)), parseCursor(c.downField("cursor")))

  def user(rawHandle: String, aboutAccount: Boolean): IO[FxUser] =
    for
      h <- IO(parseHandle(rawHandle))
      q = if aboutAccount then Map("about_account" -> "1") else Map.empty[String, String]
      json <- getJson(s"/2/profile/${enc(h)}", q)
      u <- IO.fromOption(json.hcursor.downField("user").focus.filterNot(_.isNull))(
        FxTwitterFailure("fx_twitter_not_found", s"User @$h not found"))
    yield parseUser(u.hcursor)

  def about(rawHandle: String): IO[FxAboutAccount] =
    for
      h <- IO(parseHandle(rawHandle))
      json <- getJson(s"/2/profile/${enc(h)}/about", Map.empty)
      ab <- IO.fromOption(json.hcursor.downField("about_account").focus.filterNot(_.isNull))(
        FxTwitterFailure("fx_twitter_not_found", s"About account info unavailable for @$h"))
    yield parseAbout(ab.hcursor)

  def timeline(rawHandle: String, tab: TimelineTab, count: Int, cursor: Option[String],
      withReplies: Boolean, since: Option[Long], lang: Option[String]): IO[FxTimelinePage] =
    for
      h <- IO(parseHandle(rawHandle))
      lim = clamp(count, 1, 100)
      q = Map("count" -> lim.toString) ++ optParam("cursor", cursor) ++ optParam("lang", lang) ++
        (if tab == TimelineTab.statuses && withReplies then Map("with_replies" -> "1") else Map.empty) ++
        (if tab == TimelineTab.statuses then since.filter(_ >= 0L).map(s => "since" -> s.toString) else None)
      json <- getJson(s"/2/profile/${enc(h)}/${tab.toString}", q)
      c = json.hcursor
      items = arr(c.downField("results").focus.getOrElse(Json.arr())).flatMap { entry =>
        val ec = entry.hcursor
        if str(ec, "type") == "thread" then
          arr(ec.downField("statuses").focus.getOrElse(Json.arr())).map(s => parseTweetOrTombstone(s.hcursor))
        else Vector(parseTweetOrTombstone(ec))
      }
    yield FxTimelinePage(h, tab.toString, items, parseCursor(c.downField("cursor")))

  def followers(rawHandle: String, count: Int, cursor: Option[String]): IO[FxUsersPage] =
    relationPage(rawHandle, "followers", count, cursor)

  def following(rawHandle: String, count: Int, cursor: Option[String]): IO[FxUsersPage] =
    relationPage(rawHandle, "following", count, cursor)

  private def relationPage(rawHandle: String, kind: String, count: Int, cursor: Option[String]): IO[FxUsersPage] =
    for
      h <- IO(parseHandle(rawHandle))
      q = Map("count" -> clamp(count, 1, 100).toString) ++ optParam("cursor", cursor)
      json <- getJson(s"/2/profile/${enc(h)}/$kind", q)
      c = json.hcursor
    yield FxUsersPage(arr(c.downField("results").focus.getOrElse(Json.arr())).map(u => parseUser(u.hcursor)), parseCursor(c.downField("cursor")))

  def trends(count: Int): IO[FxTrendsResult] =
    val q = Map("type" -> "trending", "count" -> clamp(count, 1, 50).toString)
    getJson("/2/trends", q).map { json =>
      val c = json.hcursor
      val items = arr(c.downField("trends").focus.getOrElse(Json.arr())).map { t =>
        val tc = t.hcursor
        FxTrend(str(tc, "name"), tc.get[Option[Int]]("rank").toOption.flatten, opt(tc, "context"))
      }
      FxTrendsResult(str(c, "timeline_type", "trending"), items, parseCursor(c.downField("cursor")))
    }

  def api(req: FxApiRequest): IO[Json] =
    val p = req.path.trim
    if p.isEmpty || p.startsWith("http://") || p.startsWith("https://") then
      IO.raiseError(FxTwitterFailure("invalid_fx_twitter", "path must be a relative FxTwitter path such as /2/status/20"))
    else getJson(if p.startsWith("/") then p else s"/$p", req.query)

  private def getJson(path: String, query: Map[String, String]): IO[Json] = config.flatMap: cfg =>
    val activeBase = if baseUri != defaultBaseUri then baseUri else Uri.fromString(cfg.baseUrl.trim).getOrElse(baseUri)
    val activeTimeout = if requestTimeout != 25.seconds then requestTimeout else framework.validation.Contracts.parseDuration(cfg.timeout)
    IO.defer {
      val cleanPath = if path.startsWith("/") then path else s"/$path"
      val base = Uri.fromString(activeBase.renderString.stripSuffix("/") + cleanPath)
        .fold(e => throw FxTwitterFailure("invalid_fx_twitter", e.message), identity)
      val uri = if query.isEmpty then base else base.copy(query = Query.fromVector(query.toVector.map((k, v) => k -> Some(v))))
      val req = Request[IO](
        method = Method.GET,
        uri = uri,
        headers = Headers(
          Header.Raw(CIString("Accept"), "application/json"),
          Header.Raw(CIString("User-Agent"), userAgent),
          Header.Raw(CIString("Accept-Encoding"), "gzip, deflate")
        )
      )
      http.run(req).use { resp =>
        readDecoded(resp, cfg.maxResponseBytes).flatMap { bytes =>
          val text = new String(bytes, UTF_8)
          val parsed = parse(text).toOption
          val bodyCode = parsed.flatMap(_.hcursor.get[Int]("code").toOption).getOrElse(resp.status.code)
          val effectiveStatus = if resp.status.code >= 400 then resp.status.code else bodyCode
          if effectiveStatus >= 200 && effectiveStatus < 300 && parsed.isDefined then IO.pure(parsed.get)
          else
            val msg = parsed.flatMap(j => opt(j.hcursor, "message").orElse(opt(j.hcursor, "reason")))
              .getOrElse(if text.trim.nonEmpty then text.trim.take(300) else s"HTTP ${resp.status.code}")
            IO.raiseError(classifyError(effectiveStatus, msg))
        }
      }
    }.timeoutTo(activeTimeout, IO.raiseError(FxTwitterFailure("fx_twitter_timeout", s"FxTwitter timed out after $activeTimeout")))
      .adaptError { case e if !e.isInstanceOf[FxTwitterFailure] =>
        FxTwitterFailure("fx_twitter_upstream", s"FxTwitter request failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
      }

object FxTwitterService:
  val defaultBaseUri: Uri = Uri.unsafeFromString("https://api.fxtwitter.com")
  val userAgent = "groot-http/1.0 (+https://github.com/yjgbg-labs/toolhub)"
  val maxResponseBytes: Int = 4 * 1024 * 1024

  private val idDirectRe = "^(\\d{2,20})$".r
  private val statusUrlRe = "(?:status|statuses)/(\\d{2,20})".r
  private val handleRe = "^@?([A-Za-z0-9_]{1,15})$".r
  private val profileUrlRe = "^https?://(?:www\\.)?(?:x|twitter|fxtwitter|vxtwitter|fixupx)\\.com/@?([A-Za-z0-9_]{1,15})(?:[/?#].*)?$".r

  def resource(baseUri: Uri = defaultBaseUri, config: IO[FxTwitterConfig] = IO.pure(FxTwitterConfig())): Resource[IO, FxTwitterService] =
    EmberClientBuilder.default[IO].withTimeout(Duration.Inf).withMaxResponseHeaderSize(64 * 1024).build.map(new FxTwitterService(_, baseUri, 25.seconds, config))

  def parseTweetId(raw: String): String =
    val t = raw.trim
    t match
      case idDirectRe(id) => id
      case _ => statusUrlRe.findFirstMatchIn(t).map(_.group(1)).getOrElse(
        throw FxTwitterFailure("invalid_fx_twitter", s"Invalid tweet ID or URL '$raw': expected 2-20 digit status ID or tweet URL"))

  def parseHandle(raw: String): String =
    val t = raw.trim
    t match
      case handleRe(h) => h
      case profileUrlRe(h) => h
      case _ => throw FxTwitterFailure("invalid_fx_twitter", s"Invalid X handle '$raw': expected @handle (1-15 letters, digits or underscores)")

  private def classifyError(status: Int, msg: String): FxTwitterFailure = status match
    case 400 => FxTwitterFailure("invalid_fx_twitter", msg)
    case 401 | 403 => FxTwitterFailure("fx_twitter_forbidden", msg)
    case 404 => FxTwitterFailure("fx_twitter_not_found", msg)
    case 429 => FxTwitterFailure("fx_twitter_rate_limited", s"FxTwitter rate limit exceeded (1000 req/min): $msg")
    case 503 => FxTwitterFailure("fx_twitter_unavailable", msg)
    case s => FxTwitterFailure("fx_twitter_upstream", s"FxTwitter HTTP $s: $msg")

  private def parseCursor(c: ACursor): FxCursor = FxCursor(opt(c, "top"), opt(c, "bottom"))

  private def parseAbout(c: ACursor): FxAboutAccount =
    val uc = c.downField("username_changes")
    FxAboutAccount(opt(c, "based_in"), c.get[Boolean]("created_country_accurate").toOption,
      opt(c, "source"), int(uc, "count"), opt(uc, "last_changed_at"))

  private def parseUser(c: HCursor): FxUser =
    val ver = c.downField("verification")
    val aboutOpt = c.downField("about_account").focus.filterNot(_.isNull).map(a => parseAbout(a.hcursor))
    FxUser(
      id = str(c, "id"),
      screenName = str(c, "screen_name"),
      name = str(c, "name"),
      description = str(c, "description"),
      location = opt(c, "location"),
      website = opt(c.downField("website"), "url"),
      verified = bool(ver, "verified"),
      verificationType = opt(ver, "type"),
      isProtected = bool(c, "protected"),
      followers = int(c, "followers"),
      following = int(c, "following"),
      statuses = int(c, "statuses"),
      mediaCount = int(c, "media_count"),
      likes = int(c, "likes"),
      joined = opt(c, "joined"),
      avatarUrl = opt(c, "avatar_url"),
      bannerUrl = opt(c, "banner_url"),
      url = str(c, "url"),
      aboutAccount = aboutOpt
    )

  private def parseAuthorBrief(c: ACursor): Option[FxAuthorBrief] =
    opt(c, "screen_name").map { sn =>
      val ver = c.downField("verification")
      FxAuthorBrief(str(c, "id"), sn, str(c, "name"), bool(ver, "verified"), opt(ver, "type"))
    }

  private def parseTweetOrTombstone(c: HCursor): FxTweet =
    if str(c, "type") == "tombstone" then
      val reason = str(c, "reason", "unavailable")
      val msg = opt(c, "message").getOrElse(s"Post unavailable ($reason)")
      FxTweet(id = str(c, "id"), url = str(c, "url"), text = msg, author = parseAuthorBrief(c.downField("author")), tombstoneReason = Some(reason))
    else
      val articleOpt = c.downField("article").focus.filterNot(_.isNull).map { aj =>
        val ac = aj.hcursor
        val blocks = arr(ac.downField("content").downField("blocks").focus.getOrElse(Json.arr()))
          .map(b => str(b.hcursor, "text")).mkString("\n").trim
        FxArticle(str(ac, "id"), str(ac, "title"), str(ac, "preview_text"), Option.when(blocks.nonEmpty)(blocks), opt(ac, "created_at"))
      }
      val rawText = str(c, "text")
      val fullText = if rawText.nonEmpty then rawText else articleOpt.map(a => s"${a.title}\n\n${a.content.getOrElse(a.previewText)}").getOrElse("")
      val quoteOpt = c.downField("quote").focus.filterNot(_.isNull).map { qj =>
        val qc = qj.hcursor
        if str(qc, "type") == "tombstone" then
          val r = str(qc, "reason", "unavailable")
          FxQuotedTweet(opt(qc, "id"), opt(qc, "url"), opt(qc.downField("author"), "screen_name"),
            opt(qc.downField("author"), "name"), opt(qc, "message").getOrElse(s"[$r]"), tombstoneReason = Some(r))
        else
          FxQuotedTweet(opt(qc, "id"), opt(qc, "url"), opt(qc.downField("author"), "screen_name"),
            opt(qc.downField("author"), "name"), str(qc, "text"), opt(qc, "created_at"))
      }
      val pollOpt = c.downField("poll").focus.filterNot(_.isNull).map { pj =>
        val pc = pj.hcursor
        val choices = arr(pc.downField("choices").focus.getOrElse(Json.arr())).map { ch =>
          val cc = ch.hcursor; FxPollChoice(str(cc, "label"), int(cc, "count"), cc.get[Double]("percentage").getOrElse(0.0))
        }
        FxPoll(int(pc, "total_votes"), opt(pc, "ends_at"), opt(pc, "time_left_en"), choices)
      }
      val mc = c.downField("media")
      val photos = arr(mc.downField("photos").focus.getOrElse(Json.arr())).map { p =>
        val pc = p.hcursor
        FxMediaItem(str(pc, "type", "photo"), str(pc, "url"), None, pc.get[Int]("width").toOption, pc.get[Int]("height").toOption, None, opt(pc, "altText"))
      }
      val videos = arr(mc.downField("videos").focus.getOrElse(Json.arr())).map { v =>
        val vc = v.hcursor
        FxMediaItem(str(vc, "type", "video"), str(vc, "url"), opt(vc, "thumbnail_url"),
          vc.get[Int]("width").toOption, vc.get[Int]("height").toOption, vc.get[Double]("duration").toOption, None)
      }
      val external = mc.downField("external").focus.filterNot(_.isNull).toVector.map { e =>
        val ec = e.hcursor
        FxMediaItem("external_video", str(ec, "url"), opt(ec, "thumbnail_url"), ec.get[Int]("width").toOption, ec.get[Int]("height").toOption)
      }
      val rep = c.downField("replying_to")
      val translationText = c.downField("translation").focus.filterNot(_.isNull).flatMap(tj => opt(tj.hcursor, "text"))

      FxTweet(
        id = str(c, "id"),
        url = str(c, "url"),
        text = fullText,
        author = parseAuthorBrief(c.downField("author")),
        createdAt = opt(c, "created_at"),
        createdTimestamp = c.get[Long]("created_timestamp").toOption,
        lang = opt(c, "lang"),
        likes = int(c, "likes"),
        reposts = int(c, "reposts"),
        replies = int(c, "replies"),
        quotes = int(c, "quotes"),
        bookmarks = int(c, "bookmarks"),
        views = c.get[Option[Long]]("views").toOption.flatten,
        replyingTo = opt(rep, "screen_name"),
        replyingToStatusId = opt(rep, "status"),
        repostedBy = opt(c.downField("reposted_by"), "screen_name"),
        quote = quoteOpt,
        media = photos ++ videos ++ external,
        poll = pollOpt,
        article = articleOpt,
        communityNote = opt(c.downField("community_note"), "text"),
        translation = translationText
      )

  private def readDecoded(resp: Response[IO], cap: Int): IO[Array[Byte]] =
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
      stream.take(cap.toLong + 1L).compile.to(Array).flatMap { raw =>
        if raw.length > cap then IO.raiseError(FxTwitterFailure("fx_twitter_limit", s"FxTwitter response exceeds ${cap / 1024} KiB limit"))
        else IO.pure(raw)
      }

  private def enc(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")
  private def clamp(v: Int, min: Int, max: Int): Int = math.max(min, math.min(max, v))
  private def optParam(k: String, v: Option[String]): Map[String, String] = v.map(_.trim).filter(_.nonEmpty).map(k -> _).toMap
  private def str(c: ACursor, k: String, fallback: String = ""): String = c.get[Option[String]](k).toOption.flatten.getOrElse(fallback)
  private def opt(c: ACursor, k: String): Option[String] = c.get[Option[String]](k).toOption.flatten.filter(_.nonEmpty)
  private def int(c: ACursor, k: String): Int = c.get[Int](k).orElse(c.get[Double](k).map(_.toInt)).getOrElse(0)
  private def bool(c: ACursor, k: String): Boolean = c.get[Boolean](k).getOrElse(false)
  private def arr(j: Json): Vector[Json] = j.asArray.getOrElse(Vector.empty)
