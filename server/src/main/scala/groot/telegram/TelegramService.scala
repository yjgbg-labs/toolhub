package groot.telegram

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.circe.{HCursor, Json}
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

final class TelegramService private (
    stateFile: Path,
    stateRef: Ref[IO, StoredTelegramState],
    mutex: Mutex[IO],
    client: TelegramClient,
    config: IO[TelegramConfig]
):
  import TelegramService.*

  private def requireToken: IO[(TelegramConfig, String)] =
    for
      cfg <- config
      st <- stateRef.get
      raw = st.botToken.map(_.trim).getOrElse("")
      token <- IO(TelegramClient.sanitizeToken(raw))
      _ <- ensureBotIsolation(token)
    yield (cfg, token)

  private def ensureBotIsolation(token: String): IO[Unit] =
    TelegramClient.extractBotId(token) match
      case None => IO.unit
      case Some(currentBotId) =>
        stateRef.get.flatMap { st =>
          if st.botId.forall(_ == currentBotId) then IO.unit
          else
            modifyState { cur =>
              if cur.botId.forall(_ == currentBotId) then cur
              else
                cur.copy(
                  botId = Some(currentBotId),
                  botUsername = None,
                  lastUpdateId = None,
                  chats = Vector.empty
                )
            }.void
        }

  private def effectiveDefaultChat(st: StoredTelegramState): Option[String] =
    st.defaultChatId.map(_.trim).filter(_.nonEmpty).orElse {
      val privates = st.chats.filter(_.kind == "private")
      if privates.size == 1 then Some(privates.head.chatId) else None
    }

  def login(token: String, defaultChatId: Option[String]): IO[BotProfile] =
    for
      cfg <- config
      cleanToken <- IO(TelegramClient.sanitizeToken(token))
      meJson <- client.callJson(cleanToken, "getMe")
      (botId, username, firstName, canJoin, canReadAll, inlineQ) <- IO.fromEither(parseBotJson(meJson))
      now <- IO.realTimeInstant
      cleanDefault = defaultChatId.map(_.trim).filter(_.nonEmpty)
      updated <- modifyState { cur =>
        val sameBot = cur.botId.forall(_ == botId)
        StoredTelegramState(
          botToken = Some(cleanToken),
          botId = Some(botId),
          botUsername = Some(username),
          defaultChatId = cleanDefault.orElse(if sameBot then cur.defaultChatId else None),
          lastUpdateId = if sameBot then cur.lastUpdateId else None,
          chats = if sameBot then cur.chats else Vector.empty,
          updatedAt = Some(now.toString)
        )
      }
    yield BotProfile(
      id = botId,
      username = username,
      firstName = firstName,
      canJoinGroups = canJoin,
      canReadAllGroupMessages = canReadAll,
      supportsInlineQueries = inlineQ,
      apiBase = cfg.apiBase.trim,
      defaultChatId = effectiveDefaultChat(updated),
      knownChatsCount = updated.chats.size,
      lastUpdateId = updated.lastUpdateId
    )

  def logout: IO[LogoutResult] =
    mutex.lock.surround {
      for
        prev <- stateRef.get
        now <- IO.realTimeInstant
        cleared = prev.copy(botToken = None, updatedAt = Some(now.toString))
        _ <- writeDisk(stateFile, cleared) *> stateRef.set(cleared)
      yield LogoutResult(loggedOut = prev.botToken.exists(_.nonEmpty), username = prev.botUsername)
    }

  def me: IO[BotProfile] =
    for
      (cfg, token) <- requireToken
      meJson <- client.callJson(token, "getMe")
      (botId, username, firstName, canJoin, canReadAll, inlineQ) <- IO.fromEither(parseBotJson(meJson))
      now <- IO.realTimeInstant
      st <- modifyState(_.copy(botId = Some(botId), botUsername = Some(username), updatedAt = Some(now.toString)))
    yield BotProfile(
      id = botId,
      username = username,
      firstName = firstName,
      canJoinGroups = canJoin,
      canReadAllGroupMessages = canReadAll,
      supportsInlineQueries = inlineQ,
      apiBase = cfg.apiBase.trim,
      defaultChatId = effectiveDefaultChat(st),
      knownChatsCount = st.chats.size,
      lastUpdateId = st.lastUpdateId
    )

  def updates(
      limit: Int,
      timeoutSeconds: Int,
      offset: Option[Long],
      unreadOnly: Boolean,
      ack: Boolean,
      chatIdFilter: Option[String]
  ): IO[UpdatesResult] =
    for
      (_, token) <- requireToken
      res <- pollUpdatesInternal(token, limit, timeoutSeconds, offset, unreadOnly, ack, chatIdFilter)
    yield res

  def chatList(refresh: Boolean): IO[Vector[KnownChat]] =
    for
      (_, token) <- requireToken
      _ <- if refresh then pollUpdatesInternal(token, 50, 0, None, unreadOnly = false, ack = false, None).void else IO.unit
      st <- stateRef.get
    yield st.chats

  def chatGet(chatId: Option[String]): IO[ChatInfo] =
    for
      (cfg, token) <- requireToken
      target <- resolveTargetChat(cfg, token, chatId)
      json <- client.callJson(token, "getChat", Json.obj("chat_id" -> Json.fromString(target)))
      info = parseChatInfo(json)
      now <- IO.realTimeInstant
      _ <- modifyState { cur =>
        val entry = KnownChat(
          chatId = info.chatId,
          kind = info.kind,
          title = info.title,
          username = info.username,
          firstName = info.firstName,
          lastName = info.lastName,
          lastMessagePreview = cur.chats.find(_.chatId == info.chatId).flatMap(_.lastMessagePreview),
          lastSeenAt = now.toString
        )
        cur.copy(chats = upsertChats(cur.chats, Vector(entry)), updatedAt = Some(now.toString))
      }
    yield info

  def chatUse(chatId: String): IO[DefaultChatResult] =
    val clean = chatId.trim
    if clean.isEmpty then IO.raiseError(TelegramFailure("invalid_telegram", "chatId must not be blank"))
    else
      for
        (_, token) <- requireToken
        json <- client.callJson(token, "getChat", Json.obj("chat_id" -> Json.fromString(clean)))
        info = parseChatInfo(json)
        now <- IO.realTimeInstant
        _ <- modifyState { cur =>
          val entry = KnownChat(
            chatId = info.chatId,
            kind = info.kind,
            title = info.title,
            username = info.username,
            firstName = info.firstName,
            lastName = info.lastName,
            lastMessagePreview = cur.chats.find(_.chatId == info.chatId).flatMap(_.lastMessagePreview),
            lastSeenAt = now.toString
          )
          cur.copy(
            defaultChatId = Some(info.chatId),
            chats = upsertChats(cur.chats, Vector(entry)),
            updatedAt = Some(now.toString)
          )
        }
      yield DefaultChatResult(defaultChatId = info.chatId, chat = Some(info))

  def send(req: SendRequest): IO[TelegramMessage] =
    val text = Option(req.text).getOrElse("")
    if text.trim.isEmpty then IO.raiseError(TelegramFailure("invalid_telegram", "Message text must not be empty"))
    else if text.length > 4096 then
      IO.raiseError(TelegramFailure("invalid_telegram", s"Message text length (${text.length}) exceeds Telegram 4096-char limit"))
    else
      for
        (cfg, token) <- requireToken
        target <- resolveTargetChat(cfg, token, req.chatId)
        fields = buildSendFields(target, req)
        json <- client.callJson(token, "sendMessage", Json.obj(fields*))
        msg <- recordMessageChat(json, previewOverride = Some(text.take(120)))
      yield msg

  def edit(req: EditRequest): IO[TelegramMessage] =
    val text = Option(req.text).getOrElse("")
    if req.messageId <= 0 then IO.raiseError(TelegramFailure("invalid_telegram", "messageId must be > 0"))
    else if text.trim.isEmpty then IO.raiseError(TelegramFailure("invalid_telegram", "Edited text must not be empty"))
    else if text.length > 4096 then
      IO.raiseError(TelegramFailure("invalid_telegram", s"Message text length (${text.length}) exceeds Telegram 4096-char limit"))
    else
      for
        (cfg, token) <- requireToken
        target <- resolveTargetChat(cfg, token, req.chatId)
        fields = buildEditFields(target, req)
        json <- client.callJson(token, "editMessageText", Json.obj(fields*))
        msg <- recordMessageChat(json, previewOverride = Some(text.take(120)))
      yield msg

  def delete(messageId: Long, chatId: Option[String]): IO[TelegramActionAck] =
    if messageId <= 0 then IO.raiseError(TelegramFailure("invalid_telegram", "messageId must be > 0"))
    else
      for
        (cfg, token) <- requireToken
        target <- resolveTargetChat(cfg, token, chatId)
        res <- client.callJson(
          token,
          "deleteMessage",
          Json.obj("chat_id" -> Json.fromString(target), "message_id" -> Json.fromLong(messageId))
        )
        ok = res.asBoolean.getOrElse(true)
      yield TelegramActionAck(ok, Some(target), Some(messageId), s"Deleted message $messageId in chat $target")

  def pin(messageId: Long, chatId: Option[String], disableNotification: Boolean): IO[TelegramActionAck] =
    if messageId <= 0 then IO.raiseError(TelegramFailure("invalid_telegram", "messageId must be > 0"))
    else
      for
        (cfg, token) <- requireToken
        target <- resolveTargetChat(cfg, token, chatId)
        res <- client.callJson(
          token,
          "pinChatMessage",
          Json.obj(
            "chat_id" -> Json.fromString(target),
            "message_id" -> Json.fromLong(messageId),
            "disable_notification" -> Json.fromBoolean(disableNotification)
          )
        )
        ok = res.asBoolean.getOrElse(true)
      yield TelegramActionAck(ok, Some(target), Some(messageId), s"Pinned message $messageId in chat $target")

  def unpin(messageId: Option[Long], chatId: Option[String]): IO[TelegramActionAck] =
    for
      (cfg, token) <- requireToken
      target <- resolveTargetChat(cfg, token, chatId)
      base = Vector("chat_id" -> Json.fromString(target))
      extra = messageId.filter(_ > 0).map(id => "message_id" -> Json.fromLong(id)).toVector
      res <- client.callJson(token, "unpinChatMessage", Json.obj((base ++ extra)*))
      ok = res.asBoolean.getOrElse(true)
    yield TelegramActionAck(ok, Some(target), messageId, s"Unpinned message in chat $target")

  def sendPhoto(req: MediaSendRequest): IO[TelegramMessage] =
    sendMedia("sendPhoto", "photo", req)

  def sendDocument(req: MediaSendRequest): IO[TelegramMessage] =
    sendMedia("sendDocument", "document", req)

  private def sendMedia(method: String, mediaField: String, req: MediaSendRequest): IO[TelegramMessage] =
    val src = Option(req.source).map(_.trim).getOrElse("")
    if src.isEmpty then IO.raiseError(TelegramFailure("invalid_telegram", "Media source must not be empty"))
    else
      for
        (cfg, token) <- requireToken
        target <- resolveTargetChat(cfg, token, req.chatId)
        localOpt <- resolveLocalMediaPath(src)
        json <- localOpt match
          case Some(localPath) =>
            val formFields = buildMediaFormFields(target, req)
            client.callMultipart(token, method, formFields, mediaField, localPath, req.filename)
          case None =>
            val jsonFields = buildMediaJsonFields(target, mediaField, src, req)
            client.callJson(token, method, Json.obj(jsonFields*))
        msg <- recordMessageChat(json, previewOverride = req.caption.map(_.take(120)).orElse(Some(s"[$mediaField]")))
      yield msg

  def download(fileId: String, outputPath: Option[String]): IO[FileDownloadResult] =
    val cleanId = Option(fileId).map(_.trim).getOrElse("")
    if cleanId.isEmpty then IO.raiseError(TelegramFailure("invalid_telegram", "fileId must not be empty"))
    else
      for
        (_, token) <- requireToken
        fileMeta <- client.callJson(token, "getFile", Json.obj("file_id" -> Json.fromString(cleanId)))
        tgPath <- IO.fromEither(
          fileMeta.hcursor.get[String]("file_path").left.map(_ =>
            TelegramFailure("telegram_upstream", "Telegram getFile response did not include file_path")
          )
        )
        bytes <- client.downloadFile(token, tgPath)
        saved <- outputPath.map(_.trim).filter(_.nonEmpty) match
          case Some(destRaw) =>
            IO.blocking {
              val dest = expandTilde(destRaw).toAbsolutePath.normalize()
              Option(dest.getParent).foreach(p => Files.createDirectories(p))
              Files.write(dest, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
              Some(dest.toString)
            }
          case None => IO.pure(None)
        b64 = if saved.isEmpty && bytes.length <= 262144 then Some(Base64.getEncoder.encodeToString(bytes)) else None
      yield FileDownloadResult(
        fileId = cleanId,
        telegramFilePath = tgPath,
        savedPath = saved,
        sizeBytes = bytes.length.toLong,
        contentBase64 = b64
      )

  def webhookInfo: IO[WebhookStatus] =
    for
      (_, token) <- requireToken
      json <- client.callJson(token, "getWebhookInfo")
    yield parseWebhookStatus(json)

  def webhookDelete(dropPendingUpdates: Boolean): IO[TelegramActionAck] =
    for
      (_, token) <- requireToken
      res <- client.callJson(
        token,
        "deleteWebhook",
        Json.obj("drop_pending_updates" -> Json.fromBoolean(dropPendingUpdates))
      )
      ok = res.asBoolean.getOrElse(true)
    yield TelegramActionAck(ok, None, None, "Deleted Telegram webhook")

  def api(req: RawApiRequest): IO[Json] =
    for
      (_, token) <- requireToken
      res <- client.callJson(token, req.method.trim, req.payload)
    yield res

  private def resolveTargetChat(cfg: TelegramConfig, token: String, explicit: Option[String]): IO[String] =
    explicit.map(_.trim).filter(_.nonEmpty) match
      case Some(value) => IO.pure(value)
      case None =>
        stateRef.get.flatMap { st =>
          effectiveDefaultChat(st) match
            case Some(found) => IO.pure(found)
            case None =>
              for
                _ <- pollUpdatesInternal(token, 30, 0, None, unreadOnly = false, ack = false, None)
                after <- stateRef.get
                resolved <- effectiveDefaultChat(after)
                  .orElse(after.chats.find(_.kind == "private").map(_.chatId))
                  .orElse(after.chats.headOption.map(_.chatId)) match
                  case Some(chatId) => IO.pure(chatId)
                  case None =>
                    val handleHint = after.botUsername.map(u => s"@$u (https://t.me/$u)").getOrElse("the bot")
                    IO.raiseError(
                      TelegramFailure(
                        "invalid_telegram",
                        s"No target chatId configured or discovered yet; send /start to $handleHint in Telegram first, or pass --chatId explicitly"
                      )
                    )
              yield resolved
        }

  private def pollUpdatesInternal(
      token: String,
      limit: Int,
      timeoutSeconds: Int,
      offset: Option[Long],
      unreadOnly: Boolean,
      ack: Boolean,
      chatIdFilter: Option[String]
  ): IO[UpdatesResult] =
    for
      st0 <- stateRef.get
      effectiveOffset = offset.orElse(if unreadOnly then st0.lastUpdateId.map(_ + 1L) else None)
      baseFields = Vector(
        "limit" -> Json.fromInt(math.max(1, math.min(100, limit))),
        "timeout" -> Json.fromInt(math.max(0, math.min(50, timeoutSeconds)))
      )
      offsetFields = effectiveOffset.map(o => "offset" -> Json.fromLong(o)).toVector
      rawJson <- client.callJson(
        token,
        "getUpdates",
        Json.obj((baseFields ++ offsetFields)*),
        extraTimeoutSeconds = math.max(0, math.min(50, timeoutSeconds))
      )
      now <- IO.realTimeInstant
      rawArr = rawJson.asArray.getOrElse(Vector.empty)
      parsed = rawArr.flatMap(parseUpdateItem(_, now.toString))
      items = parsed.map(_._1)
      discovered = parsed.flatMap(_._2)
      maxUpdateId = items.map(_.updateId).maxOption
      nextOff = maxUpdateId.map(_ + 1L).orElse(effectiveOffset)
      updated <-
        if discovered.isEmpty && !(ack && maxUpdateId.isDefined) then IO.pure(st0)
        else
          modifyState { cur =>
            val mergedChats = upsertChats(cur.chats, discovered)
            val autoDefault = cur.defaultChatId.orElse(
              discovered.find(_.kind == "private").map(_.chatId)
            )
            val nextLastId =
              if ack && maxUpdateId.isDefined then
                Some(cur.lastUpdateId.fold(maxUpdateId.get)(math.max(_, maxUpdateId.get)))
              else cur.lastUpdateId
            cur.copy(
              defaultChatId = autoDefault,
              lastUpdateId = nextLastId,
              chats = mergedChats,
              updatedAt = Some(now.toString)
            )
          }
      cleanFilter = chatIdFilter.map(_.trim).filter(_.nonEmpty)
      filtered = cleanFilter match
        case None => items
        case Some(cid) =>
          items.filter { item =>
            item.message.exists(_.chatId == cid) || item.callbackQuery.flatMap(_.chatId).contains(cid)
          }
    yield UpdatesResult(updates = filtered, nextOffset = nextOff, knownChats = updated.chats)

  private def recordMessageChat(msgJson: Json, previewOverride: Option[String]): IO[TelegramMessage] =
    for
      now <- IO.realTimeInstant
      (msg, knownOpt) <- IO.fromEither(parseMessageWithChat(msgJson, now.toString, previewOverride))
      _ <- knownOpt.fold(IO.unit) { entry =>
        modifyState { cur =>
          val autoDefault = cur.defaultChatId.orElse(Option.when(entry.kind == "private")(entry.chatId))
          cur.copy(
            defaultChatId = autoDefault,
            chats = upsertChats(cur.chats, Vector(entry)),
            updatedAt = Some(now.toString)
          )
        }.void
      }
    yield msg

  private def modifyState(f: StoredTelegramState => StoredTelegramState): IO[StoredTelegramState] =
    mutex.lock.surround {
      for
        cur <- stateRef.get
        next = f(cur)
        _ <- if next == cur then IO.unit else writeDisk(stateFile, next) *> stateRef.set(next)
      yield next
    }

object TelegramService:
  private val maxKnownChats = 200

  def resource(
      home: Path,
      config: IO[TelegramConfig] = IO.pure(TelegramConfig())
  ): Resource[IO, TelegramService] =
    for
      client <- TelegramClient.resource(config)
      svc <- Resource.eval {
        val stateFile = home.resolve("telegram").resolve("state.json")
        for
          init <- IO.blocking {
            if Files.isRegularFile(stateFile) then
              decode[StoredTelegramState](Files.readString(stateFile, UTF_8)).getOrElse(StoredTelegramState())
            else StoredTelegramState()
          }
          ref <- Ref.of[IO, StoredTelegramState](init)
          mutex <- Mutex[IO]
        yield new TelegramService(stateFile, ref, mutex, client, config)
      }
    yield svc

  private def writeDisk(target: Path, state: StoredTelegramState): IO[Unit] = IO.blocking {
    val dir = target.getParent
    if !Files.exists(dir) then
      Files.createDirectories(dir)
      setPerms(dir, "rwx------")
    val tmp = Files.createTempFile(dir, ".state-", ".tmp")
    try
      setPerms(tmp, "rw-------")
      Files.writeString(tmp, state.asJson.spaces2 + "\n", UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      setPerms(target, "rw-------")
    finally
      Files.deleteIfExists(tmp)
      ()
  }

  private def setPerms(p: Path, perms: String): Unit =
    try Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms))
    catch case _: UnsupportedOperationException => ()

  private def upsertChats(existing: Vector[KnownChat], incoming: Vector[KnownChat]): Vector[KnownChat] =
    val merged = incoming.foldLeft(existing) { (acc, entry) =>
      val without = acc.filterNot(_.chatId == entry.chatId)
      val prev = acc.find(_.chatId == entry.chatId)
      val combined = entry.copy(
        title = entry.title.orElse(prev.flatMap(_.title)),
        username = entry.username.orElse(prev.flatMap(_.username)),
        firstName = entry.firstName.orElse(prev.flatMap(_.firstName)),
        lastName = entry.lastName.orElse(prev.flatMap(_.lastName)),
        lastMessagePreview = entry.lastMessagePreview.orElse(prev.flatMap(_.lastMessagePreview))
      )
      combined +: without
    }
    merged.take(maxKnownChats)

  private def encodeButtons(rows: Vector[Vector[InlineButton]]): Option[(String, Json)] =
    val nonEmptyRows = rows.map(_.filter(b => b.text.trim.nonEmpty && (b.url.nonEmpty || b.callbackData.nonEmpty))).filter(_.nonEmpty)
    if nonEmptyRows.isEmpty then None
    else
      val grid = nonEmptyRows.map { row =>
        Json.fromValues(row.map { b =>
          val base = Vector("text" -> Json.fromString(b.text.trim))
          val target = b.url.map(_.trim).filter(_.nonEmpty).map(u => "url" -> Json.fromString(u))
            .orElse(b.callbackData.map(_.trim).filter(_.nonEmpty).map(d => "callback_data" -> Json.fromString(d)))
            .toVector
          Json.obj((base ++ target)*)
        })
      }
      Some("reply_markup" -> Json.obj("inline_keyboard" -> Json.fromValues(grid)))

  private def parseModeField(pm: ParseMode): Option[(String, Json)] = pm match
    case ParseMode.plain => None
    case other           => Some("parse_mode" -> Json.fromString(other.toString))

  private def buildSendFields(chatId: String, req: SendRequest): Vector[(String, Json)] =
    val base = Vector(
      Some("chat_id" -> Json.fromString(chatId)),
      Some("text" -> Json.fromString(req.text)),
      parseModeField(req.parseMode),
      req.replyToMessageId.filter(_ > 0).map(id => "reply_parameters" -> Json.obj("message_id" -> Json.fromLong(id))),
      req.messageThreadId.filter(_ > 0).map(id => "message_thread_id" -> Json.fromLong(id)),
      Option.when(req.disableNotification)("disable_notification" -> Json.True),
      Option.when(req.disableWebPagePreview)("link_preview_options" -> Json.obj("is_disabled" -> Json.True)),
      Option.when(req.protectContent)("protect_content" -> Json.True),
      encodeButtons(req.buttons)
    )
    base.flatten

  private def buildEditFields(chatId: String, req: EditRequest): Vector[(String, Json)] =
    Vector(
      Some("chat_id" -> Json.fromString(chatId)),
      Some("message_id" -> Json.fromLong(req.messageId)),
      Some("text" -> Json.fromString(req.text)),
      parseModeField(req.parseMode),
      Option.when(req.disableWebPagePreview)("link_preview_options" -> Json.obj("is_disabled" -> Json.True)),
      encodeButtons(req.buttons)
    ).flatten

  private def buildMediaFormFields(chatId: String, req: MediaSendRequest): Vector[(String, String)] =
    Vector(
      Some("chat_id" -> chatId),
      req.caption.map(_.trim).filter(_.nonEmpty).map("caption" -> _),
      Option.when(req.parseMode != ParseMode.plain)("parse_mode" -> req.parseMode.toString),
      req.replyToMessageId.filter(_ > 0).map(id => "reply_to_message_id" -> id.toString),
      req.messageThreadId.filter(_ > 0).map(id => "message_thread_id" -> id.toString),
      Option.when(req.disableNotification)("disable_notification" -> "true")
    ).flatten

  private def buildMediaJsonFields(
      chatId: String,
      mediaField: String,
      source: String,
      req: MediaSendRequest
  ): Vector[(String, Json)] =
    Vector(
      Some("chat_id" -> Json.fromString(chatId)),
      Some(mediaField -> Json.fromString(source)),
      req.caption.map(_.trim).filter(_.nonEmpty).map(c => "caption" -> Json.fromString(c)),
      parseModeField(req.parseMode),
      req.replyToMessageId.filter(_ > 0).map(id => "reply_parameters" -> Json.obj("message_id" -> Json.fromLong(id))),
      req.messageThreadId.filter(_ > 0).map(id => "message_thread_id" -> Json.fromLong(id)),
      Option.when(req.disableNotification)("disable_notification" -> Json.True)
    ).flatten

  private def expandTilde(raw: String): Path =
    if raw.startsWith("~/") then Path.of(System.getProperty("user.home"), raw.drop(2))
    else Path.of(raw)

  private def resolveLocalMediaPath(source: String): IO[Option[Path]] =
    if source.startsWith("http://") || source.startsWith("https://") then IO.pure(None)
    else if source.startsWith("/") || source.startsWith("./") || source.startsWith("../") || source.startsWith("~/") then
      IO.pure(Some(expandTilde(source)))
    else
      IO.blocking {
        val candidate = Path.of(source)
        if Files.isRegularFile(candidate) then Some(candidate) else None
      }

  private def parseBotJson(json: Json): Either[TelegramFailure, (Long, String, String, Boolean, Boolean, Boolean)] =
    val c = json.hcursor
    for
      id <- c.get[Long]("id").left.map(_ => TelegramFailure("telegram_upstream", "Missing bot id in getMe"))
      username <- c.get[String]("username").left.map(_ => TelegramFailure("telegram_upstream", "Missing username in getMe"))
      firstName = c.get[String]("first_name").getOrElse(username)
      canJoin = c.get[Boolean]("can_join_groups").getOrElse(false)
      canReadAll = c.get[Boolean]("can_read_all_group_messages").getOrElse(false)
      inlineQ = c.get[Boolean]("supports_inline_queries").getOrElse(false)
    yield (id, username, firstName, canJoin, canReadAll, inlineQ)

  private def parseChatInfo(json: Json): ChatInfo =
    val c = json.hcursor
    val chatId = c.get[Long]("id").map(_.toString).orElse(c.get[String]("id")).getOrElse("")
    ChatInfo(
      chatId = chatId,
      kind = c.get[String]("type").getOrElse("unknown"),
      title = c.get[String]("title").toOption.filter(_.nonEmpty),
      username = c.get[String]("username").toOption.filter(_.nonEmpty),
      firstName = c.get[String]("first_name").toOption.filter(_.nonEmpty),
      lastName = c.get[String]("last_name").toOption.filter(_.nonEmpty),
      bio = c.get[String]("bio").toOption.filter(_.nonEmpty),
      description = c.get[String]("description").toOption.filter(_.nonEmpty)
    )

  private def parseUser(c: HCursor): Option[TelegramUserSummary] =
    c.get[Long]("id").toOption.map { id =>
      TelegramUserSummary(
        id = id,
        isBot = c.get[Boolean]("is_bot").getOrElse(false),
        username = c.get[String]("username").toOption.filter(_.nonEmpty),
        firstName = c.get[String]("first_name").getOrElse(""),
        lastName = c.get[String]("last_name").toOption.filter(_.nonEmpty)
      )
    }

  private def parseAttachments(c: HCursor): Vector[TelegramAttachment] =
    val doc = c.downField("document").focus.flatMap { j =>
      val dc = j.hcursor
      dc.get[String]("file_id").toOption.map { fid =>
        TelegramAttachment(
          kind = "document",
          fileId = fid,
          fileUniqueId = dc.get[String]("file_unique_id").toOption,
          fileName = dc.get[String]("file_name").toOption,
          mimeType = dc.get[String]("mime_type").toOption,
          fileSize = dc.get[Long]("file_size").toOption
        )
      }
    }
    val photo = c.downField("photo").as[Vector[Json]].toOption.flatMap(_.lastOption).flatMap { j =>
      val pc = j.hcursor
      pc.get[String]("file_id").toOption.map { fid =>
        TelegramAttachment(
          kind = "photo",
          fileId = fid,
          fileUniqueId = pc.get[String]("file_unique_id").toOption,
          fileSize = pc.get[Long]("file_size").toOption
        )
      }
    }
    val otherKinds = List("audio", "video", "voice", "sticker", "animation").flatMap { k =>
      c.downField(k).focus.flatMap { j =>
        val kc = j.hcursor
        kc.get[String]("file_id").toOption.map { fid =>
          TelegramAttachment(
            kind = k,
            fileId = fid,
            fileUniqueId = kc.get[String]("file_unique_id").toOption,
            fileName = kc.get[String]("file_name").toOption,
            mimeType = kc.get[String]("mime_type").toOption,
            fileSize = kc.get[Long]("file_size").toOption
          )
        }
      }
    }
    (photo.toVector ++ doc.toVector ++ otherKinds)

  private def parseMessageWithChat(
      json: Json,
      seenAt: String,
      previewOverride: Option[String] = None
  ): Either[TelegramFailure, (TelegramMessage, Option[KnownChat])] =
    val c = json.hcursor
    for
      msgId <- c.get[Long]("message_id").left.map(_ =>
        TelegramFailure("telegram_upstream", "Missing message_id in Telegram message")
      )
      chatCursor = c.downField("chat")
      chatId <- chatCursor.get[Long]("id").map(_.toString).orElse(chatCursor.get[String]("id")).left.map(_ =>
        TelegramFailure("telegram_upstream", "Missing chat.id in Telegram message")
      )
    yield
      val chatKind = chatCursor.get[String]("type").getOrElse("unknown")
      val chatTitle = chatCursor.get[String]("title").toOption.filter(_.nonEmpty)
      val chatUsername = chatCursor.get[String]("username").toOption.filter(_.nonEmpty)
      val chatFirst = chatCursor.get[String]("first_name").toOption.filter(_.nonEmpty)
      val chatLast = chatCursor.get[String]("last_name").toOption.filter(_.nonEmpty)
      val text = c.get[String]("text").toOption
      val caption = c.get[String]("caption").toOption
      val preview = previewOverride.orElse(text.map(_.take(120))).orElse(caption.map(_.take(120)))
      val msg = TelegramMessage(
        messageId = msgId,
        chatId = chatId,
        chatKind = chatKind,
        chatTitle = chatTitle,
        chatUsername = chatUsername,
        messageThreadId = c.get[Long]("message_thread_id").toOption,
        from = c.downField("from").focus.flatMap(j => parseUser(j.hcursor)),
        date = c.get[Long]("date").getOrElse(0L),
        editDate = c.get[Long]("edit_date").toOption,
        replyToMessageId = c.downField("reply_to_message").get[Long]("message_id").toOption,
        text = text,
        caption = caption,
        attachments = parseAttachments(c)
      )
      val known = KnownChat(
        chatId = chatId,
        kind = chatKind,
        title = chatTitle,
        username = chatUsername,
        firstName = chatFirst,
        lastName = chatLast,
        lastMessagePreview = preview,
        lastSeenAt = seenAt
      )
      (msg, Some(known))

  private def parseUpdateItem(json: Json, seenAt: String): Option[(TelegramUpdateItem, Option[KnownChat])] =
    val c = json.hcursor
    c.get[Long]("update_id").toOption.map { uid =>
      val msgFields = List("message", "edited_message", "channel_post", "edited_channel_post")
      msgFields.collectFirst(Function.unlift(f => c.downField(f).focus.map(j => (f, j)))) match
        case Some((kind, msgJson)) =>
          parseMessageWithChat(msgJson, seenAt) match
            case Right((msg, known)) =>
              (TelegramUpdateItem(updateId = uid, kind = kind, message = Some(msg)), known)
            case Left(_) =>
              (TelegramUpdateItem(updateId = uid, kind = kind), None)
        case None =>
          c.downField("callback_query").focus match
            case Some(cbJson) =>
              val cc = cbJson.hcursor
              val cbId = cc.get[String]("id").getOrElse(uid.toString)
              val from = cc.downField("from").focus.flatMap(j => parseUser(j.hcursor)).getOrElse(
                TelegramUserSummary(0L, false, None, "")
              )
              val msgOpt = cc.downField("message").focus.flatMap(j => parseMessageWithChat(j, seenAt).toOption)
              val cb = TelegramCallback(
                id = cbId,
                from = from,
                chatId = msgOpt.map(_._1.chatId),
                messageId = msgOpt.map(_._1.messageId),
                data = cc.get[String]("data").toOption
              )
              (TelegramUpdateItem(updateId = uid, kind = "callback_query", callbackQuery = Some(cb)), msgOpt.flatMap(_._2))
            case None =>
              (TelegramUpdateItem(updateId = uid, kind = "other"), None)
    }

  private def parseWebhookStatus(json: Json): WebhookStatus =
    val c = json.hcursor
    WebhookStatus(
      url = c.get[String]("url").getOrElse(""),
      hasCustomCertificate = c.get[Boolean]("has_custom_certificate").getOrElse(false),
      pendingUpdateCount = c.get[Int]("pending_update_count").getOrElse(0),
      lastErrorDate = c.get[Long]("last_error_date").toOption,
      lastErrorMessage = c.get[String]("last_error_message").toOption,
      maxConnections = c.get[Int]("max_connections").toOption,
      allowedUpdates = c.get[Vector[String]]("allowed_updates").getOrElse(Vector.empty)
    )
