package groot.sqlite

import framework.model.JsonModel
import framework.validation.{Validator, contract}
import io.circe.Json

final case class SqliteConfig(
    @contract[Int](_ > 0) maxSqlBytes: Int = 1024 * 1024,
    @contract[Int](_ > 0) maxResultBytes: Int = 512 * 1024,
    @contract[Int](_ > 0) maxRows: Int = 10000,
    @contract[Int](_ > 0) busyTimeoutMs: Int = 5000
) derives JsonModel, Validator

case class SqliteEnvironment(name: String, path: String, managed: Boolean) derives JsonModel
case class StatementResult(columns: Vector[String], rows: Vector[Vector[Json]], changes: Long) derives JsonModel
case class ExecuteResult(results: Vector[StatementResult]) derives JsonModel

case class InvalidSqlite(message: String) extends RuntimeException(message)
case class MissingEnvironment(name: String) extends RuntimeException(s"No SQLite environment named $name")
case class EnvironmentExists(name: String) extends RuntimeException(s"SQLite environment already exists: $name")
case class SqliteResultLimit(message: String) extends RuntimeException(message)
