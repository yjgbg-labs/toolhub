package groot.jobs

import munit.FunSuite

class JobsConfigSuite extends FunSuite:
  test("planning selects only the target graph in dependency order and deduplicates shared jobs") {
    val yaml = """version: 1
jobs:
  - {name: install, run: install}
  - {name: lint, needs: [install], run: lint}
  - {name: test, needs: [install], run: test}
  - {name: build, needs: [lint, test], run: build}
  - {name: unrelated, run: unrelated}
"""
    assertEquals(JobsConfig.plan(yaml, "build").map(_.name), Vector("install", "lint", "test", "build"))
  }

  test("configuration errors and unsupported imports are rejected before script generation") {
    val cases = List(
      "[]" -> "expected an object",
      "version: 2\njobs: []" -> "version must be 1",
      "version: 1" -> "jobs must be an array",
      "version: 1\nimports: []\njobs: []" -> "imports is not supported",
      "version: 1\njobs: [{name: 'build@common', run: echo}]" -> "namespaces are not supported",
      "version: 1\njobs: [{name: build, run: ' '}]" -> "run must be a non-empty string",
      "version: 1\njobs: [{name: build, run: echo, needs: [x, x]}]" -> "needs contains duplicates",
      "version: 1\njobs: [{name: build, run: echo, needs: [build]}]" -> "cannot depend on itself",
      "version: 1\njobs: [{name: build, run: echo, needs: [missing]}]" -> "missing job",
      "version: 1\njobs: [{name: build, run: echo}, {name: build, run: echo}]" -> "duplicate job",
      "version: 1\njobs: [{name: build, run: echo, needs: [b]}, {name: b, run: echo, needs: [build]}]" -> "circular job dependency: build -> b -> build",
      "version: 1\njobs: [{name: build, run: echo}, {name: a, run: echo, needs: [b]}, {name: b, run: echo, needs: [a]}]" -> "circular job dependency: a -> b -> a",
      "version: 1\njobs: []" -> "no job named",
      "version: 1\nversion: 1\njobs: []" -> "invalid YAML"
    )
    cases.foreach { (yaml, message) =>
      val error = intercept[InvalidJobs](JobsConfig.plan(yaml, "build"))
      assert(error.getMessage.contains(message), error.getMessage)
    }
  }

  test("YAML 1.2 preserves string names such as on and supports multiline scripts and anchors") {
    val yaml = """version: 1
jobs:
  - name: on
    run: &script |
      printf '%s\n' '空 "quoted"'
      echo complete
  - name: build
    needs: [on]
    run: *script
"""
    val jobs = JobsConfig.plan(yaml, "build")
    assertEquals(jobs.head.name, "on")
    assertEquals(jobs.head.run, "printf '%s\\n' '空 \"quoted\"'\necho complete\n")
    assertEquals(jobs.last.run, jobs.head.run)
  }
