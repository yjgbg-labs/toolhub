package groot.usejdk

import cats.effect.IO
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.GraalTestSupport.temporary
import io.circe.Json
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import munit.CatsEffectSuite
import org.http4s.{Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import scala.jdk.CollectionConverters.*

class UseJdkSuite extends CatsEffectSuite:
  private val app = httpApp { UseJdkRoutes.register() }
  private case class Result(code: Int, stdout: String, stderr: String)

  private def executable(path: Path, source: String): Unit =
    Files.createDirectories(path.getParent)
    Files.writeString(path, "#!/bin/sh\nset -eu\n" + source)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))

  private def jdk(path: Path, version: String = "25.0.5", mac: Boolean = false): Path =
    val home = if mac then path.resolve("Contents/Home") else path
    executable(home.resolve("bin/java"), "printf 'selected-java:%s\\n' \"$*\"\n")
    executable(home.resolve("bin/javac"), "exit 0\n")
    Files.writeString(home.resolve("release"), s"JAVA_VERSION=\"$version\"\n")
    home

  private def run(root: Path, command: String, environment: Map[String, String] = Map.empty): IO[Result] = IO.blocking {
    val output = Files.createTempDirectory(root, "run-")
    Files.writeString(output.resolve("stdin"), "from stdin\n")
    val builder = new ProcessBuilder("/bin/sh", "-c", UseJdkService.compile(25, command)).directory(root.toFile)
      .redirectInput(output.resolve("stdin").toFile)
      .redirectOutput(output.resolve("stdout").toFile)
      .redirectError(output.resolve("stderr").toFile)
    builder.environment().putAll(Map("GROOT_HOME" -> root.resolve("cache '空").toString,
      "JAVA_HOME" -> "/old-java", "PATH" -> s"${root.resolve("tools")}:${sys.env("PATH")}", "FIXTURE" -> root.toString,
      "TEST_VALUE" -> "空 'quoted' $value").asJava)
    builder.environment().putAll(environment.asJava)
    val child = builder.start()
    try
      assert(child.waitFor(20, TimeUnit.SECONDS), "use-jdk shell timed out")
      Result(child.exitValue(), Files.readString(output.resolve("stdout")), Files.readString(output.resolve("stderr")))
    finally if child.isAlive then
      child.descendants().forEach(_.destroyForcibly())
      child.destroyForcibly()
  }

  private def downloadFixture(root: Path, mac: Boolean = false): IO[Unit] = IO.blocking {
    jdk(root.resolve("archive/jdk-25"), mac = mac)
    val tar = new ProcessBuilder("tar", "-czf", root.resolve("jdk.tar.gz").toString,
      "-C", root.resolve("archive").toString, "jdk-25").inheritIO().start()
    assertEquals(tar.waitFor(), 0)
    val checksum = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("jdk.tar.gz")))
      .map(byte => f"${byte & 0xff}%02x").mkString
    Files.writeString(root.resolve("releases.json"), Json.arr(Json.obj(
      "version_data" -> Json.obj("major" -> Json.fromInt(25)),
      "binaries" -> Json.arr(Json.obj("os" -> Json.fromString(if mac then "mac" else "linux"),
        "architecture" -> Json.fromString(if mac then "aarch64" else "x64"), "image_type" -> Json.fromString("jdk"),
        "package" -> Json.obj("link" -> Json.fromString("https://fixture.invalid/jdk.tar.gz"), "checksum" -> Json.fromString(checksum))))
    )).noSpaces)
    executable(root.resolve("tools/uname"), s"""case "$$1" in -s) echo ${if mac then "Darwin" else "Linux"} ;; -m) echo ${if mac then "arm64" else "x86_64"} ;; *) exit 1 ;; esac
""")
    executable(root.resolve("tools/curl"), """output= url=
while [ "$#" -gt 0 ]; do
  case "$1" in -o) output=$2; shift ;; https://*) url=$1 ;; esac
  shift
done
printf '%s\n' "$url" >> "$FIXTURE/requests"
case "$url" in
  https://api.adoptium.net/*) cp "$FIXTURE/releases.json" "$output" ;;
  https://fixture.invalid/jdk.tar.gz)
    [ "${FAIL_DOWNLOAD:-0}" = 0 ] || exit 22
    cp "$FIXTURE/jdk.tar.gz" "$output"
    [ "${CORRUPT_DOWNLOAD:-0}" = 0 ] || printf broken >> "$output" ;;
  *) exit 1 ;;
esac
""")
  }

  test("CLI-only Shell contract validates version and command without executing the command") {
    val operation = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor
      .downField("paths").downField("/use-jdk").downField("get")
    assertEquals(operation.get[List[String]]("x-expose-on"), Right(List("cli")))
    assertEquals(app.endpoints.head.exposedOn, Set(framework.Client.cli))
    assert(operation.downField("requestBody").focus.isEmpty)
    val call = Uri.unsafeFromString("/use-jdk").withQueryParam("version", "25").withQueryParam("command", "java --version")
    for
      response <- app.httpApp(Request[IO](uri = call))
      shell <- response.as[String]
      _ = assertEquals(response.status, Status.Ok)
      _ = assertEquals(response.headers.get[`Content-Type`].map(_.mediaType), org.http4s.MediaType.parse("application/x-sh").toOption)
      _ = assertEquals(shell, UseJdkService.compile(25, "java --version"))
      _ <- List("/use-jdk", "/use-jdk?version=25", "/use-jdk?version=0&command=x", "/use-jdk?version=-1&command=x",
        "/use-jdk?version=1.5&command=x", "/use-jdk?version=25&command=%20", "/use-jdk?version=25&command=x%00")
        .traverse_(path => app.httpApp(Request[IO](uri = Uri.unsafeFromString(path))).map(response => assertEquals(response.status, Status.BadRequest)))
    yield
      intercept[InvalidUseJdk](UseJdkService.compile(25, "中" * 22000))
      intercept[InvalidUseJdk](UseJdkService.compile(0, "true"))
  }

  test("cached Linux and macOS JDKs preserve shell quoting, cwd, environment, I/O and exit code") {
    List(false, true).traverse_ { mac => temporary.use { root =>
      val target = root.resolve("cache '空/jdks/temurin-25")
      for
        home <- IO.blocking(jdk(target, mac = mac))
        _ <- IO.blocking(executable(root.resolve("tools/curl"), "exit 99\n"))
        result <- run(root, """IFS= read -r line
printf '%s\n' "$JAVA_HOME" "$TEST_VALUE" "$PWD" "$line"
java 'literal $(touch injected)'
printf diagnostic >&2
exit 23""")
      yield
        assertEquals(result.code, 23, result.stderr)
        assertEquals(result.stdout, s"$home\n空 'quoted' $$value\n$root\nfrom stdin\nselected-java:literal $$(touch injected)\n")
        assertEquals(result.stderr, "diagnostic")
        assert(!Files.exists(root.resolve("injected")))
    } }
  }

  test("fresh downloads verify SHA-256, install both archive layouts and reuse the cache") {
    List(false, true).traverse_ { mac => temporary.use { root => for
      _ <- downloadFixture(root, mac)
      result <- run(root, "java --version")
      again <- run(root, "java cached")
      requests <- IO.blocking(Files.readAllLines(root.resolve("requests")).asScala.toVector)
    yield {
      assertEquals(result.code, 0, result.stderr)
      assertEquals(result.stdout, "selected-java:--version\n")
      assertEquals(again.code, 0, again.stderr)
      assertEquals(again.stdout, "selected-java:cached\n")
      assertEquals(requests.size, 2)
      assert(requests.head.contains(if mac then "os=mac" else "os=linux"))
      assert(requests.head.contains(if mac then "architecture=aarch64" else "architecture=x64"))
      val entries = Files.list(root.resolve("cache '空/jdks"))
      try assertEquals(entries.iterator().asScala.map(_.getFileName.toString).toVector, Vector("temurin-25"))
      finally entries.close()
    }
    } }
  }

  test("download failures and checksum mismatches clean up without running the child; retry succeeds") {
    List("FAIL_DOWNLOAD", "CORRUPT_DOWNLOAD").traverse_ { flag => temporary.use { root => for
      _ <- downloadFixture(root)
      failed <- run(root, "touch command-ran", Map(flag -> "1"))
      _ = assert(failed.code != 0)
      _ = assert(!Files.exists(root.resolve("command-ran")))
      _ = assert(!Files.exists(root.resolve("cache '空/jdks/temurin-25")))
      _ <- IO.blocking {
        val entries = Files.list(root.resolve("cache '空/jdks"))
        try assert(!entries.iterator().hasNext) finally entries.close()
      }
      retried <- run(root, "java retry")
    yield assertEquals(retried.code, 0, retried.stderr)
    } }
  }

  test("incomplete and mismatched caches are reported without overwriting them") {
    List("21.0.1", "missing").traverse_ { version => temporary.use { root => for
      _ <- IO.blocking {
        val home = jdk(root.resolve("cache '空/jdks/temurin-25"), version)
        if version == "missing" then Files.delete(home.resolve("release"))
      }
      result <- run(root, "touch command-ran")
    yield {
      assertEquals(result.code, 1)
      assert(result.stderr.contains("incomplete or has the wrong version"))
      assert(Files.exists(root.resolve("cache '空/jdks/temurin-25/bin/java")))
      assert(!Files.exists(root.resolve("command-ran")))
    }
    } }
  }

  test("concurrent cold invocations publish one complete cache without nested JDK directories") {
    List.range(0, 8).traverse_ { _ => temporary.use { root => for
      _ <- downloadFixture(root)
      results <- List.fill(4)(run(root, "java concurrent")).parSequence
    yield {
      results.foreach(result => assertEquals(result.code, 0, result.stderr))
      assert(Files.exists(root.resolve("cache '空/jdks/temurin-25/bin/javac")))
      assert(!Files.exists(root.resolve("cache '空/jdks/temurin-25/jdk-25")), results.map(_.stderr).mkString("\nNEXT\n"))
      assert(!Files.exists(root.resolve("cache '空/jdks/.temurin-25.lock")))
    }
    } }
  }
