package groot.fxtwitter

import framework.model.JsonModel
import framework.validation.{Contracts, Validator, contract}
import io.circe.Json

final case class FxTwitterConfig(
    @contract[String](Contracts.isHttpUrl, "must be an absolute http(s) URL") baseUrl: String = "https://api.fxtwitter.com",
    @contract[String](Contracts.isDuration) timeout: String = "25s",
    @contract[Int](_ > 0) maxResponseBytes: Int = FxTwitterService.maxResponseBytes
) derives JsonModel, Validator

final case class FxTwitterFailure(code: String, message: String) extends RuntimeException(message)

final case class FxCursor(top: Option[String] = None, bottom: Option[String] = None) derives JsonModel
final case class FxAboutAccount(basedIn: Option[String] = None, createdCountryAccurate: Option[Boolean] = None,
    source: Option[String] = None, usernameChanges: Int = 0, lastUsernameChangeAt: Option[String] = None) derives JsonModel

final case class FxUser(id: String, screenName: String, name: String, description: String = "",
    location: Option[String] = None, website: Option[String] = None, verified: Boolean = false,
    verificationType: Option[String] = None, isProtected: Boolean = false, followers: Int = 0,
    following: Int = 0, statuses: Int = 0, mediaCount: Int = 0, likes: Int = 0, joined: Option[String] = None,
    avatarUrl: Option[String] = None, bannerUrl: Option[String] = None, url: String,
    aboutAccount: Option[FxAboutAccount] = None) derives JsonModel

final case class FxAuthorBrief(id: String, screenName: String, name: String, verified: Boolean = false,
    verificationType: Option[String] = None) derives JsonModel
final case class FxMediaItem(kind: String, url: String, thumbnailUrl: Option[String] = None,
    width: Option[Int] = None, height: Option[Int] = None, durationSec: Option[Double] = None,
    altText: Option[String] = None) derives JsonModel
final case class FxPollChoice(label: String, count: Int, percentage: Double) derives JsonModel
final case class FxPoll(totalVotes: Int, endsAt: Option[String] = None, timeLeft: Option[String] = None,
    choices: Vector[FxPollChoice] = Vector.empty) derives JsonModel
final case class FxArticle(id: String, title: String, previewText: String, content: Option[String] = None,
    createdAt: Option[String] = None) derives JsonModel
final case class FxQuotedTweet(id: Option[String] = None, url: Option[String] = None,
    authorScreenName: Option[String] = None, authorName: Option[String] = None, text: String = "",
    createdAt: Option[String] = None, tombstoneReason: Option[String] = None) derives JsonModel

final case class FxTweet(id: String, url: String, text: String, author: Option[FxAuthorBrief] = None,
    createdAt: Option[String] = None, createdTimestamp: Option[Long] = None, lang: Option[String] = None,
    likes: Int = 0, reposts: Int = 0, replies: Int = 0, quotes: Int = 0, bookmarks: Int = 0,
    views: Option[Long] = None, replyingTo: Option[String] = None, replyingToStatusId: Option[String] = None,
    repostedBy: Option[String] = None, quote: Option[FxQuotedTweet] = None,
    media: Vector[FxMediaItem] = Vector.empty, poll: Option[FxPoll] = None, article: Option[FxArticle] = None,
    communityNote: Option[String] = None, translation: Option[String] = None,
    tombstoneReason: Option[String] = None) derives JsonModel

enum TimelineTab derives JsonModel:
  case statuses, media, articles

enum RankingMode derives JsonModel:
  case likes, recency

final case class FxTimelinePage(handle: String, tab: String, items: Vector[FxTweet], cursor: FxCursor) derives JsonModel
final case class FxThreadResult(status: FxTweet, author: Option[FxUser] = None, thread: Vector[FxTweet] = Vector.empty) derives JsonModel
final case class FxConversationResult(status: FxTweet, thread: Vector[FxTweet] = Vector.empty,
    replies: Vector[FxTweet] = Vector.empty, cursor: FxCursor) derives JsonModel
final case class FxUsersPage(users: Vector[FxUser], cursor: FxCursor) derives JsonModel
final case class FxTrend(name: String, rank: Option[Int] = None, context: Option[String] = None) derives JsonModel
final case class FxTrendsResult(timelineType: String, trends: Vector[FxTrend], cursor: FxCursor) derives JsonModel
final case class FxApiRequest(path: String, query: Map[String, String] = Map.empty) derives JsonModel
