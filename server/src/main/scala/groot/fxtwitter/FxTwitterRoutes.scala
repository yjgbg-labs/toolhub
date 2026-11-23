package groot.fxtwitter

import framework.http.ErrorBody
import framework.model.JsonModel
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import io.circe.Json

object FxTwitterRoutes:
  private def enumCodec[A: JsonModel]: ParamCodec[A] = ParamCodec.scalar { value =>
    JsonModel[A].decoder.decodeJson(Json.fromString(value)).left.map(_.message)
  }
  private given ParamCodec[TimelineTab] = enumCodec[TimelineTab]
  private given ParamCodec[RankingMode] = enumCodec[RankingMode]

  def register(service: FxTwitterService)(using CommandsScope): Unit = commands("fxTwitter") {
    description("Read public X/Twitter posts, threads, conversations, user profiles, timelines and trends anonymously via FxTwitter API v2")
    exposeOn(cli, codemode, graaljs)

    val invalid = response.json[ErrorBody](400) { description("Invalid X handle, status ID or query parameter") }
    val forbidden = response.json[ErrorBody](403) { description("Protected, suspended or private X content") }
    val missing = response.json[ErrorBody](404) { description("Tweet or X user not found") }
    val large = response.json[ErrorBody](413) { description("Upstream FxTwitter payload exceeds size limit") }
    val rateLimited = response.json[ErrorBody](429) { description("FxTwitter rate limit exceeded (1000 req/min)") }
    val upstream = response.json[ErrorBody](502) { description("FxTwitter upstream request failed") }
    val unavailable = response.json[ErrorBody](503) { description("Upstream X/FxTwitter feature currently unavailable") }
    val timedOut = response.json[ErrorBody](504) { description("FxTwitter request timed out") }

    recover { case e: FxTwitterFailure =>
      val b = ErrorBody(e.code, e.getMessage)
      e.code match
        case "invalid_fx_twitter" => invalid(b)
        case "fx_twitter_forbidden" => forbidden(b)
        case "fx_twitter_not_found" => missing(b)
        case "fx_twitter_limit" => large(b)
        case "fx_twitter_rate_limited" => rateLimited(b)
        case "fx_twitter_unavailable" => unavailable(b)
        case "fx_twitter_timeout" => timedOut(b)
        case _ => upstream(b)
    }

    command[FxTweet]("status") {
      description("Read a single tweet by status ID or X/Twitter URL, including quote, media, poll, article and optional translation")
      val id = param[String]("id", "Numeric status ID or full tweet URL") { minLength(2) }
      val lang = param[Option[String]]("lang", "Optional target language code for translation (e.g. zh, en, ja)")
      val aboutAccount = param[Boolean]("aboutAccount", "Include author account origin metadata when available") { default(false) }
      handle { service.status(id.value, lang.value, aboutAccount.value) }
    }

    command[FxThreadResult]("thread") {
      description("Unroll the author's self-thread around a tweet ID or URL")
      val id = param[String]("id", "Numeric status ID or full tweet URL") { minLength(2) }
      val lang = param[Option[String]]("lang", "Optional target language code for translation")
      val aboutAccount = param[Boolean]("aboutAccount", "Include author account origin metadata") { default(false) }
      handle { service.thread(id.value, lang.value, aboutAccount.value) }
    }

    command[FxConversationResult]("conversation") {
      description("Read an unrolled thread together with replies and pagination cursor")
      val id = param[String]("id", "Numeric status ID or full tweet URL") { minLength(2) }
      val rankingMode = param[RankingMode]("rankingMode", "Reply sort order: likes or recency") { default(RankingMode.likes) }
      val cursor = param[Option[String]]("cursor", "Pagination cursor (cursor.bottom from a previous call)")
      val limit = param[Int]("limit", "Maximum replies to return") { default(30); minimum(1); maximum(100) }
      val lang = param[Option[String]]("lang", "Optional target language code for translation")
      val aboutAccount = param[Boolean]("aboutAccount", "Include author account origin metadata") { default(false) }
      handle { service.conversation(id.value, rankingMode.value, cursor.value, limit.value, lang.value, aboutAccount.value) }
    }

    command[FxUsersPage]("reposts") {
      description("List accounts that reposted a tweet")
      val id = param[String]("id", "Numeric status ID or full tweet URL") { minLength(2) }
      val count = param[Int]("count", "Number of accounts per page") { default(20); minimum(1); maximum(100) }
      val cursor = param[Option[String]]("cursor", "Pagination cursor")
      handle { service.reposts(id.value, count.value, cursor.value) }
    }

    command[FxUser]("user") {
      description("Read an X user's profile and counters by @handle or profile URL")
      val userHandle = param[String]("handle", "X handle (with or without @) or profile URL") { minLength(1) }
      val aboutAccount = param[Boolean]("aboutAccount", "Include account creation region and username change count") { default(false) }
      handle { service.user(userHandle.value, aboutAccount.value) }
    }

    command[FxAboutAccount]("about") {
      description("Read an X account's region, creation source and username change history")
      val userHandle = param[String]("handle", "X handle (with or without @) or profile URL") { minLength(1) }
      handle { service.about(userHandle.value) }
    }

    command[FxTimelinePage]("timeline") {
      description("List a user's recent statuses, media posts or long-form articles with cursor pagination and optional Unix timestamp floor")
      val userHandle = param[String]("handle", "X handle (with or without @) or profile URL") { minLength(1) }
      val tab = param[TimelineTab]("tab", "Timeline feed: statuses, media or articles") { default(TimelineTab.statuses) }
      val count = param[Int]("count", "Items to request per page") { default(20); minimum(1); maximum(100) }
      val cursor = param[Option[String]]("cursor", "Pagination cursor (cursor.bottom from a previous call)")
      val withReplies = param[Boolean]("withReplies", "Include replies on the statuses tab") { default(false) }
      val since = param[Option[Long]]("since", "Only return statuses newer than this Unix epoch second timestamp")
      val lang = param[Option[String]]("lang", "Optional target language code for translation")
      handle { service.timeline(userHandle.value, tab.value, count.value, cursor.value, withReplies.value, since.value, lang.value) }
    }

    command[FxUsersPage]("followers") {
      description("List followers of an X user")
      val userHandle = param[String]("handle", "X handle (with or without @) or profile URL") { minLength(1) }
      val count = param[Int]("count", "Accounts per page") { default(20); minimum(1); maximum(100) }
      val cursor = param[Option[String]]("cursor", "Pagination cursor")
      handle { service.followers(userHandle.value, count.value, cursor.value) }
    }

    command[FxUsersPage]("following") {
      description("List accounts followed by an X user")
      val userHandle = param[String]("handle", "X handle (with or without @) or profile URL") { minLength(1) }
      val count = param[Int]("count", "Accounts per page") { default(20); minimum(1); maximum(100) }
      val cursor = param[Option[String]]("cursor", "Pagination cursor")
      handle { service.following(userHandle.value, count.value, cursor.value) }
    }

    command[FxTrendsResult]("trends") {
      description("Read current trending X topics")
      val count = param[Int]("count", "Number of trending topics to return") { default(20); minimum(1); maximum(50) }
      handle { service.trends(count.value) }
    }

    command[Json]("api") {
      description("Send a raw GET request to any FxTwitter path (e.g. /2/openapi.json or newly added /2/* endpoints)")
      val input = body[FxApiRequest]("Relative FxTwitter path and optional query parameters")
      handle { service.api(input.value) }
    }
  }
