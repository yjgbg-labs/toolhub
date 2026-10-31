package groot.sqlite

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.sql.Connection
import java.util.UUID
import scala.util.Using

/** The registry is serialized; each SQL request owns an independent connection to its selected database. */
final class SqliteService private (directory: Path, registry: Connection, mutex: Mutex[IO]):
  private def serial[A](action: => A): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.blocking(action)))

  private def validateName(name: String): Unit =
    if name.trim.isEmpty || name.contains('\u0000') || name.codePointCount(0, name.length) > 128 then
      throw InvalidSqlite("Environment name must be non-blank, without NUL bytes, and at most 128 characters")

  private def find(name: String): Option[SqliteEnvironment] =
    Using.resource(registry.prepareStatement("SELECT path, managed FROM environments WHERE name = ?")) { statement =>
      statement.setString(1, name)
      Using.resource(statement.executeQuery()) { rows =>
        Option.when(rows.next())(SqliteEnvironment(name, rows.getString(1), rows.getBoolean(2)))
      }
    }

  private def available(name: String): Unit =
    validateName(name)
    if find(name).nonEmpty then throw EnvironmentExists(name)

  private def insert(value: SqliteEnvironment): SqliteEnvironment =
    Using.resource(registry.prepareStatement("INSERT INTO environments(name, path, managed) VALUES (?, ?, ?)")) { statement =>
      statement.setString(1, value.name)
      statement.setString(2, value.path)
      statement.setBoolean(3, value.managed)
      statement.executeUpdate()
    }
    value

  def list: IO[Vector[SqliteEnvironment]] = serial {
    Using.resource(registry.createStatement()) { statement =>
      Using.resource(statement.executeQuery("SELECT name, path, managed FROM environments ORDER BY name")) { rows =>
        val values = Vector.newBuilder[SqliteEnvironment]
        while rows.next() do values += SqliteEnvironment(rows.getString(1), rows.getString(2), rows.getBoolean(3))
        values.result()
      }
    }
  }

  def add(name: String): IO[SqliteEnvironment] = serial {
    available(name)
    val path = directory.resolve("databases").resolve(s"${UUID.randomUUID()}.sqlite3")
    Files.createFile(path)
    try
      Using.resource(SqliteExecutor.open(path)) { connection =>
        Using.resource(connection.createStatement())(_.execute("PRAGMA user_version=0"))
      }
      insert(SqliteEnvironment(name, path.toString, true))
    catch
      case error: Throwable => Files.deleteIfExists(path); throw error
  }

  def importDatabase(name: String, file: String): IO[SqliteEnvironment] = serial {
    available(name)
    val input = Path.of(file)
    if !input.isAbsolute || !Files.isRegularFile(input) then throw InvalidSqlite("Import path must be an existing absolute database file on the server")
    val path = input.toRealPath()
    // Validate the file using a read-only connection, including live WAL data.
    Using.resource(SqliteExecutor.open(path, readOnly = true)) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery("PRAGMA schema_version"))(_.next())
      }
    }
    insert(SqliteEnvironment(name, path.toString, false))
  }

  /** Remove only the binding: files and in-flight executions retain their independent lifetimes. */
  def delete(name: String): IO[Unit] = serial {
    Using.resource(registry.prepareStatement("DELETE FROM environments WHERE name = ?")) { statement =>
      statement.setString(1, name)
      if statement.executeUpdate() == 0 then throw MissingEnvironment(name)
    }
  }

  def execute(env: String, sql: String): IO[ExecuteResult] =
    serial(find(env).getOrElse(throw MissingEnvironment(env)))
      .flatMap(environment => SqliteExecutor.execute(Path.of(environment.path), sql))

object SqliteService:
  def resource(directory: Path = groot.schedule.Store.defaultHome.resolve("sqlite")): Resource[IO, SqliteService] =
    val home = directory.toAbsolutePath.normalize()
    for
      _ <- Resource.make(IO.blocking {
        Files.createDirectories(home.resolve("databases"))
        val channel = FileChannel.open(home.resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try
          if channel.tryLock() == null then throw new IllegalStateException(s"A SQLite service already owns $home")
          channel
        catch
          case error: Throwable => channel.close(); throw error
      })(channel => IO.blocking(channel.close()))
      registry <- Resource.fromAutoCloseable(IO.blocking {
        val path = home.resolve("environments.sqlite3")
        if !Files.exists(path) then Files.createFile(path)
        SqliteExecutor.open(path)
      })
      _ <- Resource.eval(IO.blocking {
        Using.resource(registry.createStatement()) { statement =>
          statement.execute("PRAGMA journal_mode=WAL")
          val version = Using.resource(statement.executeQuery("PRAGMA user_version")) { row => row.next(); row.getInt(1) }
          require(version <= 1, s"Unsupported SQLite registry version: $version")
          statement.execute("CREATE TABLE IF NOT EXISTS environments (name TEXT PRIMARY KEY, path TEXT NOT NULL, managed INTEGER NOT NULL)")
          statement.execute("PRAGMA user_version=1")
        }
      })
      mutex <- Resource.eval(Mutex[IO])
    yield new SqliteService(home, registry, mutex)
