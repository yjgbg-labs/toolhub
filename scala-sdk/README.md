# Scala SDK

A standalone OpenAPI 3.1 → Scala client generator and cross-platform runtime.
Nothing in this directory imports `server/`, `cli/` or `plugin/`. Bun runs the
generator; generated Scala has no Bun or JavaScript runtime dependency on JVM or
Native. The checked-in `openapi.json` is a reproducible snapshot of Sprout's
contract, not a dependency on a running daemon.

## Generate

From the repository root:

```sh
groot jobs scala-sdk-generate
```

This writes `scala-sdk/generated/sprout/{Models,Calls,Client}.scala` using package
`sprout.generated`. To refresh the snapshot from the local daemon, explicitly run
`groot jobs scala-sdk-schema` first. Set `SPROUT_OPENAPI_URL` for a TCP server, or
`SPROUT_SOCKET` for another Unix socket. `SDK_SCHEMA` overrides the generation
job's input file/URL.

The generator also works independently, without installing dependencies:

```sh
bun scala-sdk/generator/main.ts \
  --schema ./openapi.json \
  --package example.client \
  --output ./src/main/scala/example/client
```

`--schema` accepts a local file or HTTP(S) URL; `--unix-socket /absolute/path`
can accompany an HTTP URL. Output is deterministic, no server is contacted for a
local file, and handwritten destination files are never overwritten. Generated
files are build outputs and ignored by Git.

## Structure

```text
project.scala       Scala and Circe dependencies, shared by all targets
config.env          Maven organization, version and local repository
core/               Call[A], Executor[F], HTTP values, errors, JSON codecs
adapters/cats/      Cats Effect executor, preserving transport cancellation
adapters/zio/       ZIO executor, preserving R and typed ClientError
adapters/sttp/      HTTP transport for any sttp 4 Backend[F]
adapters/native/    HTTP-over-Unix-Socket backend for Native
generator/          Independent Bun/TypeScript generator and its tests
cli/                Business-independent Native CLI with runtime OpenAPI discovery
tests/              Same checks executed on JVM, JS and Native; HTTP integration
```

The SDK uses Scala **3.9.0** (required by sttp **4.1.0**), Scala Native **0.5.12**,
and Circe **0.14.16**. `core/` and generated sources are shared across all three
targets. Select only the adapters you use; the native-only directory must not be
included in a JVM or JS build. With Scala CLI, include `project.scala`, `core/`,
your generated sources and your selected adapter directories. With sbt, put the
shared files in a crossProject and use `%%%` for dependencies; JVM sttp backends
require a recent JDK (the development jobs use JDK 25).

## Supported versions and local Maven publishing

The supported/tested Scala version is **3.9.0** on all targets. Published JARs
contain Scala 3.9 TASTy, so **Scala 3.3–3.8 cannot consume them**, despite the
`_3` binary suffix. Scala 2 is not supported. Newer Scala 3 compilers should
follow Scala's backwards TASTy compatibility, but are not yet tested here.
The platform versions used for publishing and consumption checks are Scala.js
**1.22.0** and Scala Native **0.5.12**. JVM artifacts target **Java 17** bytecode;
the development/build launcher uses JDK 25.

Publish all three platforms into the standard Maven local repository:

```sh
groot jobs scala-sdk-publish-local
```

Default destination: `~/.m2/repository/lab/yjgbg/`. Default version:
`0.1.0-SNAPSHOT`. Each module has a binary JAR, source JAR, dependency POM and
SHA-256 checksums. Dependent modules are compiled against the published core
JAR; they do not bundle duplicate core classes.

| Module | Contents | Platforms |
| --- | --- | --- |
| `sprout-client` | Generated Sprout models, calls and `Client[F]` | JVM / JS / Native |
| `sprout-sdk-core` | Effect-independent runtime and JSON codecs | JVM / JS / Native |
| `sprout-sdk-cats` | Cats Effect executor | JVM / JS / Native |
| `sprout-sdk-zio` | ZIO executor | JVM / JS / Native |
| `sprout-sdk-sttp` | sttp transport and executor | JVM / JS / Native |
| `sprout-sdk-native-unix` | Native Unix Socket backend | Native |

The artifact suffix is `_3` for JVM, `_sjs1_3` for JS, or `_native0.5_3` for
Native. For example:

```text
lab.yjgbg:sprout-client_3:0.1.0-SNAPSHOT
lab.yjgbg:sprout-client_sjs1_3:0.1.0-SNAPSHOT
lab.yjgbg:sprout-client_native0.5_3:0.1.0-SNAPSHOT
```

The generated client transitively brings the core runtime and codecs. Add the
executor/transport modules you use. A custom generated SDK can depend directly
on `sprout-sdk-core`, without depending on Sprout's generated client.

Publish a single platform with `scala-sdk-publish-local-jvm`,
`scala-sdk-publish-local-js` or `scala-sdk-publish-local-native`. Settings live in
`scala-sdk/config.env`; per-command overrides are supported:

```sh
SDK_VERSION=0.2.0-SNAPSHOT groot jobs scala-sdk-publish-local
SDK_MAVEN_REPOSITORY=/absolute/custom/repository groot jobs scala-sdk-publish-local
```

With a custom repository, configure consumers to use that path instead of their
default Maven local directory. To verify the published dependency graph in an
isolated consumer, run `groot jobs scala-sdk-published-test`: it compiles and runs
JVM/JS/Native consumers and links a Native CLI using only Maven dependencies.
Use `groot jobs scala-sdk-test-installed` to run those consumption checks against
the already installed artifacts without publishing again.

### Maven (`pom.xml`, JVM)

Maven uses `~/.m2/repository` by default. In a Scala 3 project configured to use
Scala 3.9.0, add:

```xml
<dependencies>
  <dependency>
    <groupId>lab.yjgbg</groupId>
    <artifactId>sprout-client_3</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>lab.yjgbg</groupId>
    <artifactId>sprout-sdk-sttp_3</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
</dependencies>
```

### Gradle (`build.gradle`, JVM)

```groovy
plugins { id 'scala' }

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'org.scala-lang:scala3-library_3:3.9.0'
    implementation 'lab.yjgbg:sprout-client_3:0.1.0-SNAPSHOT'
    implementation 'lab.yjgbg:sprout-sdk-sttp_3:0.1.0-SNAPSHOT'
}
```

### sbt (`build.sbt`)

```scala
scalaVersion := "3.9.0"
resolvers += Resolver.mavenLocal
libraryDependencies ++= Seq(
  "lab.yjgbg" %% "sprout-client" % "0.1.0-SNAPSHOT",
  "lab.yjgbg" %% "sprout-sdk-sttp" % "0.1.0-SNAPSHOT"
)
```

For a project with the Scala.js/Scala Native plugin enabled, use `%%%` instead
of `%%` to select the matching platform artifacts. Use Scala.js 1.22.0 or Scala
Native 0.5.12 as appropriate.

### Scala CLI (`project.scala`)

```scala
//> using scala 3.9.0
//> using repository m2Local
//> using dep lab.yjgbg::sprout-client::0.1.0-SNAPSHOT
//> using dep lab.yjgbg::sprout-sdk-sttp::0.1.0-SNAPSHOT
```

The double colon before the version enables platform-aware artifact selection.
For JS add `//> using platform js` and `//> using jsVersion 1.22.0`. For Native:

```scala
//> using platform native
//> using nativeVersion 0.5.12
//> using dep lab.yjgbg::sprout-sdk-native-unix::0.1.0-SNAPSHOT
```

Add `lab.yjgbg::sprout-sdk-cats::0.1.0-SNAPSHOT` or
`lab.yjgbg::sprout-sdk-zio::0.1.0-SNAPSHOT` when using those executors.

## Effect-independent API

```scala
trait Executor[F[_]]:
  def execute[A](call: Call[A]): F[A]

val request: Call[Vector[ScheduleEntry]] = Calls.schedule.list()
// Calls are immutable, inspectable and composable without any effect type class.
val withMetadata = request.withResponse // Call[(HttpResponse, Vector[ScheduleEntry])]
```

Generated methods delegate to an executor without requiring a `Monad`,
`Functor`, Cats Effect or ZIO. Circe brings Cats Core transitively, but does not
select an effect runtime. `Call.map` maps the pure decoder, not an effect.

### Cats Effect

```scala
import cats.effect.IO
import sprout.sdk.cats.CatsExecutor
import sprout.generated.Client

// send: HttpRequest => IO[HttpResponse], e.g. a SttpTransport[IO]
val client = new Client[IO](new CatsExecutor[IO](send))
val program: IO[Vector[ScheduleEntry]] = client.schedule.list()
```

Keep the underlying HTTP client's `Resource` around the program. Any HTTP client
can supply `send`; the adapter does not turn asynchronous I/O into blocking I/O
or alter cancellation. Choose an sttp Cats backend for your target if using sttp.

### ZIO

```scala
import zio.ZIO
import sprout.sdk.{ClientError, HttpRequest, HttpResponse}
import sprout.sdk.zio.ZioExecutor
import sprout.generated.Client

// send: HttpRequest => ZIO[R, Throwable, HttpResponse]
val client = new Client[[A] =>> ZIO[R, ClientError, A]](new ZioExecutor[R](send))
```

The caller retains ownership of the HTTP client's `Scope`. Transport errors,
unexpected HTTP statuses and decoding failures use the typed `ClientError`
channel. HTTP errors retain the complete status, headers and raw response body.

### JVM / JS / Native HTTP

`SttpTransport[F]` converts SDK HTTP values to sttp requests. Use a platform
backend, then either the Cats/ZIO adapter or `SttpExecutor[F]`:

| Target | Example backend | Effect |
| --- | --- | --- |
| JVM | `DefaultSyncBackend()` | `Identity` |
| JS (browser or Bun) | `DefaultFutureBackend()` | `Future` |
| Native | `DefaultSyncBackend()` (libcurl) | `Identity` |
| Native local Sprout | `new UnixSocketBackend(path)` | `Identity` |

Use `http://localhost` as the base URL with `UnixSocketBackend`; the backend
directs the connection to the socket. This small adapter uses sttp's native curl
extension API and is pinned/tested against 4.1.0. The caller closes the backend.
Requests have a configurable 30-second timeout and do not follow redirects.

## Missing fields, null, defaults and errors

* Required non-null field: `A`.
* Required nullable field: `Option[A]`, where `None` emits JSON null.
* Optional non-null field: `Optional[A]`.
* Optional nullable field: `Optional[Option[A]]` — `Missing`, `Present(None)`
  and `Present(Some(value))` remain distinct.

Optional parameters/fields default to `Optional.Missing`, so the server applies
its defaults. OpenAPI defaults are annotations and are not silently inserted
into requests or responses. To set a value, use `Optional.Present(value)`.

`Int`, `Long`, `BigInt` and `BigDecimal` retain their Scala precision, including
in Scala.js: all targets use Circe Jawn, avoiding JavaScript `JSON.parse`'s
conversion of numbers to doubles. String enums and `oneOf`/`anyOf` generate ADTs and codecs. Multiple
success statuses/media types produce a response ADT; one success response gives
its body directly. JSON/text/empty bodies are supported. `withResponse` exposes
status and headers even for single-success endpoints. Declared HTTP failures
are represented by `ClientError.Http`, with their raw body, rather than thrown
away or converted to a success value.

This generator targets Sprout's JSON API subset of OpenAPI 3.1: local component
references, recursive objects, primitive values, arrays, dictionaries, unions,
path/query/header/cookie primitives and primitive query arrays. It uses the
contract's HTTP method, never a method inferred from an operation name. Schema
constraints such as minimum/pattern are enforced by the server, while JSON
shape, primitive type, required fields and union membership are checked locally.
Unsupported constructs such as multipart, external references, `allOf`, mixed
fixed/dictionary objects and response status ranges fail generation explicitly.

## Checks and native CLI

```sh
groot jobs scala-sdk-test             # generator + JVM + JS + Native + socket/TCP
groot jobs scala-sdk-test-jvm         # individual target
groot jobs scala-sdk-test-js          # links JS, executes with Bun
groot jobs scala-sdk-test-native      # links and executes a native binary
groot jobs scala-sdk-native-cli       # builds dist/sprout-native
groot jobs scala-sdk-native-cli-test  # live schema discovery + generic API fixture
groot jobs scala-sdk-integration-test # actual JVM/JS/Native HTTP backends + socket

scala-sdk/dist/sprout-native health
scala-sdk/dist/sprout-native schedule list
scala-sdk/dist/sprout-native --help
scala-sdk/dist/sprout-native schedule log --name example
scala-sdk/dist/sprout-native schedule new --name example --body '{"command":["echo","hi"],"every":"1h"}'
```

The native CLI defaults to the same Unix socket as Sprout. `SPROUT_BASE_URL`
can be an `http(s)://...` URL or `unix:///absolute/path`. Every invocation,
including help, fetches `/openapi.json` from that server. A running server is
therefore required. Commands come from dot-separated `operationId` values
(for example, `inventory.parts.revise` becomes `inventory parts revise`), while
methods, paths, parameters and help come from the contract. Optional `x-rpc`
command descriptions are used for group help. New server operations are available
on the next invocation without regenerating sources or recompiling the CLI.

The entry point is `cli/NativeCli.scala`; `cli/CommandLine.scala` handles discovery,
help and arguments, and `cli/Catalog.scala` interprets OpenAPI and constructs
generic SDK HTTP requests. The build uses only the core and HTTP transports;
it does not depend on `sprout.generated`, the checked-in schema or the generator.

Invocation follows the TypeScript CLI conventions:

- Path parameters are positional or `--path.name value`.
- Query/header/cookie parameters use `--name value` or `--location.name value`.
  Use qualified flags for duplicate names or reserved `body`, `input`, `help`.
- Use `--name=value` for values beginning with `--`. Boolean values are explicit
  (`--enabled false`); primitive query arrays accept repeated flags or JSON arrays.
- `--body` accepts inline JSON, `@file`, or `-` for stdin. `--input` accepts the
  complete `{path, query, header, cookie, body}` JSON object and must be used alone.
- Defaults remain omitted so the server applies them. JSON numbers preserve their
  precision. Required values, primitive types, enums and common schema constraints
  are checked locally; the server remains responsible for full schema validation.
- JSON responses are pretty-printed, strings/text are printed directly, empty/null
  responses produce no output, and HTTP errors print the status/body and exit 1.

The dynamic catalog supports OpenAPI 3.1 local references, JSON request bodies,
primitive path/query/header/cookie parameters and primitive query arrays with
`form`/`simple` serialization. It rejects unsupported parameter serialization,
external references and non-JSON request bodies rather than guessing a request.
It is not a complete OpenAPI/JSON Schema implementation.

Native compilation needs Clang, libcurl/OpenSSL development files and the usual
Scala Native platform prerequisites. On this Ubuntu development host,
`scala-sdk-native-tools` can extract missing development packages into
`scala-sdk/.cache/native` without sudo. Other platforms should provide their
normal toolchain. Program execution requires libcurl and its runtime libraries.
