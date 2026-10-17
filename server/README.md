# Sprout DSL

`sprout.httpDsl` describes HTTP methods, paths, input locations and responses.
`sprout.rpcDsl` builds on it to describe commands. Both use Scala 3 context
functions and compile into the same HTTP contracts and OpenAPI document.

Each DSL has its own `httpApp` entry point. `httpDsl.httpApp` accepts
`AppScope ?=> Unit`; `rpcDsl.httpApp` wraps it and accepts
`(AppScope, CommandsScope) ?=> Unit`. Importing `sprout.rpcDsl.*` selects the RPC
entry point. The HTTP layer does not depend on RPC scope types.

## HTTP

```scala
import cats.effect.IO
import sprout.httpDsl.*

val app = httpApp {
  server { port(8080) }
  routes {
    group("/users") {
      get("/{id}") {
        description("Get a user ID")
        val id = path[Long]("id") { minimum(1L) }
        val ok = response.json[Long](200) {}
        handle { IO.pure(ok(id.value)) }
      }
    }
  }
}
```

`get`, `post`, `put`, `patch`, `delete`, `head` and `options` declare explicit
methods. `group` adds a path prefix. Inputs use `path`, `query`, `header`,
`cookie` and `body.json`; responses use `response.json`, `response.text` and
`response.empty`. HTTP handlers return `IO[EndpointResult]`.

## RPC

```scala
import cats.effect.IO
import sprout.rpcDsl.*

case class User(id: Long, name: String) derives JsonModel
case class CreateUser(name: String) derives JsonModel

val app = httpApp {
  commands("users") {
    description("User management")

    command[User]("get") {
      description("Get a user")
      val id = param[Long]("id", "User ID") { minimum(1L) }
      handle { IO.pure(User(id.value, "Ada")) }
    }

    command[User]("create") {
      description("Create a user")
      val id = param[Long]("id", "User ID") { minimum(1L) }
      val input = body[CreateUser]("User payload") {
        constraint[CreateUser]("Name must not be blank")(_.name.trim.nonEmpty)
      }
      handle { IO.pure(User(id.value, input.value.name)) }
    }
  }
}
```

- `rpcDsl.httpApp` provides both `AppScope` and the root `CommandsScope`.
  Declare commands, shared inputs and middleware directly in its body, alongside
  server and OpenAPI configuration.
- `commands("users")` creates a namespace and path prefix. Nested namespaces
  produce IDs such as `users.admin.list` and paths such as `/users/admin/list`.
- `command[A]` declares the HTTP 200 result type. Its handler returns `IO[A]`.
  `String` produces text, `Unit` an empty body, and other `JsonModel` types JSON.
- `param[A]("name", "description") { ... }` declares a query parameter.
  `default`, numeric/string constraints, optional values and repeated query
  values work as in the HTTP DSL. The configuration block is optional.
- `body[A]("description") { ... }` declares a JSON body with the same defaults
  and validation. The configuration block is optional.
- A command with a body uses POST; a command without one uses GET. This is based
  on the complete declaration, including inherited bodies declared after child
  commands. An optional/defaulted body still selects POST.
- `CommandScope[A] extends CommandsScope`: reusable parameter and middleware
  declarations accept `CommandsScope`; only `CommandScope[A]` permits `handle`.

## Documentation text

Use `description("...")` consistently for documentation text, including named
`commands` groups, individual commands, HTTP endpoints, inputs and responses.
It maps directly to the corresponding OpenAPI `description` field.
`param` and `body` accept their description directly as an argument.

The RPC DSL exposes neither `summary` nor `tag`; named `commands` groups generate
OpenAPI tags automatically. The HTTP DSL retains both methods for explicit HTTP
metadata. Business declarations and examples use `description` only.

## Middleware and errors

Both DSLs retain `middleware(Handler => Handler)`, shared declarations and
`Output.raise`. Inputs are decoded and validated before middleware. Middleware
nests from root to endpoint, with the first declaration outermost. A declared
response can short-circuit a typed command using `response.raise(payload)`.

### Non-200 responses

Declare additional responses with `response.json[A](status)`,
`response.text(status)` or `response.empty(status)`, then use the returned
output's `raise` method to return that response from a typed command:

```scala
import cats.effect.IO
import sprout.ErrorBody
import sprout.rpcDsl.*

val app = httpApp {
  commands("users") {
    val missing = response.json[ErrorBody](404) {
      description("User not found")
    }

    command[String]("name") {
      val id = param[Int]("id", "User ID")
      handle {
        if id.value == 1 then IO.pure("Ada")
        else missing.raise(ErrorBody("not_found", "User not found"))
      }
    }
  }
}
```

`command[String]` still defines only the HTTP 200 result. `missing.raise(...)`
short-circuits it with HTTP 404 and the declared JSON payload. Its return type
is `IO[Nothing]`, so it fits any command result type. For a text response use
`output.raise("message")`; for an empty response use `output.raise(())`.
The same mechanism supports other statuses, including 201 and 204.

Responses declared in `commands` are inherited by its child commands and can
also be used in middleware. Each declared response appears in OpenAPI with its
status, description and body schema where applicable.

Use `app.serve` to run, `app.resource` to manage the listener, `app.httpApp` for
in-process requests and `app.openApiJson` for the generated contract.

## Migration

Replace `sprout.dsl.*` with the relevant DSL import. HTTP routes retain their
explicit method and path APIs. For RPC, remove the outer `routes` or `commands()`
container; `httpApp` supplies the root command context. Rename `group/operation`
to `commands/command`, and use `param` and `body` for inputs.

Scheduled job names now use `?name=...` instead of a path segment. Consequently,
CLI calls use `sprout schedule log --name example`, and Code Mode/client inputs
use `{ query: { name: "example" } }`. The health endpoint is now `GET /health`.

Run server checks from the repository root with `groot jobs server-test`; use
`groot jobs integration-test` for the real server, CLI and plugin integration.
