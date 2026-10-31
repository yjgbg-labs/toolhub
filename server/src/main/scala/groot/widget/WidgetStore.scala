package groot.widget

import cats.effect.{IO, Resource}
import framework.model.JsonModel
import io.circe.parser.parse
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.sql.{Connection, DriverManager}
import scala.util.Using

/** One durable snapshot; a SQLite statement commits items, settings and their revision together. */
private[widget] final class WidgetStore(connection: Connection):
  def load: IO[WidgetState] = IO.blocking {
    Using.resource(connection.createStatement()) { statement =>
      Using.resource(statement.executeQuery("SELECT state FROM widget WHERE id=1")) { row =>
        if row.next() then parse(row.getString(1)).flatMap(JsonModel[WidgetState].decoder.decodeJson).fold(throw _, identity)
        else WidgetState()
      }
    }
  }

  def save(state: WidgetState): IO[Unit] = IO.blocking {
    Using.resource(connection.prepareStatement("INSERT INTO widget(id,state) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state")) { statement =>
      statement.setString(1, JsonModel[WidgetState].encoder(state).noSpaces)
      statement.executeUpdate()
      ()
    }
  }

private[widget] object WidgetStore:
  def resource(home: Path): Resource[IO, WidgetStore] = for
    _ <- Resource.make(IO.blocking {
      Files.createDirectories(home)
      val channel = FileChannel.open(home.resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        if channel.tryLock() == null then throw new IllegalStateException(s"A widget service already owns $home")
        channel
      catch
        case error: Throwable => channel.close(); throw error
    })(channel => IO.blocking(channel.close()))
    connection <- Resource.fromAutoCloseable(IO.blocking {
      Class.forName("org.sqlite.JDBC")
      DriverManager.getConnection(s"jdbc:sqlite:${home.resolve("widget.sqlite3").toAbsolutePath}")
    })
    _ <- Resource.eval(IO.blocking {
      Using.resource(connection.createStatement()) { statement =>
        statement.execute("PRAGMA busy_timeout=5000")
        statement.execute("PRAGMA journal_mode=WAL")
        statement.execute("PRAGMA synchronous=FULL")
        val version = Using.resource(statement.executeQuery("PRAGMA user_version")) { row => row.next(); row.getInt(1) }
        require(version <= 1, s"Unsupported widget database version: $version")
        statement.execute("CREATE TABLE IF NOT EXISTS widget(id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL)")
        statement.execute("PRAGMA user_version=1")
        ()
      }
    })
  yield new WidgetStore(connection)
