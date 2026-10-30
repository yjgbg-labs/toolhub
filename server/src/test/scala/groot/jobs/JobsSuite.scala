package groot.jobs

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import io.circe.Json
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import munit.CatsEffectSuite
import org.http4s.{Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import scala.jdk.CollectionConverters.*

class JobsSuite extends CatsEffectSuite:
  private val app = httpApp { JobsRoutes.register() }
  private val endpoint = Uri.unsafeFromString("/jobs")

  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "jobs '空-"))
  )(path => IO.blocking {
    val files = Files.walk(path)
    try files.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    finally files.close()
  })

  private case class Result(code: Int, stdout: String, stderr: String)

  private def execute(script: String, root: Path): IO[Result] = IO.blocking {
    Files.writeString(root.resolve("stdin"), "from stdin\n")
    val builder = new ProcessBuilder("/bin/sh", "-c", script).directory(root.toFile)
      .redirectInput(root.resolve("stdin").toFile)
      .redirectOutput(root.resolve("stdout").toFile)
      .redirectError(root.resolve("stderr").toFile)
    builder.environment().put("JOBS_TEST_VALUE", "空 'quoted'")
    val child = builder.start()
    try
      assert(child.waitFor(10, TimeUnit.SECONDS), "local jobs process timed out")
      Result(child.exitValue(), Files.readString(root.resolve("stdout")), Files.readString(root.resolve("stderr")))
    finally if child.isAlive then child.destroyForcibly()
  }

  private def script(job: String, parallel: Int = 5): IO[String] =
    app.httpApp(Request[IO](uri = endpoint.withQueryParam("job", job).withQueryParam("parallel", parallel))).flatMap { response =>
      assertEquals(response.status, Status.Ok)
      assertEquals(response.headers.get[`Content-Type`].map(_.mediaType), org.http4s.MediaType.parse("application/x-sh").toOption)
      response.as[String]
    }

  test("jobs exposes a Shell contract with validated job and parallel parameters") {
    val operation = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths").downField("/jobs").downField("get")
    assertEquals(operation.get[String]("operationId"), Right("jobs"))
    assertEquals(operation.downField("responses").downField("200").downField("content")
      .downField("application/x-sh").downField("schema").get[String]("type"), Right("string"))
    val parameters = operation.get[Vector[Json]]("parameters").toOption.get.map(value => value.hcursor.get[String]("name").toOption.get -> value.hcursor).toMap
    assertEquals(parameters("job").get[Boolean]("required"), Right(true))
    assertEquals(parameters("parallel").downField("schema").get[Int]("default"), Right(5))
    assertEquals(parameters("parallel").downField("schema").get[Int]("minimum"), Right(1))
    List("/jobs", "/jobs?job=", "/jobs?job=build&parallel=0", "/jobs?job=build&parallel=1.5").traverse_ { path =>
      app.httpApp(Request[IO](uri = Uri.unsafeFromString(path))).map(response => assertEquals(response.status, Status.BadRequest))
    }
  }

  test("the returned bundle runs imported jobs locally with literal arguments and inherited I/O") {
    temporary.use { root =>
      val target = "build '$(touch injected)' 空"
      for
        _ <- IO.blocking {
          Files.createDirectory(root.resolve("jobs"))
          Files.writeString(root.resolve("jobs/common.yaml"), """version: 1
jobs:
  - name: prepare
    run: printf prepared > prepared.txt
""")
          Files.writeString(root.resolve("jobs.yaml"), s"""version: 1
imports: [{path: ./jobs/common.yaml, namespace: common}]
jobs:
  - name: ${Json.fromString(target).noSpaces}
    needs: [prepare@common]
    run: |
      test -f prepared.txt
      IFS= read -r line
      printf '%s\\n' "$$JOBS_TEST_VALUE" "$$PWD" "$$line"
      printf diagnostic >&2
      printf built > result.txt
""")
        }
        shell <- script(target, 2)
        _ = assert(!Files.exists(root.resolve("result.txt")))
        result <- execute(shell, root)
        _ = assertEquals(result.code, 0, result.stderr)
        output <- IO.blocking(Files.readString(root.resolve("result.txt")))
      yield
        assertEquals(result.stderr, "diagnostic")
        assert(result.stdout.contains(s"空 'quoted'\n$root\nfrom stdin\n"))
        assert(result.stdout.contains("[jobs] start prepare@common"))
        assert(result.stdout.contains(s"[jobs] done  $target"))
        assertEquals(output, "built")
        assert(!Files.exists(root.resolve("jobs/prepared.txt")))
        assert(!Files.exists(root.resolve("injected")))
    }
  }

  test("failed scripts stop at the failing command, skip dependents and finish independent jobs") {
    temporary.use { root =>
      for
        _ <- IO.blocking(Files.writeString(root.resolve("jobs.yaml"), """version: 1
jobs:
  - name: bad
    run: |
      false
      touch after-failure
  - name: independent
    run: printf completed > independent.txt
  - name: build
    needs: [bad, independent]
    run: touch build.txt
  - name: unrelated
    run: touch unrelated.txt
"""))
        shell <- script("build", 1)
        result <- execute(shell, root)
      yield
        assertEquals(result.code, 1)
        assert(result.stderr.contains("[jobs] fail  bad (exit 1,"))
        assert(result.stderr.contains("[jobs] skip  build (dependency failed)"))
        assertEquals(Files.readString(root.resolve("independent.txt")), "completed")
        List("after-failure", "build.txt", "unrelated.txt").foreach(name => assert(!Files.exists(root.resolve(name))))
    }
  }

  test("configuration and unknown-target errors are local process errors") {
    temporary.use { root =>
      for
        shell <- script("missing")
        missingFile <- execute(shell, root)
        _ = assertEquals(missingFile.code, 1)
        _ = assert(missingFile.stderr.contains(s"cannot read $root/jobs.yaml"))
        _ <- IO.blocking(Files.writeString(root.resolve("jobs.yaml"), "version: 1\njobs: []\n"))
        missingJob <- execute(shell, root)
      yield
        assertEquals(missingJob.code, 1)
        assert(missingJob.stderr.contains("groot-cli jobs: no job named \"missing\""))
    }
  }
