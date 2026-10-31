package groot.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import io.circe.Json
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import org.http4s.{Request, Status, Uri}
import org.http4s.circe.*
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class SqliteSuite extends CatsEffectSuite:
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "sqlite-test-"))
  )(path => IO.blocking {
    val files = Files.walk(path)
    try files.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    finally files.close()
  })

  private def request(path: String, values: (String, String)*): Request[IO] =
    Request[IO](uri = Uri.unsafeFromString(path).withQueryParams(values.toMap))

  private def eventually(check: IO[Boolean]): IO[Unit] =
    check.flatMap(done => if done then IO.unit else IO.sleep(10.millis) *> eventually(check)).timeout(5.seconds)

  test("environments persist, names cannot escape storage, deletion preserves files, and duplicate ownership fails") {
    temporary.use { home =>
      SqliteService.resource(home).use { service =>
        for
          env <- service.add("../中文 / 'scratch'")
          _ = assertEquals(Path.of(env.path).getParent, home.resolve("databases"))
          _ = assert(env.managed)
          _ <- service.execute(env.name, "CREATE TABLE sample(value); INSERT INTO sample VALUES ('retained')")
          second <- SqliteService.resource(home).use(_ => IO.unit).attempt
          _ = assert(second.isLeft)
        yield env
      }.flatMap { original =>
        SqliteService.resource(home).use { service =>
          for
            entries <- service.list
            _ = assertEquals(entries, Vector(original))
            result <- service.execute(original.name, "SELECT value FROM sample")
            _ = assertEquals(result.results.head.rows, Vector(Vector(Json.fromString("retained"))))
            _ <- service.delete(original.name)
            _ = assert(Files.isRegularFile(Path.of(original.path)))
            absent <- service.execute(original.name, "SELECT 1").attempt
            _ = assert(absent.swap.toOption.get.isInstanceOf[MissingEnvironment])
            replacement <- service.add(original.name)
            _ = assertNotEquals(replacement.path, original.path)
            imported <- service.importDatabase("restored", original.path)
            _ = assert(!imported.managed)
            restored <- service.execute("restored", "SELECT value FROM sample")
          yield assertEquals(restored, result)
        }
      }
    }
  }

  test("concurrent registry updates preserve entries and duplicate names never replace a database") {
    temporary.use { home => SqliteService.resource(home).use { service =>
      for
        attempts <- (1 to 12).toList.parTraverse(_ => service.add("same").attempt)
        _ = assertEquals(attempts.count(_.isRight), 1)
        _ = assert(attempts.collect { case Left(error) => error }.forall(_.isInstanceOf[EnvironmentExists]))
        _ <- (1 to 12).toList.parTraverse_(n => service.add(s"env$n"))
        entries <- service.list
        invalid <- List("", " ", "x\u0000y", "x" * 129).traverse(name => service.add(name).attempt)
      yield
        assertEquals(entries.size, 13)
        assertEquals(entries.map(_.name), entries.map(_.name).sorted)
        assert(invalid.forall(_.isLeft))
        val files = Files.list(home.resolve("databases"))
        try assertEquals(files.count(), 13L) finally files.close()
    } }
  }

  test("import references live WAL databases and preserves literal filename characters") {
    temporary.use { home =>
      val path = home.resolve("external ?# 中文.sqlite3")
      val database = Resource.fromAutoCloseable(IO.blocking { Files.createFile(path); SqliteExecutor.open(path) })
      (database, SqliteService.resource(home.resolve("service"))).tupled.use { (writer, service) =>
        def write(sql: String): IO[Unit] = IO.blocking(Using.resource(writer.createStatement())(_.executeUpdate(sql))).void
        for
          _ <- IO.blocking(Using.resource(writer.createStatement())(_.execute("PRAGMA journal_mode=WAL")))
          _ <- write("CREATE TABLE live(value); INSERT INTO live VALUES (1)")
          env <- service.importDatabase("live", path.toString)
          _ = assertEquals(env.path, path.toRealPath().toString)
          _ <- write("INSERT INTO live VALUES (2)")
          result <- service.execute("live", "SELECT value FROM live ORDER BY value")
          _ = assertEquals(result.results.head.rows, Vector(Vector(Json.fromInt(1)), Vector(Json.fromInt(2))))
          _ <- service.execute("live", "INSERT INTO live VALUES (3)")
          visible <- IO.blocking(Using.resource(writer.createStatement()) { statement =>
            Using.resource(statement.executeQuery("SELECT count(*) FROM live")) { rows => rows.next(); rows.getInt(1) }
          })
          _ = assertEquals(visible, 3)
          _ <- service.delete("live")
        yield assert(Files.exists(path))
      }
    }
  }

  test("invalid imports leave no binding and deleted database files are not silently recreated") {
    temporary.use { home => SqliteService.resource(home.resolve("service")).use { service =>
      val invalid = home.resolve("not-sqlite")
      for
        _ <- IO.blocking(Files.writeString(invalid, "definitely not a SQLite database"))
        attempts <- List("relative.db", home.toString, home.resolve("missing.db").toString, invalid.toString)
          .traverse(path => service.importDatabase("invalid", path).attempt)
        entries <- service.list
        _ = assert(attempts.forall(_.isLeft))
        _ = assertEquals(entries, Vector.empty)
        env <- service.add("gone")
        _ <- IO.blocking(Files.delete(Path.of(env.path)))
        missing <- service.execute("gone", "SELECT 1").attempt
      yield
        assert(missing.isLeft)
        assert(!Files.exists(Path.of(env.path)))
    } }
  }

  test("SQL results preserve null, numeric and text values, blobs, large integers and duplicate column names") {
    temporary.use { home => SqliteService.resource(home).use { service =>
      for
        _ <- service.add("types")
        result <- service.execute("types", """SELECT NULL AS absent, -7 AS n, 1.25 AS real, '中文🙂' AS text,
          X'00ff' AS binary, 9223372036854775807 AS large, -9223372036854775808 AS negative,
          9007199254740991 AS safe, 1 AS dup, 2 AS dup; SELECT 1 WHERE 0;""")
      yield
        val first = result.results.head
        assertEquals(first.columns, Vector("absent", "n", "real", "text", "binary", "large", "negative", "safe", "dup", "dup"))
        assertEquals(first.rows, Vector(Vector(Json.Null, Json.fromInt(-7), Json.fromDoubleOrNull(1.25), Json.fromString("中文🙂"),
          Json.obj("base64" -> Json.fromString("AP8=")), Json.fromString("9223372036854775807"), Json.fromString("-9223372036854775808"),
          Json.fromLong(9007199254740991L), Json.fromInt(1), Json.fromInt(2))))
        assertEquals(first.changes, 0L)
        assertEquals(result.results(1), StatementResult(Vector("1"), Vector.empty, 0L))
        assertEquals(JsonModel[ExecuteResult].decoder.decodeJson(JsonModel[ExecuteResult].encoder(result)), Right(result))
    } }
  }

  test("SQL scripts retain quoted semicolons, comments, trigger bodies, RETURNING and explicit transactions") {
    temporary.use { home => SqliteService.resource(home).use { service =>
      for
        _ <- service.add("scripts")
        result <- service.execute("scripts", s"""
          -- ignored ; END;
          CREATE TABLE data(id INTEGER PRIMARY KEY, value TEXT);
          CREATE TABLE audit(value TEXT);
          ${'\ufeff'}CREATE TRIGGER track AFTER INSERT ON data BEGIN
            INSERT INTO audit VALUES (CASE WHEN NEW.value = 'a;''b' THEN 'yes' ELSE 'no' END);
            /* ; END; */ INSERT INTO audit VALUES ('trigger;value');
          END;
          BEGIN;
          INSERT INTO data(value) VALUES ('a;''b') RETURNING id, value;
          COMMIT;
          SELECT value FROM audit;
          SELECT 'a;''b' AS "double;quote", 2 AS [bracket;quote], 3 AS `backtick;quote`;
          -- trailing comment without a newline""")
        failed <- service.execute("scripts", "BEGIN; INSERT INTO data(value) VALUES ('rollback'); SELECT * FROM missing_table").attempt
        rows <- service.execute("scripts", "SELECT count(*) FROM data")
        partial <- service.execute("scripts", "INSERT INTO data(value) VALUES ('committed'); SELECT * FROM missing_table").attempt
        after <- service.execute("scripts", "SELECT count(*) FROM data; VACUUM;")
      yield
        assertEquals(result.results.size, 8)
        assertEquals(result.results(4).rows, Vector(Vector(Json.fromInt(1), Json.fromString("a;'b"))))
        assertEquals(result.results(4).changes, 3L)
        assertEquals(result.results(6).rows, Vector(Vector(Json.fromString("yes")), Vector(Json.fromString("trigger;value"))))
        assertEquals(result.results(7).columns, Vector("double;quote", "bracket;quote", "backtick;quote"))
        assert(failed.isLeft && partial.isLeft)
        assertEquals(rows.results.head.rows, Vector(Vector(Json.fromInt(1))))
        assertEquals(after.results.head.rows, Vector(Vector(Json.fromInt(2))))
    } }
  }

  test("HTTP and the SDK expose all commands, schemas and structured errors") {
    temporary.use { home => SqliteService.resource(home).use { service =>
      val app = httpApp { SqliteRoutes.register(service) }
      val doc = io.circe.parser.parse(app.openApiJson).toOption.get
      val paths = doc.hcursor.downField("paths").focus.get.asObject.get
      assertEquals(paths.size, 5)
      paths.values.foreach(path => assertEquals(path.hcursor.downField("get").get[List[String]]("x-expose-on"), Right(List("cli", "codemode", "graaljs"))))
      val commands = groot.graaljs.GraalCommandSdk(app)
      assertEquals(commands.commands, Vector("sqlite.env.add", "sqlite.env.delete", "sqlite.env.import", "sqlite.env.list", "sqlite.execute"))
      for
        added <- app.httpApp(request("/sqlite/env/add", "name" -> "http"))
        duplicate <- app.httpApp(request("/sqlite/env/add", "name" -> "http"))
        absent <- app.httpApp(request("/sqlite/execute", "env" -> "missing", "sql" -> "SELECT 1"))
        invalid <- app.httpApp(request("/sqlite/execute", "env" -> "http", "sql" -> "bad sql"))
        empty <- app.httpApp(request("/sqlite/execute", "env" -> "http", "sql" -> "-- empty"))
        _ <- service.execute("http", "CREATE TABLE unique_values(value UNIQUE); INSERT INTO unique_values VALUES (1)")
        constraint <- app.httpApp(request("/sqlite/execute", "env" -> "http", "sql" -> "INSERT INTO unique_values VALUES (1)"))
        sdk <- commands.call("sqlite.execute", Json.obj("env" -> Json.fromString("http"), "sql" -> Json.fromString("SELECT value FROM unique_values")))
        list <- app.httpApp(request("/sqlite/env/list")).flatMap(_.as[Json])
        deleted <- app.httpApp(request("/sqlite/env/delete", "name" -> "http"))
        missing <- app.httpApp(request("/sqlite/env/delete", "name" -> "http"))
      yield
        assertEquals(added.status, Status.Ok)
        assertEquals(duplicate.status, Status.Conflict)
        assertEquals(absent.status, Status.NotFound)
        assertEquals(invalid.status, Status.BadRequest)
        assertEquals(empty.status, Status.BadRequest)
        assertEquals(constraint.status, Status.Conflict)
        assertEquals(sdk.hcursor.get[Int]("status"), Right(200))
        assertEquals(sdk.hcursor.downField("body").downField("results").downArray.downField("rows").focus, Some(Json.arr(Json.arr(Json.fromInt(1)))))
        assertEquals(list.asArray.get.size, 1)
        assertEquals(deleted.status, Status.Ok)
        assertEquals(missing.status, Status.NotFound)
    } }
  }

  test("row and byte limits reject oversized results and leave subsequent requests usable") {
    temporary.use { home => SqliteService.resource(home).use { service =>
      val app = httpApp { SqliteRoutes.register(service) }
      for
        _ <- service.add("limits")
        rows <- app.httpApp(request("/sqlite/execute", "env" -> "limits", "sql" ->
          "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<10001) SELECT x FROM n"))
        bytes <- app.httpApp(request("/sqlite/execute", "env" -> "limits", "sql" -> "SELECT zeroblob(500000)"))
        good <- service.execute("limits", "SELECT 42")
        bad <- List("SELECT 'unterminated", "SELECT 1 /* unterminated", "SELECT 1\u0000", "x" * (SqliteExecutor.maxSqlBytes + 1))
          .traverse(sql => service.execute("limits", sql).attempt)
      yield
        assertEquals(rows.status, Status.PayloadTooLarge)
        assertEquals(bytes.status, Status.PayloadTooLarge)
        assertEquals(good.results.head.rows, Vector(Vector(Json.fromInt(42))))
        assert(bad.forall(_.isLeft))
    } }
  }

  test("cancelling native SQL waits for the worker and releases an uncommitted write lock") {
    temporary.use { home => SqliteService.resource(home).use { service =>
      for
        env <- service.add("cancel")
        _ <- service.execute(env.name, "CREATE TABLE values_table(value)")
        _ <- Resource.make(service.execute(env.name, """BEGIN IMMEDIATE; INSERT INTO values_table VALUES (1);
          WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT sum(x) FROM n""").start)(_.cancel).use { worker =>
          val locked = IO.blocking {
            Using.resource(SqliteExecutor.open(Path.of(env.path))) { connection =>
              connection.setBusyTimeout(0)
              Using.resource(connection.createStatement()) { statement =>
                try
                  statement.execute("BEGIN IMMEDIATE")
                  statement.execute("ROLLBACK")
                  false
                catch case error: org.sqlite.SQLiteException if (error.getResultCode.code & 0xff) == 5 => true
              }
            }
          }
          eventually(locked) *> worker.cancel.timeout(5.seconds)
        }
        result <- service.execute(env.name, "BEGIN IMMEDIATE; SELECT count(*) FROM values_table; COMMIT")
      yield assertEquals(result.results(1).rows, Vector(Vector(Json.fromInt(0))))
    } }
  }
