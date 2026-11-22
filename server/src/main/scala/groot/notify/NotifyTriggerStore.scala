package groot.notify

import cats.effect.{IO, Resource}
import framework.model.JsonModel
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.util.Using

/** SQLite persistence for notify.when / notify.at triggers under $GROOT_HOME/notify/triggers.sqlite3. */
private[notify] final class NotifyTriggerStore(connection: Connection):
  private val model = JsonModel[NotifyTrigger]
  private def sql[A](f: Connection => A): IO[A] = IO.blocking(connection.synchronized(f(connection)))
  private def decode(text: String): NotifyTrigger =
    parse(text).flatMap(model.decoder.decodeJson).fold(throw _, identity)

  def close(): Unit = connection.synchronized(connection.close())

  def insert(trigger: NotifyTrigger): IO[Unit] = sql { db =>
    Using.resource(db.prepareStatement("INSERT INTO triggers(id, status, trigger_json) VALUES (?, ?, ?)")) { st =>
      st.setString(1, trigger.id)
      st.setString(2, trigger.status)
      st.setString(3, model.encoder(trigger).noSpaces)
      st.executeUpdate()
      ()
    }
  }

  def save(trigger: NotifyTrigger): IO[Unit] = sql { db =>
    Using.resource(db.prepareStatement("UPDATE triggers SET status = ?, trigger_json = ? WHERE id = ?")) { st =>
      st.setString(1, trigger.status)
      st.setString(2, model.encoder(trigger).noSpaces)
      st.setString(3, trigger.id)
      st.executeUpdate()
      ()
    }
  }

  def get(id: String): IO[Option[NotifyTrigger]] = sql { db =>
    Using.resource(db.prepareStatement("SELECT trigger_json FROM triggers WHERE id = ?")) { st =>
      st.setString(1, id)
      Using.resource(st.executeQuery()) { rows =>
        if rows.next() then Some(decode(rows.getString(1))) else None
      }
    }
  }

  def active: IO[List[NotifyTrigger]] = sql { db =>
    Using.resource(db.createStatement()) { st =>
      Using.resource(st.executeQuery("SELECT trigger_json FROM triggers WHERE status = 'active' ORDER BY seq")) { rows =>
        val b = List.newBuilder[NotifyTrigger]
        while rows.next() do b += decode(rows.getString(1))
        b.result()
      }
    }
  }

  def listAll(limit: Int = 200): IO[List[NotifyTrigger]] = sql { db =>
    Using.resource(db.prepareStatement("SELECT trigger_json FROM triggers ORDER BY seq DESC LIMIT ?")) { st =>
      st.setInt(1, limit)
      Using.resource(st.executeQuery()) { rows =>
        val b = List.newBuilder[NotifyTrigger]
        while rows.next() do b += decode(rows.getString(1))
        b.result().reverse
      }
    }
  }

private[notify] object NotifyTriggerStore:
  def resource(home: Path): Resource[IO, NotifyTriggerStore] =
    Resource.make(IO.blocking {
      val dir = home.resolve("notify")
      Files.createDirectories(dir)
      val dbPath = dir.resolve("triggers.sqlite3")
      val connection = DriverManager.getConnection(s"jdbc:sqlite:${dbPath.toAbsolutePath}")
      Using.resource(connection.createStatement()) { st =>
        st.execute("PRAGMA journal_mode=WAL")
        st.execute("PRAGMA synchronous=NORMAL")
        st.execute("""CREATE TABLE IF NOT EXISTS triggers (
          seq INTEGER PRIMARY KEY AUTOINCREMENT,
          id TEXT UNIQUE NOT NULL,
          status TEXT NOT NULL,
          trigger_json TEXT NOT NULL
        )""")
        st.execute("CREATE INDEX IF NOT EXISTS idx_triggers_status ON triggers(status)")
      }
      new NotifyTriggerStore(connection)
    })(store => IO.blocking(store.close()))
