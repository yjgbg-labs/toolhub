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
      val id = param[Long]("id", "User ID") { minimum(1L) }
      handle { IO.pure(User(id.value, "Ada")) }
    }

    command[User]("create") {
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

## Middleware and errors

Both DSLs retain `middleware(Handler => Handler)`, shared declarations and
`Output.raise`. Inputs are decoded and validated before middleware. Middleware
nests from root to endpoint, with the first declaration outermost. A declared
response can short-circuit a typed command using `response.raise(payload)`.

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
