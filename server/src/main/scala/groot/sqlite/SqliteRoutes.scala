package groot.sqlite

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import org.sqlite.SQLiteException

object SqliteRoutes:
  def register(service: SqliteService)(using CommandsScope): Unit = commands("sqlite") {
    description("Manage named SQLite databases and execute SQL on the server")
    exposeOn(cli, codemode, graaljs)
    val invalid = response.json[ErrorBody](400) { description("Invalid environment, database file or SQL") }
    val missing = response.json[ErrorBody](404) { description("Environment not found") }
    val conflict = response.json[ErrorBody](409) { description("Environment already exists or SQL constraint failed") }
    val large = response.json[ErrorBody](413) { description("SQL result exceeds the response limits") }
    val busy = response.json[ErrorBody](503) { description("Database is busy or locked") }
    recover {
      case error: InvalidSqlite => invalid(ErrorBody("invalid_sqlite", error.getMessage))
      case error: MissingEnvironment => missing(ErrorBody("not_found", error.getMessage))
      case error: EnvironmentExists => conflict(ErrorBody("environment_exists", error.getMessage))
      case error: SqliteResultLimit => large(ErrorBody("result_limit", error.getMessage))
      case error: SQLiteException => (error.getResultCode.code & 0xff) match
        case 5 | 6 => busy(ErrorBody("sqlite_busy", error.getMessage))
        case 19 => conflict(ErrorBody("sqlite_constraint", error.getMessage))
        case 18 => large(ErrorBody("result_limit", error.getMessage))
        case _ => invalid(ErrorBody("sqlite_error", error.getMessage))
      case error: java.nio.file.InvalidPathException => invalid(ErrorBody("invalid_path", error.getMessage))
      case error: java.io.IOException => invalid(ErrorBody("database_io", error.getMessage))
    }

    commands("env") {
      description("Create and register database environments; deleting an environment preserves its file")
      command[Vector[SqliteEnvironment]]("list") {
        description("List registered environment names and database paths")
        handle { service.list }
      }
      command[SqliteEnvironment]("add") {
        description("Create an empty managed SQLite database and register its name")
        val name = param[String]("name", "Environment name") { minLength(1); maxLength(128) }
        handle { service.add(name.value) }
      }
      command[SqliteEnvironment]("import") {
        description("Register an existing SQLite file in place, including live WAL data")
        val name = param[String]("name", "Environment name") { minLength(1); maxLength(128) }
        val path = param[String]("path", "Absolute existing database path on the server") { minLength(1) }
        handle { service.importDatabase(name.value, path.value) }
      }
      command[Unit]("delete") {
        description("Remove an environment binding while retaining the database file")
        val name = param[String]("name", "Environment name") { minLength(1) }
        handle { service.delete(name.value) }
      }
    }

    command[ExecuteResult]("execute") {
      description("Execute SQL statements on one connection and return each result; transactions can be explicit")
      val env = param[String]("env", "Registered environment name") { minLength(1) }
      val sql = param[String]("sql", "SQL statement or script; rows are positional, blobs use base64, large integers use decimal strings") { minLength(1) }
      handle { service.execute(env.value, sql.value) }
    }
  }
