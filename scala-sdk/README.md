# Scala SDK

Three independent layers, with no dependency on server business code:

```text
sdk-core (JVM / JS / Native) ← sdk-generator (JVM / JS / Native)
          ↑                              │ produces source
    cli (Native)                 generated typesafe SDK → sdk-core
```

`sdk-core` owns OpenAPI discovery, metadata, local references, input validation,
JSON Schema export, dynamic request construction, HTTP values, codecs and the
effect-independent `Call[A]` / `Executor[F]` interfaces. `sdk-generator` is a pure
Scala library taking a `Catalog` or JSON document and returning Scala source files.
The CLI handles command-line parsing, help, input files/stdin and output; all API
discovery and calls go through core. It does not import the generator or generated
business clients.

## Layout and versions

```text
sdk-core/            Shared protocol/runtime library (sprout.sdk)
sdk-generator/       Shared Scala generator (sprout.sdk.generator)
cli/                 Native CLI (sprout.sdk.cli)
adapters/            Optional Cats, ZIO, sttp and Native Unix Socket adapters
tools/               JVM/Native generator launcher and Scala.js export bridge
generator/           Thin Bun host I/O bridge and generator test fixtures
generated/           Ignored generated business SDK sources
scripts/             Build, publication, release and verification helpers
```

Supported/tested: **Scala 3.9.0**, Scala.js **1.22.0**, Scala Native **0.5.12**,
Circe **0.14.16**, sttp **4.1.0**. JVM artifacts target Java 17 bytecode; development
jobs use JDK 25. Scala 2 and older Scala 3 compilers cannot consume these 3.9 TASTy
artifacts. The cross-platform libraries have no host filesystem or HTTP backend
dependency; callers select an adapter. The Native transport uses libcurl.

## Independent release lifecycles

`config.env` separates protocol/tooling version `SDK_VERSION=0.2.0` from business
client version `SDK_CLIENT_VERSION=0.2.0-SNAPSHOT`. Business schema changes require
only new generated sources and a new business SDK release. They do not republish
core, the generator, adapters or CLI. Compatible new server commands are discovered
at runtime by the existing CLI binary.

Publish protocol/tooling libraries **once per protocol/tooling release**:

```sh
groot jobs scala-sdk-publish-local
```

This publishes 16 artifacts to `~/.m2/repository/lab/yjgbg/`. Existing non-SNAPSHOT
versions are checksum-verified and skipped, never overwritten. Change `SDK_VERSION`
for a new protocol/tooling release. Individual platform jobs end in `-jvm`, `-js`,
or `-native`. `SDK_MAVEN_REPOSITORY` overrides the destination.

| Artifact base name | Platforms | Dependencies |
| --- | --- | --- |
| `sprout-sdk-core` | JVM / JS / Native | Scala + Circe, no business SDK |
| `sprout-sdk-generator` | JVM / JS / Native | core |
| `sprout-sdk-cats` | JVM / JS / Native | core + Cats Effect |
| `sprout-sdk-zio` | JVM / JS / Native | core + ZIO |
| `sprout-sdk-sttp` | JVM / JS / Native | core + sttp |
| `sprout-sdk-native-unix` | Native | core + sttp adapter |
| `sprout-client` | JVM / JS / Native | core; independently generated/versioned |

Suffixes: `_3` (JVM), `_sjs1_3` (JS), `_native0.5_3` (Native). Each publication has
a binary JAR, source JAR, dependency POM and SHA-256. Modules compile against the
published core rather than bundling duplicate runtime classes.

### Business SDK generation and publication

After the protocol libraries have been installed:

```sh
# Reproducible checked-in schema by default; live discovery is explicit.
groot jobs scala-sdk-schema
SDK_CLIENT_VERSION=0.3.0 groot jobs scala-sdk-publish-client-local

# Another business API, still using the same protocol libraries:
SDK_SCHEMA=/absolute/inventory.json \
SDK_CLIENT_PACKAGE=inventory.generated \
SDK_CLIENT_SOURCES=scala-sdk/generated/inventory \
SDK_CLIENT_MODULE=inventory-client \
SDK_CLIENT_VERSION=1.0.0 \
groot jobs scala-sdk-publish-client-local
```

`scala-sdk-publish-client-local` never invokes protocol publication or a CLI build.
`scala-sdk-generate` only generates sources. `scala-sdk-schema` uses
`SPROUT_OPENAPI_URL` or `SPROUT_SOCKET` to refresh `openapi.json`.

The generator can be called as a library on all three platforms:

```scala
import sprout.sdk.Catalog
import sprout.sdk.generator.Generator

val catalog = new Catalog(Catalog.parse(openApiText))
val files: Map[String, String] = Generator.generate(catalog, "inventory.generated")
// Models.scala, Calls.scala, Client.scala; caller owns I/O and compilation.
```

Runnable launchers are available through `scala-sdk-generator-jvm`,
`scala-sdk-generator-native` and `scala-sdk-generator-js`. Build a launcher once,
then run it against changed schemas without recompiling it:

```sh
scala-sdk/dist/generator-native openapi.json inventory.generated output/directory
groot use-jdk 25 -- java -jar scala-sdk/dist/generator.jar openapi.json inventory.generated output/directory

# JS export accepts JSON text and returns a JSON object of source filenames/text.
bun -e 'import {generate} from "./scala-sdk/dist/generator.mjs"; console.log(generate(await Bun.file("openapi.json").text(), "inventory.generated"))'

# Bun host wrapper additionally supports HTTP(S), Unix Socket and safe file writes:
bun scala-sdk/generator/main.ts --schema ./openapi.json --package inventory.generated --output ./output
```

The Bun wrapper contains no generation rules. The same Scala generator executes
on JVM, JS and Native, and parity tests compare their complete generated output.
Handwritten destination files are rejected before writing. JSON is parsed with
Circe Jawn on every target, preserving integer/decimal precision.

## Maven / Gradle / sbt / Scala CLI

The following examples consume the generated client. To consume dynamic discovery
instead, select `sprout-sdk-core:0.2.0`; for generation select
`sprout-sdk-generator:0.2.0`. Add only the transport/effect adapters you use.

### Maven (`pom.xml`, JVM)

Configure Scala 3.9.0 and add these dependencies. Maven uses `~/.m2/repository`:

```xml
<dependencies>
  <dependency>
    <groupId>lab.yjgbg</groupId>
    <artifactId>sprout-client_3</artifactId>
    <version>0.2.0-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>lab.yjgbg</groupId>
    <artifactId>sprout-sdk-sttp_3</artifactId>
    <version>0.2.0</version>
  </dependency>
</dependencies>
```

### Gradle (`build.gradle`, JVM)

```groovy
plugins { id 'scala' }
repositories { mavenLocal(); mavenCentral() }
dependencies {
    implementation 'org.scala-lang:scala3-library_3:3.9.0'
    implementation 'lab.yjgbg:sprout-client_3:0.2.0-SNAPSHOT'
    implementation 'lab.yjgbg:sprout-sdk-sttp_3:0.2.0'
}
```

### sbt (`build.sbt`)

```scala
scalaVersion := "3.9.0"
resolvers += Resolver.mavenLocal
libraryDependencies ++= Seq(
  "lab.yjgbg" %% "sprout-client" % "0.2.0-SNAPSHOT",
  "lab.yjgbg" %% "sprout-sdk-sttp" % "0.2.0"
)
```

For Scala.js/Native projects use `%%%` with the appropriate plugin.

### Scala CLI (`project.scala`)

```scala
//> using scala 3.9.0
//> using repository m2Local
//> using dep lab.yjgbg::sprout-client::0.2.0-SNAPSHOT
//> using dep lab.yjgbg::sprout-sdk-sttp::0.2.0
```

The double colon before the version enables platform-aware selection. Add
`//> using platform js` / `//> using jsVersion 1.22.0` for JS, or
`//> using platform native` / `//> using nativeVersion 0.5.12` for Native.

## Dynamic and typesafe calls

Core requires no Monad, Functor, Cats Effect or ZIO. `Call` is an immutable request
plus decoder; `Executor[F]` decides how it is run. Circe brings Cats Core
transitively but does not choose an effect runtime.

```scala
import sprout.sdk.*
import sprout.sdk.cats.CatsExecutor
import cats.effect.IO

// send: HttpRequest => IO[HttpResponse], owned by the application
val executor = new CatsExecutor[IO](send)
val dynamic = for
  catalog <- executor.execute(Catalog.discover())
  client = new DynamicClient[IO](catalog, executor)
  response <- client.call("inventory.list")
yield response

// JSON Schema exports for dynamic tool integrations:
val input = catalog.inputSchema(catalog.byId("inventory.list"))
val output = catalog.outputSchema(catalog.byId("inventory.list"))
```

Discovery can use another endpoint via `Catalog.discover(path)`. A custom executor
can supply headers, base URL, cancellation and any HTTP backend. The sttp adapter
supports JVM HttpClient, JS Fetch and Native Curl; Native Unix Socket support is
an optional adapter. The caller owns backend cleanup. The supplied sttp transport
defaults to a 30-second timeout and does not follow redirects.

Generated `Client[F]` methods use the same executor, with typed parameters/results:

```scala
val client = new sprout.generated.Client[IO](executor)
val jobs = client.schedule.list()
val description = sprout.generated.Calls.schedule.list() // Call[Vector[ScheduleEntry]]
val withMetadata = description.withResponse
```

`Optional.Missing`, `Optional.Present(value)` and explicit JSON null remain distinct.
Optional defaults are not silently inserted; the server applies its defaults.
Recursive objects, string enums, unions, dictionaries, int64/BigInt/BigDecimal and
multiple response variants are generated with codecs. HTTP failures retain the
status, headers and raw body in `ClientError.Http`. ZIO uses a typed `ClientError`
channel; Cats/ZIO adapters retain the transport's cancellation behavior.

This is the supported OpenAPI 3.1 JSON API subset, not all of OpenAPI/JSON Schema:
local references, primitive path/query/header/cookie values, primitive query arrays
and JSON request bodies. The dynamic validator checks common constraints, required
fields and union membership; the server remains responsible for full validation.
The generator rejects unsupported constructs (such as multipart, external schema
references, `allOf`, mixed fixed/dictionary objects and success status ranges).

## Native CLI and deployment

```sh
groot jobs scala-sdk-native-cli-test
groot jobs scala-sdk-native-cli-install  # build/test → S3 upload → download/check → install
groot jobs cli-deploy                   # install the pinned CLI release; no build
sprout --help
sprout schedule log --name example
```

The installation provides `sprout` and `sprout-native`. Release artifacts are
content-addressed on S3 and SHA-256 verified before activation. The CLI's pinned
hash lives in `scala-sdk/config.env`, independently of the server's release hash.
Business server deployments reuse that same CLI release. The TypeScript source
remains available for the existing plugin's API adapter and comparison.

Every CLI invocation, including help, fetches `/openapi.json`; a reachable server
is required. `SPROUT_BASE_URL` selects HTTP(S) or `unix:///absolute/socket`; default
is the local Sprout socket. Help uses the invoked filename (`argv[0]`), including
renamed binaries and symlinks. Commands come from dot-separated `operationId`s;
group descriptions come from optional `x-rpc` metadata.

- Path arguments are positional or `--path.name`; other parameters use `--name`
  or `--location.name`. Qualify ambiguous/reserved names (`body`, `input`, `help`).
- Use `--name=value` for values beginning with `--`; booleans require explicit
  values. Query arrays accept repeated flags or JSON arrays.
- `--body` accepts JSON, `@file` or stdin `-`; `--input` takes the complete
  `{path, query, header, cookie, body}` document and must be used alone.
- JSON responses are pretty-printed; text is printed directly; empty/null responses
  are silent. Errors print to stderr and exit 1.

Native builds need Clang and libcurl/OpenSSL development files. The Ubuntu helper
can extract missing toolchain packages into `scala-sdk/.cache/native` without sudo.
Runtime requires the corresponding platform's libcurl/system libraries.

## Verification and comparison

```sh
groot jobs scala-sdk-test                    # core + generated SDK + generator on 3 targets, HTTP + CLI
groot jobs scala-sdk-generator-platform-test # identical output from JVM/JS/Native generator
groot jobs scala-sdk-published-test          # initial protocol/client publication + isolated consumers
groot jobs scala-sdk-test-installed          # only existing Maven artifacts, including generator and CLI
groot jobs scala-sdk-cli-benchmark           # Native vs TS/Bun fresh-process help benchmark
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for Native/TypeScript CLI and
Scala.js/TypeScript Code Mode plugin trade-offs.
