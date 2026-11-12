package groot.wechat

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.{Connection, DriverManager, ResultSet}
import scala.util.Using

/** Cursor, conversation token and inbox commit together, before any notification is published. */
private[wechat] final class WechatStore(connection: Connection, mutex: Mutex[IO]):
  import WechatApi.field
  private def sql[A](f: => A): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.blocking(f)))
  private def update(query: String, args: String*): Int = Using.resource(connection.prepareStatement(query)) { s =>
    args.zipWithIndex.foreach((v, i) => s.setString(i + 1, v))
    s.executeUpdate()
  }
  private def rows[A](query: String, args: Seq[String] = Seq.empty)(f: ResultSet => A): Vector[A] =
    Using.resource(connection.prepareStatement(query)) { s =>
      args.zipWithIndex.foreach((v, i) => s.setString(i + 1, v))
      Using.resource(s.executeQuery()) { r =>
        val values = Vector.newBuilder[A]
        while r.next() do values += f(r)
        values.result()
      }
    }
  private def transaction[A](f: => A): A =
    connection.setAutoCommit(false)
    try
      val value = f
      connection.commit()
      value
    catch
      case e: Throwable => connection.rollback(); throw e
    finally connection.setAutoCommit(true)

  private def account(r: ResultSet): Account = Account(r.getString("wechatid"), r.getString("bot_id"),
    r.getString("token"), r.getString("base_url"), r.getString("cursor"), Option(r.getString("context")),
    Option(r.getString("session")), r.getString("status"))
  def all: IO[Vector[Account]] = sql(rows("SELECT * FROM accounts ORDER BY rowid DESC")(account))
  def get(id: String): IO[Account] = sql(rows("SELECT * FROM accounts WHERE wechatid=?", Seq(id))(account)
    .headOption.getOrElse(throw WechatFailure("wechat_not_found", "WeChat ID is not logged in", 404)))
  def save(a: Account): IO[Unit] = sql {
    update("""INSERT INTO accounts(wechatid,bot_id,token,base_url) VALUES(?,?,?,?)
      ON CONFLICT(wechatid) DO UPDATE SET bot_id=excluded.bot_id,token=excluded.token,base_url=excluded.base_url,
      cursor=CASE WHEN accounts.bot_id=excluded.bot_id THEN accounts.cursor ELSE '' END,
      context=CASE WHEN accounts.bot_id=excluded.bot_id AND accounts.token=excluded.token THEN accounts.context ELSE NULL END,status='connected'""",
      a.wechatid, a.botId, a.token, a.baseUrl)
    ()
  }
  def bind(id: String, session: Option[String]): IO[Unit] = sql {
    if update("UPDATE accounts SET session=? WHERE wechatid=?", session.orNull, id) == 0 then
      throw WechatFailure("wechat_not_found", "WeChat ID is not logged in", 404)
  }
  def status(id: String, value: String): IO[Unit] = sql { update("UPDATE accounts SET status=? WHERE wechatid=?", value, id); () }
  def list: IO[Vector[WechatAccount]] = sql {
    rows("""SELECT a.*, (SELECT count(*) FROM inbox i WHERE i.wechatid=a.wechatid AND i.delivered=0) AS pending
      FROM accounts a ORDER BY a.wechatid""") { r =>
      val a = account(r)
      WechatAccount(a.wechatid, a.botId, a.session, a.status, r.getLong("pending"))
    }
  }

  def ingest(a: Account, response: Json): IO[Unit] = sql { transaction {
    // Reject malformed batches rather than advancing past them.
    val messages = response.hcursor.get[Option[Vector[Json]]]("msgs").fold(
      _ => throw WechatFailure("wechat_upstream", "Invalid WeChat message batch", 502), _.getOrElse(Vector.empty))
    messages.foreach { msg =>
      val sender = field(msg, "from_user_id")
      if sender.contains(a.wechatid) && msg.hcursor.get[Int]("message_type").toOption.contains(1) then
        val key = field(msg, "message_id").orElse(field(msg, "client_id")).getOrElse {
          val stable = msg.mapObject(_.remove("context_token")).noSpaces
          java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stable.getBytes(UTF_8)))
        }
        val items = msg.hcursor.get[Vector[Json]]("item_list").getOrElse(Vector.empty)
        val content = if items.isEmpty then msg.mapObject(_.remove("context_token")).noSpaces else items.map { item =>
          if item.hcursor.get[Int]("type").toOption.contains(1) && item.hcursor.downField("text_item").get[String]("text").isRight then
            val text = item.hcursor.downField("text_item").get[String]("text").toOption.get
            item.hcursor.downField("ref_msg").focus.fold(text)(ref => s"$text\n[引用] ${ref.noSpaces}")
          else s"[微信消息项] ${item.noSpaces}"
        }.mkString("\n")
        // Keep every item, including media metadata; split at code point boundaries below notify's 64 KiB limit.
        val parts = content.codePoints().toArray.grouped(10000).map(cps => new String(cps, 0, cps.length)).toVector match
          case v if v.nonEmpty => v
          case _ => Vector("[空消息]")
        var inserted = false
        parts.zipWithIndex.foreach { (part, index) =>
          val text = s"[WeChat wechatid=${a.wechatid} messageId=$key part=${index + 1}/${parts.size}]\n$part"
          inserted = update("INSERT OR IGNORE INTO inbox(wechatid,bot_id,message_key,part,message) VALUES(?,?,?,?,?)",
            a.wechatid, a.botId, key, index.toString, text) != 0 || inserted
        }
        if inserted then field(msg, "context_token").foreach { token =>
          update("UPDATE accounts SET context=? WHERE wechatid=? AND bot_id=?", token, a.wechatid, a.botId)
        }
    }
    update("UPDATE accounts SET cursor=?,status='connected' WHERE wechatid=? AND bot_id=?",
      field(response, "get_updates_buf").getOrElse(a.cursor), a.wechatid, a.botId)
    ()
  }}
  def pending(id: String): IO[Option[Pending]] = sql {
    rows("""SELECT i.id,a.session,i.message FROM inbox i JOIN accounts a ON a.wechatid=i.wechatid
      WHERE i.wechatid=? AND i.delivered=0 AND a.session IS NOT NULL ORDER BY i.id LIMIT 1""", Seq(id)) { r =>
      Pending(r.getLong(1), r.getString(2), r.getString(3))
    }.headOption
  }
  def delivered(id: Long): IO[Unit] = sql {
    // Retain deduplication keys, release the potentially large message body.
    update("UPDATE inbox SET delivered=1,message='' WHERE id=?", id.toString)
    ()
  }

private[wechat] object WechatStore:
  def resource(home: Path): Resource[IO, WechatStore] = for
    connection <- Resource.fromAutoCloseable(IO.blocking {
      Files.createDirectories(home)
      Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
      val file = home.resolve("wechat.sqlite3")
      if !Files.exists(file) then Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
      Class.forName("org.sqlite.JDBC")
      val config = new org.sqlite.SQLiteConfig
      config.setBusyTimeout(5000)
      DriverManager.getConnection(s"jdbc:sqlite:${file.toAbsolutePath.toUri.toASCIIString}", config.toProperties)
    })
    _ <- Resource.eval(IO.blocking { Using.resource(connection.createStatement()) { s =>
      s.execute("PRAGMA journal_mode=WAL")
      s.execute("PRAGMA synchronous=FULL")
      val version = Using.resource(s.executeQuery("PRAGMA user_version")) { r => r.next(); r.getInt(1) }
      require(version <= 1, s"Unsupported WeChat database version: $version")
      s.execute("""CREATE TABLE IF NOT EXISTS accounts(wechatid TEXT PRIMARY KEY NOT NULL,bot_id TEXT NOT NULL,
        token TEXT NOT NULL,base_url TEXT NOT NULL,cursor TEXT NOT NULL DEFAULT '',context TEXT,session TEXT,
        status TEXT NOT NULL DEFAULT 'connected')""")
      s.execute("""CREATE TABLE IF NOT EXISTS inbox(id INTEGER PRIMARY KEY AUTOINCREMENT,wechatid TEXT NOT NULL,
        bot_id TEXT NOT NULL,message_key TEXT NOT NULL,part INTEGER NOT NULL,message TEXT NOT NULL,
        delivered INTEGER NOT NULL DEFAULT 0,UNIQUE(wechatid,bot_id,message_key,part))""")
      s.execute("CREATE INDEX IF NOT EXISTS inbox_pending ON inbox(wechatid,delivered,id)")
      s.execute("PRAGMA user_version=1")
      ()
    }})
    mutex <- Resource.eval(Mutex[IO])
  yield new WechatStore(connection, mutex)
