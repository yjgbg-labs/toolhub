package groot.sqlite

import framework.model.JsonModel
import io.circe.Json

case class SqliteEnvironment(name: String, path: String, managed: Boolean) derives JsonModel
case class StatementResult(columns: Vector[String], rows: Vector[Vector[Json]], changes: Long) derives JsonModel
case class ExecuteResult(results: Vector[StatementResult]) derives JsonModel

case class InvalidSqlite(message: String) extends RuntimeException(message)
case class MissingEnvironment(name: String) extends RuntimeException(s"No SQLite environment named $name")
case class EnvironmentExists(name: String) extends RuntimeException(s"SQLite environment already exists: $name")
case class SqliteResultLimit(message: String) extends RuntimeException(message)
