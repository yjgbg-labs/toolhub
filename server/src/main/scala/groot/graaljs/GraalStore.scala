package groot.graaljs

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.model.JsonModel
import fs2.{Chunk, Stream}
import io.circe.parser.parse
import java.io.RandomAccessFile
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.sql.{Connection, DriverManager}
import scala.concurrent.duration.*
import scala.util.Using

/** SQLite owns task metadata and source snapshots; output is an append-only local file. */
private[graaljs] final class GraalStore(val directory: Path, connection: Connection):
  private val model = JsonModel[GraalTask]
  private def sql[A](f: Connection => A): IO[A] = IO.blocking(connection.synchronized(f(connection)))
  private def decode(text: String): GraalTask =
    parse(text).flatMap(model.decoder.decodeJson).fold(throw _, identity)

  def logPath(id: String): Path = directory.resolve("logs").resolve(s"$id.log")

  def insert(task: GraalTask, source: String): IO[Unit] = sql { db =>
    Using.resource(db.prepareStatement("INSERT INTO tasks(id, state, task_json, source) VALUES (?, ?, ?, ?)")) { statement =>
      statement.setString(1, task.id)
      statement.setString(2, task.state)
      statement.setString(3, model.encoder(task).noSpaces)
      statement.setString(4, source)
      statement.executeUpdate()
      ()
    }
  }

  def save(task: GraalTask): IO[Unit] = sql { db =>
    Using.resource(db.prepareStatement("UPDATE tasks SET state = ?, task_json = ? WHERE id = ?")) { statement =>
      statement.setString(1, task.state)
      statement.setString(2, model.encoder(task).noSpaces)
      statement.setString(3, task.id)
      if statement.executeUpdate() != 1 then throw MissingGraalTask(task.id)
    }
  }

  def get(id: String): IO[GraalTask] = sql { db =>
    Using.resource(db.prepareStatement("SELECT task_json FROM tasks WHERE id = ?")) { statement =>
      statement.setString(1, id)
      Using.resource(statement.executeQuery()) { rows =>
        if !rows.next() then throw MissingGraalTask(id)
        decode(rows.getString(1))
      }
    }
  }

  def source(id: String): IO[String] = sql { db =>
    Using.resource(db.prepareStatement("SELECT source FROM tasks WHERE id = ?")) { statement =>
      statement.setString(1, id)
      Using.resource(statement.executeQuery()) { rows =>
        if !rows.next() then throw MissingGraalTask(id)
        rows.getString(1)
      }
    }
  }

  def active: IO[List[GraalTask]] = sql { db =>
    Using.resource(db.createStatement()) { statement =>
      Using.resource(statement.executeQuery("SELECT task_json FROM tasks WHERE state IN ('queued', 'running', 'cancelling') ORDER BY seq")) { rows =>
        val result = List.newBuilder[GraalTask]
        while rows.next() do result += decode(rows.getString(1))
        result.result()
      }
    }
  }

  def createLog(id: String): IO[Unit] = IO.blocking { Files.createFile(logPath(id)); () }

  def appendLog(id: String, text: String): IO[Unit] = IO.blocking {
    Files.writeString(logPath(id), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    ()
  }

  def logs(id: String): IO[String] = get(id) *> IO.blocking {
    val bytes = Files.readAllBytes(logPath(id))
    val chars = CharBuffer.allocate(bytes.length)
    // A concurrent writer may currently be halfway through a UTF-8 character.
    // Preserve readable history if a crash interrupted a character before recovery appended its summary.
    UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
      .decode(ByteBuffer.wrap(bytes), chars, false)
    chars.flip().toString
  }

  /** Each follower has its own file position. A terminal-state observation is followed by a final drain. */
  def follow(id: String): Stream[IO, String] =
    Stream.resource(Resource.fromAutoCloseable(IO.blocking(new RandomAccessFile(logPath(id).toFile, "r")))).flatMap { file =>
      def read: IO[Array[Byte]] = IO.blocking {
        val buffer = new Array[Byte](8192)
        val count = file.read(buffer)
        if count <= 0 then Array.emptyByteArray else java.util.Arrays.copyOf(buffer, count)
      }
      def drain: Stream[IO, Byte] = Stream.repeatEval(read).takeWhile(_.nonEmpty).flatMap(bytes => Stream.chunk(Chunk.array(bytes)))
      def loop: Stream[IO, Byte] = Stream.eval(read).flatMap { bytes =>
        if bytes.nonEmpty then Stream.chunk(Chunk.array(bytes)) ++ loop
        else Stream.eval(get(id)).flatMap { task =>
          if task.terminal then drain
          else Stream.sleep_[IO](100.millis) ++ loop
        }
      }
      loop.through(fs2.text.utf8.decode).through(fs2.text.lines)
    }

private[graaljs] object GraalStore:
  def defaultHome: Path = groot.schedule.Store.defaultHome.resolve("graaljs")

  def resource(directory: Path): Resource[IO, GraalStore] =
    for
      _ <- Resource.make(IO.blocking {
        Files.createDirectories(directory.resolve("logs"))
        val channel = FileChannel.open(directory.resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try
          if channel.tryLock() == null then throw new IllegalStateException(s"A GraalJS manager already owns $directory")
          channel
        catch
          case error: Throwable => channel.close(); throw error
      })(channel => IO.blocking(channel.close()))
      connection <- Resource.fromAutoCloseable(IO.blocking {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection(s"jdbc:sqlite:${directory.resolve("tasks.sqlite3").toAbsolutePath}")
      })
      _ <- Resource.eval(IO.blocking {
        Using.resource(connection.createStatement()) { statement =>
          statement.execute("PRAGMA busy_timeout=5000")
          statement.execute("PRAGMA journal_mode=WAL")
          statement.execute("PRAGMA synchronous=FULL")
          val version = Using.resource(statement.executeQuery("PRAGMA user_version")) { row => row.next(); row.getInt(1) }
          require(version <= 1, s"Unsupported GraalJS database version: $version")
          statement.execute("""CREATE TABLE IF NOT EXISTS tasks (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            id TEXT NOT NULL UNIQUE,
            state TEXT NOT NULL,
            task_json TEXT NOT NULL,
            source TEXT NOT NULL
          )""")
          statement.execute("CREATE INDEX IF NOT EXISTS tasks_state_seq ON tasks(state, seq)")
          statement.execute("PRAGMA user_version=1")
          ()
        }
      })
    yield new GraalStore(directory, connection)
