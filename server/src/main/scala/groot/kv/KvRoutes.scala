package groot.kv

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import java.io.IOException
import java.nio.file.InvalidPathException

object KvRoutes:
  def register(service: KvService)(using CommandsScope): Unit = commands("kv") {
    exposeOn(graaljs)
    description("Persist shared string key-value entries across GraalJS tasks, schedule cycles and server restarts")
    val invalid = response.json[ErrorBody](400) { description("Invalid kv key, value or configured path") }
    val large = response.json[ErrorBody](413) { description("Value exceeds 64 KiB of UTF-8") }
    val failed = response.json[ErrorBody](500) { description("KV storage read/write error") }
    recover {
      case error: InvalidKv => invalid(ErrorBody("invalid_kv", error.getMessage))
      case error: InvalidPathException => invalid(ErrorBody("invalid_kv", error.getMessage))
      case error: KvLimit => large(ErrorBody("kv_limit", error.getMessage))
      case error: KvStorageError => failed(ErrorBody("kv_storage_error", error.getMessage))
      case error: IOException => failed(ErrorBody("kv_io_error", Option(error.getMessage).getOrElse("KV I/O failed")))
    }
    command[Unit]("set") {
      description("Create or replace a string value; commit before returning; empty strings are preserved")
      val input = body[KvWrite]("Shared key (1 to 128 characters, no NUL) and string value (up to 64 KiB of UTF-8)")
      handle { service.set(input.value.key, input.value.value) }
    }
    command[Option[String]]("get") {
      description("Read a shared string value; return null if the key does not exist")
      val key = param[String]("key", "Shared key") { minLength(1); maxLength(128) }
      handle { service.get(key.value) }
    }
    command[Boolean]("clear") {
      description("Delete one shared key; return whether it existed; safe to repeat")
      val key = param[String]("key", "Shared key") { minLength(1); maxLength(128) }
      handle { service.clear(key.value) }
    }
  }
