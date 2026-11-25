package groot.kv

import framework.model.JsonModel
import framework.validation.{Validator, contract}

final case class KvConfig(
    @contract[String](s => s != null && !s.contains('\u0000') && (s.isEmpty || s.trim.nonEmpty), "must not be blank or contain NUL")
    file: String = ""
) derives JsonModel, Validator

case class KvWrite(key: String, value: String) derives JsonModel
private[kv] case class InvalidKv(message: String) extends RuntimeException(message)
private[kv] case class KvLimit(message: String) extends RuntimeException(message)
private[kv] case class KvStorageError(message: String) extends RuntimeException(message)
