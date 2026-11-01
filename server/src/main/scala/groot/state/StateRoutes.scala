package groot.state

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object StateRoutes:
  def register(service: StateService)(using CommandsScope): Unit = commands("state") {
    exposeOn(graaljs)
    description("Persist shared string state across GraalJS tasks, schedule cycles and server restarts")
    val invalid = response.json[ErrorBody](400) { description("Invalid state key or value") }
    val large = response.json[ErrorBody](413) { description("Value exceeds 64 KiB of UTF-8") }
    recover {
      case error: InvalidState => invalid(ErrorBody("invalid_state", error.getMessage))
      case error: StateLimit => large(ErrorBody("state_limit", error.getMessage))
    }
    command[Unit]("set") {
      description("Create or replace a string value; commit before returning; empty strings are preserved")
      val input = body[StateWrite]("Shared key (1 to 128 characters, no NUL) and string value (up to 64 KiB of UTF-8)")
      handle { service.set(input.value.key, input.value.value) }
    }
    command[Option[String]]("get") {
      description("Read a shared string value; return null if the key does not exist")
      val key = param[String]("key", "Shared state key") { minLength(1); maxLength(128) }
      handle { service.get(key.value) }
    }
    command[Boolean]("clear") {
      description("Delete one shared key; return whether it existed; safe to repeat")
      val key = param[String]("key", "Shared state key") { minLength(1); maxLength(128) }
      handle { service.clear(key.value) }
    }
  }
