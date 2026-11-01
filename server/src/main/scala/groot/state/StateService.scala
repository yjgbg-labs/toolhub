package groot.state

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.util.Using

/** Shared across tasks; every operation commits independently before returning. */
final class StateService private (connection: Connection, mutex: Mutex[IO]):
  private def serial[A](key: String)(action: => A): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.blocking {
    if key.isEmpty || key.contains('\u0000') || key.codePointCount(0, key.length) > 128 then
      throw InvalidState("Key must contain 1 to 128 characters without NUL bytes")
    action
  }))

  def set(key: String, value: String): IO[Unit] = serial(key) {
    if value.getBytes(UTF_8).length > StateService.maxValueBytes then throw StateLimit("Value exceeds 64 KiB of UTF-8")
    Using.resource(connection.prepareStatement("INSERT INTO state(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) { statement =>
      statement.setString(1, key)
      statement.setString(2, value)
      statement.executeUpdate()
      ()
    }
  }

  def get(key: String): IO[Option[String]] = serial(key) {
    Using.resource(connection.prepareStatement("SELECT value FROM state WHERE key = ?")) { statement =>
      statement.setString(1, key)
      Using.resource(statement.executeQuery()) { rows => Option.when(rows.next())(rows.getString(1)) }
    }
  }

  def clear(key: String): IO[Boolean] = serial(key) {
    Using.resource(connection.prepareStatement("DELETE FROM state WHERE key = ?")) { statement =>
      statement.setString(1, key)
      statement.executeUpdate() != 0
    }
  }

object StateService:
  val maxValueBytes = 64 * 1024

  def resource(home: Path = groot.schedule.Store.defaultHome.resolve("state")): Resource[IO, StateService] = for
    connection <- Resource.fromAutoCloseable(IO.blocking {
      Files.createDirectories(home)
      Class.forName("org.sqlite.JDBC")
      val config = new org.sqlite.SQLiteConfig
      config.setBusyTimeout(5000)
      DriverManager.getConnection(s"jdbc:sqlite:${home.resolve("state.sqlite3").toAbsolutePath.toUri.toASCIIString}", config.toProperties)
    })
    _ <- Resource.eval(IO.blocking {
      Using.resource(connection.createStatement()) { statement =>
        statement.execute("PRAGMA journal_mode=WAL")
        statement.execute("PRAGMA synchronous=FULL")
        val version = Using.resource(statement.executeQuery("PRAGMA user_version")) { row => row.next(); row.getInt(1) }
        require(version <= 1, s"Unsupported state database version: $version")
        statement.execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        statement.execute("PRAGMA user_version=1")
        ()
      }
    })
    mutex <- Resource.eval(Mutex[IO])
  yield new StateService(connection, mutex)
