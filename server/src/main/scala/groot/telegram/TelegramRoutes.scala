package groot.telegram

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import io.circe.Json

object TelegramRoutes:
  def register(service: TelegramService)(using CommandsScope): Unit = commands("telegram") {
    description("Send, edit, pin and receive Telegram Bot messages, upload/download media, manage known chats and call Bot API methods")
    exposeOn(cli, codemode, graaljs)

    val invalid = response.json[ErrorBody](400) { description("Invalid Telegram parameter, missing target chatId or bad request") }
    val unauth = response.json[ErrorBody](401) { description("Telegram bot token missing or unauthorized") }
    val forbidden = response.json[ErrorBody](403) { description("Bot blocked by user or lacks chat permission") }
    val missing = response.json[ErrorBody](404) { description("Telegram chat, message, file or method not found") }
    val conflict = response.json[ErrorBody](409) { description("Telegram state conflict (e.g. active webhook blocking getUpdates)") }
    val large = response.json[ErrorBody](413) { description("Upload file or Telegram response exceeds configured byte limit") }
    val rateLimited = response.json[ErrorBody](429) { description("Telegram Bot API rate limit exceeded") }
    val upstream = response.json[ErrorBody](502) { description("Telegram Bot API upstream error") }
    val timedOut = response.json[ErrorBody](504) { description("Telegram Bot API request timed out") }

    recover { case e: TelegramFailure =>
      val b = ErrorBody(e.code, e.getMessage)
      e.code match
        case "invalid_telegram"         => invalid(b)
        case "telegram_unauthenticated" => unauth(b)
        case "telegram_forbidden"       => forbidden(b)
        case "telegram_not_found"       => missing(b)
        case "telegram_conflict"        => conflict(b)
        case "telegram_limit"           => large(b)
        case "telegram_rate_limited"    => rateLimited(b)
        case "telegram_timeout"         => timedOut(b)
        case _                          => upstream(b)
    }

    command[BotProfile]("login") {
      exposeOn(cli)
      description("Verify a Telegram Bot token via getMe and persist it locally ($GROOT_HOME/telegram/state.json)")
      val token = param[String]("token", "Telegram Bot HTTP API token (<bot_id>:<secret>, or '-' for stdin)") { minLength(10) }
      val defaultChatId = param[Option[String]]("defaultChatId", "Optional default numeric chat ID or @channelusername")
      handle { service.login(token.value, defaultChatId.value) }
    }

    command[LogoutResult]("logout") {
      exposeOn(cli)
      description("Clear the persisted Telegram Bot token from $GROOT_HOME/telegram/state.json")
      handle { service.logout }
    }

    command[BotProfile]("me") {
      description("Inspect the active Telegram Bot profile, API base URL, default chat ID and update cursor")
      handle { service.me }
    }

    command[UpdatesResult]("updates") {
      description("Poll incoming Telegram messages and callback queries, automatically recording discovered chats")
      val limit = param[Int]("limit", "Maximum updates to fetch") { default(30); minimum(1); maximum(100) }
      val timeoutSeconds = param[Int]("timeoutSeconds", "Long-polling wait time in seconds (0 returns immediately)") { default(0); minimum(0); maximum(50) }
      val offset = param[Option[Long]]("offset", "Explicit Telegram update_id offset")
      val unreadOnly = param[Boolean]("unreadOnly", "Start after the last acknowledged update_id") { default(false) }
      val ack = param[Boolean]("ack", "Advance stored lastUpdateId cursor to the newest fetched update") { default(false) }
      val chatId = param[Option[String]]("chatId", "Optional filter by chatId")
      handle { service.updates(limit.value, timeoutSeconds.value, offset.value, unreadOnly.value, ack.value, chatId.value) }
    }

    command[TelegramMessage]("send") {
      description("Send a text message (plain, MarkdownV2, Markdown or HTML) with optional reply, thread and inline keyboard buttons")
      val input = body[SendRequest]("Message text, optional chatId (auto-discovered when omitted), parseMode, reply/thread options and inline buttons")
      handle { service.send(input.value) }
    }

    command[TelegramMessage]("edit") {
      description("Edit the text or inline buttons of an existing message sent by the bot")
      val input = body[EditRequest]("Target messageId, updated text, optional chatId, parseMode and inline buttons")
      handle { service.edit(input.value) }
    }

    command[TelegramActionAck]("delete") {
      description("Delete a message by messageId in the target or default chat")
      val messageId = param[Long]("messageId", "Telegram message_id to delete")
      val chatId = param[Option[String]]("chatId", "Target chatId; defaults to configured or discovered default chat")
      handle { service.delete(messageId.value, chatId.value) }
    }

    command[TelegramActionAck]("pin") {
      description("Pin a message in the target or default chat")
      val messageId = param[Long]("messageId", "Telegram message_id to pin")
      val chatId = param[Option[String]]("chatId", "Target chatId; defaults to configured or discovered default chat")
      val disableNotification = param[Boolean]("disableNotification", "Pin silently without notifying members") { default(true) }
      handle { service.pin(messageId.value, chatId.value, disableNotification.value) }
    }

    command[TelegramActionAck]("unpin") {
      description("Unpin a specific message (or the most recent pinned message when messageId is omitted)")
      val messageId = param[Option[Long]]("messageId", "Optional Telegram message_id to unpin")
      val chatId = param[Option[String]]("chatId", "Target chatId; defaults to configured or discovered default chat")
      handle { service.unpin(messageId.value, chatId.value) }
    }

    command[TelegramMessage]("photo") {
      description("Send a photo from a local file path, HTTPS URL or Telegram file_id")
      val input = body[MediaSendRequest]("Local file path / URL / file_id, optional chatId, caption and parseMode")
      handle { service.sendPhoto(input.value) }
    }

    command[TelegramMessage]("document") {
      description("Send a document or arbitrary file from a local file path, HTTPS URL or Telegram file_id")
      val input = body[MediaSendRequest]("Local file path / URL / file_id, optional chatId, caption, custom filename and parseMode")
      handle { service.sendDocument(input.value) }
    }

    command[FileDownloadResult]("download") {
      description("Download an attachment by Telegram file_id to disk or inline Base64 (when <= 256 KiB)")
      val fileId = param[String]("fileId", "Telegram file_id from message attachments") { minLength(2) }
      val outputPath = param[Option[String]]("outputPath", "Optional local destination file path")
      handle { service.download(fileId.value, outputPath.value) }
    }

    commands("chat") {
      description("List discovered chats, inspect chat metadata and pin the default recipient chat")
      command[Vector[KnownChat]]("list") {
        description("List chats discovered from bot updates and outgoing messages")
        val refresh = param[Boolean]("refresh", "Poll recent updates before listing") { default(true) }
        handle { service.chatList(refresh.value) }
      }
      command[ChatInfo]("get") {
        description("Fetch live metadata for a chat via getChat")
        val chatId = param[Option[String]]("chatId", "Target chatId or @username; defaults to active default chat")
        handle { service.chatGet(chatId.value) }
      }
      command[DefaultChatResult]("use") {
        description("Verify and persist the default target chatId in $GROOT_HOME/telegram/state.json")
        val chatId = param[String]("chatId", "Numeric chatId or @channelusername") { minLength(1) }
        handle { service.chatUse(chatId.value) }
      }
    }

    commands("webhook") {
      description("Inspect or delete Telegram Bot webhook configuration")
      command[WebhookStatus]("info") {
        description("Read current webhook URL, pending update count and last delivery error")
        handle { service.webhookInfo }
      }
      command[TelegramActionAck]("delete") {
        description("Remove any active webhook so getUpdates polling can be used")
        val dropPendingUpdates = param[Boolean]("dropPendingUpdates", "Discard queued updates while deleting webhook") { default(false) }
        handle { service.webhookDelete(dropPendingUpdates.value) }
      }
    }

    command[Json]("api") {
      description("Invoke any raw Telegram Bot API method with a JSON object payload and return its result field")
      val input = body[RawApiRequest]("Bot API method name (e.g. sendChatAction, setMyCommands) and JSON payload")
      handle { service.api(input.value) }
    }
  }
