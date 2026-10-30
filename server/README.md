# Framework DSL

`framework.httpDsl` describes HTTP methods, paths, input locations and responses.
`framework.rpcDsl` builds on it to describe commands. Both use Scala 3 context
functions and compile into the same HTTP contracts and OpenAPI document.

Each DSL has its own `httpApp` entry point. `httpDsl.httpApp` accepts
`AppScope ?=> Unit`; `rpcDsl.httpApp` wraps it and accepts
`(AppScope, CommandsScope) ?=> Unit`. Importing `framework.rpcDsl.*` selects the RPC
entry point. The HTTP layer does not depend on RPC scope types.

## Package structure

```text
framework/
├── httpDsl.scala
├── rpcDsl.scala
├── model/
│   ├── JsonModel.scala
│   ├── Schema.scala
│   └── ModelMacros.scala
├── http/
│   ├── Scopes.scala
│   ├── Declarations.scala
│   ├── Contract.scala
│   ├── Exchange.scala
│   ├── Compiler.scala
│   ├── ParamCodec.scala
│   └── OpenApi.scala
├── rpc/
│   ├── Scopes.scala
│   ├── Metadata.scala
│   ├── PlainText.scala
│   ├── Shell.scala
│   ├── SSE.scala
│   └── SuccessResponse.scala
└── runtime/
    └── Application.scala
```

- `model` owns JSON codecs, schema definitions and derivation, independently of
  HTTP, RPC and the runtime.
- `http` owns configuration scopes, flat frozen declarations, endpoint contracts,
  parameter validation, middleware binding and OpenAPI generation.
  `Exchange.scala` contains the public input/output and handler types, including
  `ErrorBody` and `FieldError`; settings live in `Declarations.scala`.
- `rpc` contains command scopes and HTTP 200 result conventions, using the HTTP
  and model packages. The RPC DSL turns command nesting into complete HTTP paths,
  operation IDs and shared declaration scopes before the HTTP compiler runs.
  `Metadata.scala` owns RPC group descriptions. HTTP and
  runtime carry generic OpenAPI extensions without depending on the RPC model.
- `runtime` executes HTTP contracts and manages TCP listeners. It depends
  on `http` and `model`, rather than the DSL entry points.

The two root DSL objects compose these packages. For explicit type imports use
`framework.model.JsonModel`, `framework.http.ErrorBody`,
`framework.rpc.CommandsScope` and `framework.runtime.Application`.

## HTTP

```scala
import cats.effect.IO
import framework.httpDsl.*

val app = httpApp {
  server { port(18080) }
  routes {
    get("/users/{id}") {
      operationId("users.get")
      description("Get a user ID")
      val id = path[Long]("id") { minimum(1L) }
      val ok = response.json[Long](200) {}
      handle { IO.pure(ok(id.value)) }
    }
  }
}
```

`get`, `post`, `put`, `patch`, `delete`, `head` and `options` declare explicit
methods and complete absolute paths. `get()` defaults to `/`.
`operationId("users.get")` sets the OpenAPI operation ID independently of the
path. When omitted, an ID is derived from the HTTP method and path.

`routes` is a flat collection: inputs, responses, tags and middleware declared
there apply to all of its endpoints, including declarations made after an
endpoint. Multiple `routes` blocks have independent shared declarations.
Inputs use `path`, `query`, `header`, `cookie` and `body.json`; responses use
`response.json`, `response.text` and `response.empty`. HTTP handlers return
`IO[EndpointResult]`.

## RPC

```scala
import cats.effect.IO
import framework.rpcDsl.*

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
- Both `commands` and `command` take one name matching `[A-Za-z][A-Za-z0-9_-]*`.
  The nesting determines the path and operationId; names cannot contain dots
  or slashes. For example, `commands("users") { command[String]("get") { ... } }`
  produces `/users/get` and `users.get`. A root `command[PlainText]("health")`
  produces `/health` and `health`. Commands are leaves and cannot contain groups
  or other commands.
- `command[A]` declares the HTTP 200 result type. Its handler returns `IO[A]`.
  `PlainText` produces `text/plain`, `Shell` an `application/x-sh` script,
  `Unit` an empty body, `SSE[A]` an event stream, and `JsonModel` types
  (including ordinary `String`) JSON.
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

### Plain text and JSON strings

`PlainText` is an opaque `String` subtype (`opaque type PlainText <: String = String`).
Construct it explicitly with `PlainText(value)`; it can be used wherever a `String`
is expected, but a `String` does not implicitly become a `PlainText`.

```scala
command[PlainText]("health") { handle { IO.pure(PlainText("ok")) } }
command[String]("label") { handle { IO.pure("ok") } }
```

`health` returns the bytes `ok` as `text/plain`; `label` returns `"ok"` as
`application/json`. The health and scheduler log endpoints use `PlainText`.

### Client-side shell scripts

The CLI automatically supplies any declared string parameter named `cwd` from
its current working directory, without exposing a user-editable CLI argument:

```scala
val cwd = param[String]("cwd", "Caller working directory")
```

This uses ordinary OpenAPI parameters. File discovery and parsing belong to the
server-side command handler.

`Shell` is an explicitly constructed opaque `String` subtype:
`opaque type Shell <: String = String`. It is exported by `framework.rpcDsl.*`.

```scala
command[Shell]("local-info") {
  handle { IO.pure(Shell("printf 'Client directory: '; pwd")) }
}
```

The server returns the script unchanged with `Content-Type: application/x-sh`;
OpenAPI describes it as a string. Running `groot-cli local-info` executes the
complete successful response locally with `/bin/sh -c`, inheriting the CLI's
working directory, environment and standard input/output/error. Output appears
as the script runs, and the CLI exits with the script's exit status.

Execution is selected from the actual response media type, including headers
with charset parameters. The HTTP response must finish successfully before
execution starts. Plain text, JSON, SSE events, HTTP errors and OpenAPI discovery
responses are never interpreted as executable scripts.

### Server-sent events

`SSE[A]` wraps `fs2.Stream[IO, A]`. A `SuccessResponse[SSE[A]]` is available when
`A` has a non-streaming `SuccessResponse`; nested SSE streams are rejected.

```scala
import cats.effect.IO
import fs2.Stream
import framework.rpcDsl.*
import scala.concurrent.duration.*

case class Tick(value: Long) derives JsonModel

val app = httpApp {
  command[SSE[Tick]]("ticks") {
    handle { IO.pure(SSE(Stream.awakeEvery[IO](1.second).zipWithIndex.map { case (_, n) => Tick(n) })) }
  }
  command[SSE[PlainText]]("messages") {
    handle { IO.pure(SSE(Stream.emits(List[PlainText](PlainText("hello"), PlainText("world"))).covary[IO])) }
  }
}
```

When building plaintext collections or streams, retain the element type explicitly
(for example, `List[PlainText](...)` or `.map[PlainText](PlainText(_))`) so Scala
does not widen it to `String`.

Responses use `text/event-stream` with `Cache-Control: no-cache`. Every element
becomes one standard SSE event, terminated by a blank line:

```text
data: {"value":0}

data: {"value":1}

```

JSON values use compact JSON; ordinary `String` values are quoted JSON strings.
`PlainText` is unquoted, and embedded CR/LF line endings are normalized into
multiple `data:` lines within the same event. Empty text or `Unit` produces an
empty data event. OpenAPI 3.1 describes the wire body as a string under
`text/event-stream`, not a JSON array.

The stream is consumed lazily with backpressure, without collecting it into
memory. Put resource acquisition/finalizers inside the stream so cancellation
and client disconnect release them. Handler errors before the response is
returned can be recovered normally. Once body streaming starts, failures
terminate the stream; handle them in the source stream if an error event is needed.

## Documentation text

Use `description("...")` consistently for documentation text, including named
`commands` groups, individual commands, HTTP endpoints, inputs and responses.
It maps to the corresponding `description` field; group descriptions are
published in the `x-rpc` extension described below.
`param` and `body` accept their description directly as an argument.

The RPC DSL exposes neither `summary` nor `tag`. Named `commands` groups publish
their descriptions through the `x-rpc` extension. The HTTP DSL provides `summary`
and `tag` for standard HTTP/OpenAPI metadata. Business declarations and examples
use `description` only.

## RPC group descriptions

`operationId` is the dot-separated command path: `users.admin.get` means
`users admin get`. The top-level `x-rpc` extension supplies descriptions for
the groups in that hierarchy:

```json
{
  "x-rpc": {
      "commands": [
      { "name": "users", "description": "User management" },
      { "name": "users.admin", "description": "Administrative operations" }
    ]
  }
}
```

- `commands` corresponds to DSL `commands(...)` declarations: an array of unique
  dot-separated group names and optional
  descriptions. Reopened DSL groups merge consistent descriptions, including
  descriptions declared after their commands.
- CLI group help and plugin namespace descriptions read `x-rpc.commands`.
  OpenAPI tags are independent HTTP classifications.
- Command discovery and tool names are derived from `operationId`. The extension
  only supplies group documentation; it contains no command-to-operation mapping.
- Without `x-rpc`, commands remain available with no group descriptions.

Code Mode tools use the command path directly: `schedule.config.read` becomes
`tools.schedule.config.read()`, and a root command such as `health` becomes
`tools.health()`. The plugin preserves dotted namespaces and their descriptions,
with no additional prefix or namespace override.

## Middleware and errors

Both DSLs retain `middleware(Handler => Handler)`, shared declarations and
`Output.raise`. Inputs are decoded and validated before middleware. Middleware
nests from root to endpoint, with the first declaration outermost. A declared
response can short-circuit a typed command using `response.raise(payload)`.

The RPC DSL also provides
`recover(handler: RequestContext ?=> PartialFunction[Throwable, EndpointResult])(using CommandsScope)`:

```scala
commands("users") {
  val missing = response.json[framework.http.ErrorBody](404) {}
  recover {
    case error: NoSuchElementException =>
      missing(framework.http.ErrorBody("not_found", error.getMessage))
  }
  command[Int]("get") { handle { IO.raiseError(new NoSuchElementException("missing user")) } }
}
```

Recovery inherits the same scope, ordering and request context as middleware,
including access to parameter `.value`. The partial function returns a declared
response value (not an `IO`); unmatched exceptions propagate to outer recovery
and ultimately the framework's HTTP 500 response. Deliberately raised responses
are already successful results when they reach recovery.

### Non-200 responses

Declare additional responses with `response.json[A](status)`,
`response.text(status)` or `response.empty(status)`, then use the returned
output's `raise` method to return that response from a typed command:

```scala
import cats.effect.IO
import framework.http.ErrorBody
import framework.rpcDsl.*

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

Replace legacy `sprout` DSL imports with `framework.httpDsl.*` or
`framework.rpcDsl.*`, and import types from the packages above. HTTP routes
retain their explicit method and path APIs. For RPC, remove the outer `routes`
or `commands()` container; `httpApp` supplies the root command context. Rename
`group/operation` to `commands/command`, and use `param` and `body` for inputs.
Replace dotted shorthand such as `command[A]("users.get")` with
`commands("users") { command[A]("get") { ... } }`.
Clients should read `x-rpc.commands` instead of OpenAPI tags for group descriptions.
The TypeScript catalog exposes these through `groupDescriptions`; command paths
continue to come from `operationId.split(".")`.

For HTTP, flatten former `group` path prefixes into each endpoint's full path:
`group("/users") { get("/{id}") { ... } }` becomes
`get("/users/{id}") { operationId("users.get"); ... }`. Put shared declarations
in a `routes` block containing the relevant endpoints. Nested grouping belongs
to RPC `commands`.

Scheduled job names now use `?name=...` instead of a path segment. Consequently,
CLI calls use `groot-cli schedule log --name example`, and Code Mode/client inputs
use `{ query: { name: "example" } }`. The health endpoint is now `GET /health`.

Run server checks from the repository root with `groot jobs server-test` and
deploy with `groot jobs server-deploy`. See [the project README](../README.md)
for the independent Native CLI and OpenCode plugin workflows.
