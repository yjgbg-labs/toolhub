package groot.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.model.JsonModel
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.sql.{DriverManager, ResultSet}
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.sqlite.{ProgressHandler, SQLiteConfig, SQLiteConnection, SQLiteLimits, SQLiteOpenMode}
import scala.util.Using

private[sqlite] object SqliteExecutor:
  val maxSqlBytes = 1024 * 1024
  val maxResultBytes = 512 * 1024
  val maxRows = 10000

  /** URI encoding preserves literal ?, # and Unicode in filenames; missing databases are never created. */
  def open(path: Path, readOnly: Boolean = false): SQLiteConnection =
    Class.forName("org.sqlite.JDBC")
    val config = new SQLiteConfig
    config.setReadOnly(readOnly)
    config.resetOpenMode(SQLiteOpenMode.CREATE)
    config.setBusyTimeout(5000)
    config.enforceForeignKeys(true)
    DriverManager.getConnection(s"jdbc:sqlite:${path.toUri.toASCIIString}", config.toProperties).asInstanceOf[SQLiteConnection]

  def execute(path: Path, sql: String): IO[ExecuteResult] = IO.defer {
    if sql.getBytes(UTF_8).length > maxSqlBytes then throw InvalidSqlite("SQL exceeds 1 MiB")
    val statements = SqlStatements.split(sql)
    Resource.fromAutoCloseable(IO.blocking(open(path))).use { connection =>
      // A cancelled observer interrupts the native worker and waits for it before closing the connection.
      IO.uncancelable { poll =>
        val cancelled = new AtomicBoolean(false)
        for
          _ <- IO.blocking {
            connection.setLimit(SQLiteLimits.SQLITE_LIMIT_LENGTH, maxSqlBytes)
            ProgressHandler.setHandler(connection, 1000, new ProgressHandler {
              protected def progress(): Int = if cancelled.get() then 1 else 0
            })
          }
          worker <- IO.blocking(run(connection, statements, cancelled))
            .guarantee(IO.blocking(ProgressHandler.clearHandler(connection))).attempt.start
          result <- poll(worker.joinWithNever).onCancel(
            IO { cancelled.set(true); connection.getDatabase.interrupt() } *> worker.join.void)
          value <- IO.fromEither(result)
        yield value
      }
    }
  }

  private def run(connection: SQLiteConnection, statements: Vector[String], cancelled: AtomicBoolean): ExecuteResult =
    var bytes = 32L
    var count = 0
    def charge(size: Int): Unit =
      bytes += size
      if bytes > maxResultBytes then throw SqliteResultLimit("SQLite result exceeds 512 KiB; select fewer columns or rows")
    def checkCancelled(): Unit =
      if cancelled.get() then throw new InterruptedException("SQLite execution cancelled")
    val results = statements.map { sql =>
      checkCancelled()
      Using.resource(connection.createStatement()) { statement =>
        val before = connection.getDatabase.total_changes()
        val (columns, rows) = if statement.execute(sql) then
          Using.resource(statement.getResultSet) { result =>
            val metadata = result.getMetaData
            val columns = (1 to metadata.getColumnCount).map(metadata.getColumnLabel).toVector
            charge(Json.fromValues(columns.map(Json.fromString)).noSpaces.getBytes(UTF_8).length + 96)
            val rows = Vector.newBuilder[Vector[Json]]
            while result.next() do
              checkCancelled()
              count += 1
              if count > maxRows then throw SqliteResultLimit("SQLite result exceeds 10000 rows; use LIMIT")
              val row = columns.indices.map(index => cell(result, index + 1)).toVector
              charge(Json.fromValues(row).noSpaces.getBytes(UTF_8).length + 1)
              rows += row
            (columns, rows.result())
          }
        else
          charge(96)
          (Vector.empty[String], Vector.empty[Vector[Json]])
        StatementResult(columns, rows, connection.getDatabase.total_changes() - before)
      }
    }
    val result = ExecuteResult(results)
    if JsonModel[ExecuteResult].encoder(result).noSpaces.getBytes(UTF_8).length > maxResultBytes then
      throw SqliteResultLimit("SQLite result exceeds 512 KiB")
    result

  private def cell(rows: ResultSet, index: Int): Json = rows.getObject(index) match
    case null => Json.Null
    case value: java.lang.Integer => Json.fromInt(value.intValue())
    case value: java.lang.Long =>
      val number = value.longValue()
      if number >= -9007199254740991L && number <= 9007199254740991L then Json.fromLong(number)
      else Json.fromString(number.toString)
    case value: java.lang.Double =>
      if value.isNaN then Json.Null
      else if value.isInfinite then Json.fromString(value.toString)
      else Json.fromDoubleOrNull(value.doubleValue())
    case value: Array[Byte] => Json.obj("base64" -> Json.fromString(Base64.getEncoder.encodeToString(value)))
    case value: String => Json.fromString(value)
    case other => throw new IllegalStateException(s"Unsupported SQLite value: ${other.getClass.getName}")
