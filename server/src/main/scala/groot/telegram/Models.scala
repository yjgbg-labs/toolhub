package groot.telegram

import framework.model.JsonModel
import framework.validation.*
import io.circe.{Codec, Json}

final case class TelegramConfig(
    @httpUrl apiBase: String =
      sys.env.get("TELEGRAM_API_BASE").map(_.trim).filter(_.nonEmpty).getOrElse("https://api.telegram.org"),
    @contract[String](
      s => s != null && (s.trim.isEmpty || Contracts.isHttpUrl(s)),
      "must be empty or an absolute http(s) proxy URL"
    )
    proxyUrl: String = sys.env.getOrElse("TELEGRAM_PROXY_URL", "").trim,
    @duration timeout: String = "30s",
    @range(1024L, 67108864L) maxResponseBytes: Int = 4 * 1024 * 1024,
    @range(1024L, 52428800L) maxUploadBytes: Int = 50 * 1024 * 1024,
    @range(0L, 120000L) maxRateLimitWaitMs: Int = 35000
) derives JsonModel, Validator

final case class TelegramFailure(code: String, message: String) extends RuntimeException(message)

enum ParseMode derives JsonModel:
  case plain, MarkdownV2, Markdown, HTML

final case class InlineButton(
    text: String,
    url: Option[String] = None,
    callbackData: Option[String] = None
) derives JsonModel

final case class KnownChat(
    chatId: String,
    kind: String,
    title: Option[String] = None,
    username: Option[String] = None,
    firstName: Option[String] = None,
    lastName: Option[String] = None,
    lastMessagePreview: Option[String] = None,
    lastSeenAt: String
) derives Codec.AsObject, JsonModel

final case class StoredTelegramState(
    botToken: Option[String] = None,
    botId: Option[Long] = None,
    botUsername: Option[String] = None,
    defaultChatId: Option[String] = None,
    lastUpdateId: Option[Long] = None,
    chats: Vector[KnownChat] = Vector.empty,
    updatedAt: Option[String] = None
) derives Codec.AsObject, JsonModel

final case class BotProfile(
    id: Long,
    username: String,
    firstName: String,
    canJoinGroups: Boolean = false,
    canReadAllGroupMessages: Boolean = false,
    supportsInlineQueries: Boolean = false,
    apiBase: String,
    defaultChatId: Option[String] = None,
    knownChatsCount: Int = 0,
    lastUpdateId: Option[Long] = None
) derives JsonModel

final case class LogoutResult(
    loggedOut: Boolean,
    username: Option[String] = None
) derives JsonModel

final case class ChatInfo(
    chatId: String,
    kind: String,
    title: Option[String] = None,
    username: Option[String] = None,
    firstName: Option[String] = None,
    lastName: Option[String] = None,
    bio: Option[String] = None,
    description: Option[String] = None
) derives JsonModel

final case class DefaultChatResult(
    defaultChatId: String,
    chat: Option[ChatInfo] = None
) derives JsonModel

final case class SendRequest(
    text: String,
    chatId: Option[String] = None,
    parseMode: ParseMode = ParseMode.plain,
    replyToMessageId: Option[Long] = None,
    messageThreadId: Option[Long] = None,
    disableNotification: Boolean = false,
    disableWebPagePreview: Boolean = false,
    protectContent: Boolean = false,
    buttons: Vector[Vector[InlineButton]] = Vector.empty
) derives JsonModel

final case class EditRequest(
    messageId: Long,
    text: String,
    chatId: Option[String] = None,
    parseMode: ParseMode = ParseMode.plain,
    disableWebPagePreview: Boolean = false,
    buttons: Vector[Vector[InlineButton]] = Vector.empty
) derives JsonModel

final case class MediaSendRequest(
    source: String,
    chatId: Option[String] = None,
    caption: Option[String] = None,
    parseMode: ParseMode = ParseMode.plain,
    filename: Option[String] = None,
    replyToMessageId: Option[Long] = None,
    messageThreadId: Option[Long] = None,
    disableNotification: Boolean = false
) derives JsonModel

final case class TelegramUserSummary(
    id: Long,
    isBot: Boolean,
    username: Option[String] = None,
    firstName: String,
    lastName: Option[String] = None
) derives JsonModel

final case class TelegramAttachment(
    kind: String,
    fileId: String,
    fileUniqueId: Option[String] = None,
    fileName: Option[String] = None,
    mimeType: Option[String] = None,
    fileSize: Option[Long] = None
) derives JsonModel

final case class TelegramMessage(
    messageId: Long,
    chatId: String,
    chatKind: String,
    chatTitle: Option[String] = None,
    chatUsername: Option[String] = None,
    messageThreadId: Option[Long] = None,
    from: Option[TelegramUserSummary] = None,
    date: Long,
    editDate: Option[Long] = None,
    replyToMessageId: Option[Long] = None,
    text: Option[String] = None,
    caption: Option[String] = None,
    attachments: Vector[TelegramAttachment] = Vector.empty
) derives JsonModel

final case class TelegramCallback(
    id: String,
    from: TelegramUserSummary,
    chatId: Option[String] = None,
    messageId: Option[Long] = None,
    data: Option[String] = None
) derives JsonModel

final case class TelegramUpdateItem(
    updateId: Long,
    kind: String,
    message: Option[TelegramMessage] = None,
    callbackQuery: Option[TelegramCallback] = None
) derives JsonModel

final case class UpdatesResult(
    updates: Vector[TelegramUpdateItem],
    nextOffset: Option[Long] = None,
    knownChats: Vector[KnownChat] = Vector.empty
) derives JsonModel

final case class TelegramActionAck(
    ok: Boolean,
    chatId: Option[String] = None,
    messageId: Option[Long] = None,
    message: String
) derives JsonModel

final case class FileDownloadResult(
    fileId: String,
    telegramFilePath: String,
    savedPath: Option[String] = None,
    sizeBytes: Long,
    contentBase64: Option[String] = None
) derives JsonModel

final case class WebhookStatus(
    url: String,
    hasCustomCertificate: Boolean,
    pendingUpdateCount: Int,
    lastErrorDate: Option[Long] = None,
    lastErrorMessage: Option[String] = None,
    maxConnections: Option[Int] = None,
    allowedUpdates: Vector[String] = Vector.empty
) derives JsonModel

final case class RawApiRequest(
    method: String,
    payload: Json = Json.obj()
) derives JsonModel
