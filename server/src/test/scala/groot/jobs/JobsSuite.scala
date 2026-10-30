package groot.jobs

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import io.circe.Json
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.*
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
    builder.environment().put("TMPDIR", root.toString)
    val child = builder.start()
    try
      assert(child.waitFor(10, TimeUnit.SECONDS), "local jobs process timed out")
      Result(child.exitValue(), Files.readString(root.resolve("stdout")), Files.readString(root.resolve("stderr")))
    finally if child.isAlive then
      child.destroy()
      if !child.waitFor(2, TimeUnit.SECONDS) then
        child.descendants().forEach(_.destroyForcibly())
        child.destroyForcibly()
  }

  private def request(yaml: String, job: String, parallel: Int = 5): Request[IO] =
    Request[IO](Method.POST, endpoint.withQueryParam("job", job).withQueryParam("parallel", parallel)).withEntity(Json.fromString(yaml))

  private def script(yaml: String, job: String, parallel: Int = 5): IO[String] =
    app.httpApp(request(yaml, job, parallel)).flatMap { response =>
      assertEquals(response.status, Status.Ok)
      assertEquals(response.headers.get[`Content-Type`].map(_.mediaType), org.http4s.MediaType.parse("application/x-sh").toOption)
      response.as[String]
    }

  test("jobs exposes a Shell contract with validated job and parallel parameters") {
    val operation = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths").downField("/jobs").downField("post")
    assertEquals(operation.get[String]("operationId"), Right("jobs"))
    assertEquals(operation.downField("responses").downField("200").downField("content")
      .downField("application/x-sh").downField("schema").get[String]("type"), Right("string"))
    val parameters = operation.get[Vector[Json]]("parameters").toOption.get.map(value => value.hcursor.get[String]("name").toOption.get -> value.hcursor).toMap
    assertEquals(parameters("job").get[Boolean]("required"), Right(true))
    assertEquals(parameters("parallel").downField("schema").get[Int]("default"), Right(5))
    assertEquals(parameters("parallel").downField("schema").get[Int]("minimum"), Right(1))
    assertEquals(operation.downField("requestBody").downField("content").downField("application/json").downField("schema").get[String]("type"), Right("string"))
    assertEquals(operation.downField("requestBody").get[String]("x-cli-default-file"), Right("jobs.yaml"))
    List("/jobs", "/jobs?job=", "/jobs?job=build&parallel=0", "/jobs?job=build&parallel=1.5").traverse_ { path =>
      app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(Json.fromString("version: 1\njobs: []")))
        .map(response => assertEquals(response.status, Status.BadRequest))
    }
  }

  test("Scala compiles uploaded YAML into shell that preserves literal scripts and inherited I/O") {
    temporary.use { root =>
      val target = "build '$(touch injected)' 空"
      val yaml = s"""version: 1
jobs:
  - name: prepare
    run: printf prepared > prepared.txt
  - name: ${Json.fromString(target).noSpaces}
    needs: [prepare]
    run: |
      test -f prepared.txt
      IFS= read -r line
      printf '%s\\n' "$$JOBS_TEST_VALUE" "$$PWD" "$$line"
      printf diagnostic >&2
      printf built > result.txt
"""
      for
        shell <- script(yaml, target, 2)
        _ = assert(shell.startsWith("#!/bin/sh\n"))
        _ = assert(!shell.contains("bun"))
        _ = assert(!Files.exists(root.resolve("result.txt")))
        result <- execute(shell, root)
        _ = assertEquals(result.code, 0, result.stderr)
        output <- IO.blocking(Files.readString(root.resolve("result.txt")))
      yield
        assertEquals(result.stderr, "diagnostic")
        assert(result.stdout.contains(s"空 'quoted'\n$root\nfrom stdin\n"))
        assert(result.stdout.contains("[jobs] start prepare"))
        assert(result.stdout.contains(s"[jobs] done  $target"))
        assertEquals(output, "built")
        assert(!Files.exists(root.resolve("jobs.yaml")))
        assert(!Files.exists(root.resolve("injected")))
    }
  }

  test("failed scripts stop at the failing command, skip dependents and finish independent jobs") {
    temporary.use { root =>
      val yaml = """version: 1
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
"""
      for
        shell <- script(yaml, "build", 1)
        result <- execute(shell, root)
      yield
        assertEquals(result.code, 1)
        assert(result.stderr.contains("[jobs] fail  bad (exit 1,"))
        assert(result.stderr.contains("[jobs] skip  build (dependency failed)"))
        assertEquals(Files.readString(root.resolve("independent.txt")), "completed")
        List("after-failure", "build.txt", "unrelated.txt").foreach(name => assert(!Files.exists(root.resolve(name))))
    }
  }

  test("invalid YAML, imports and unknown targets return HTTP errors instead of executable scripts") {
    List(
      "jobs: [" -> "invalid YAML",
      "version: 1\nimports: []\njobs: []" -> "imports is not supported",
      "version: 1\njobs: []" -> "no job named",
      "version: 1\njobs: [{name: build, run: echo, needs: [missing]}]" -> "missing job"
    ).traverse_ { (yaml, message) =>
      app.httpApp(request(yaml, "build")).flatMap { response =>
        assertEquals(response.status, Status.BadRequest)
        response.as[Json].map { json =>
          assertEquals(json.hcursor.get[String]("code"), Right("invalid_jobs"))
          assert(json.hcursor.get[String]("message").toOption.get.contains(message))
        }
      }
    }
  }

  test("a finished job immediately frees a slot even when an earlier job is still running") {
    temporary.use { root =>
      val yaml = """version: 1
jobs:
  - name: long
    run: |
      touch long-running
      while [ ! -f release ]; do sleep 0.01; done
      touch long-done
  - name: short
    run: |
      while [ ! -f long-running ]; do sleep 0.01; done
      touch short-done
  - name: unlock
    needs: [short]
    run: |
      test -f short-done
      test ! -f long-done
      touch release
  - name: build
    needs: [long, unlock]
    run: test -f long-done
"""
      for
        shell <- script(yaml, "build", 2)
        result <- execute(shell, root)
      yield
        assertEquals(result.code, 0, result.stderr)
        assert(result.stdout.indexOf("[jobs] start unlock") < result.stdout.indexOf("[jobs] done  long"))
    }
  }

  test("real shell processes respect concurrency limits and shared dependencies run once") {
    val run = """printf '+\n' >> events
sleep 0.1
printf -- '-\n' >> events
"""
    def job(name: String, needs: Vector[String], run: String): Json = Json.obj(
      "name" -> Json.fromString(name), "needs" -> Json.fromValues(needs.map(Json.fromString)), "run" -> Json.fromString(run))
    val leaves = (0 until 6).map(i => s"task-$i").toVector
    val jobs = Vector(job("prepare", Vector.empty, "printf prepared >> prepared.txt")) ++
      leaves.map(name => job(name, Vector("prepare"), run)) :+ job("build", leaves, "test -f prepared.txt")
    val yaml = Json.obj("version" -> Json.fromInt(1), "jobs" -> Json.fromValues(jobs)).noSpaces
    List(1, 2, 5).traverse_ { parallel => temporary.use { root =>
      for
        shell <- script(yaml, "build", parallel)
        result <- execute(shell, root)
      yield
        assertEquals(result.code, 0, result.stderr)
        val active = Files.readAllLines(root.resolve("events")).asScala.scanLeft(0)((count, event) => count + (if event == "+" then 1 else -1))
        assertEquals(active.last, 0)
        assert(active.forall(count => count >= 0 && count <= parallel), active.toString)
        assertEquals(Files.readString(root.resolve("prepared.txt")), "prepared")
        val children = Files.list(root)
        try assert(!children.iterator().asScala.exists(_.getFileName.toString.startsWith("groot-jobs.")))
        finally children.close()
    } }
  }
