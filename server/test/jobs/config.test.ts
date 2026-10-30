import { afterEach, expect, test } from "bun:test";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { loadJobsConfig } from "../../src/main/typescript/jobs/config";

const directories: string[] = [];
afterEach(() => Promise.all(directories.splice(0).map(path => rm(path, { recursive: true, force: true }))));
async function fixture(files: Record<string, string>) {
  const root = await mkdtemp("/tmp/opencode/jobs-config-");
  directories.push(root);
  for (const [name, content] of Object.entries(files)) {
    const path = join(root, name);
    await mkdir(join(path, ".."), { recursive: true });
    await writeFile(path, content);
  }
  return { root, entry: join(root, "jobs.yaml") };
}

test("recursive imports resolve against each file and compose dependency namespaces", async () => {
  const { root, entry } = await fixture({
    "jobs.yaml": `version: 1
imports: [{ path: ./jobs/build.yaml, namespace: pipeline }]
jobs: [{ name: deploy, needs: [build@pipeline], run: deploy }]
`,
    "jobs/build.yaml": `version: 1
imports: [{ path: ./common.yaml, namespace: common }]
jobs: [{ name: build, needs: [install@common, test@common], run: build }]
`,
    "jobs/common.yaml": `version: 1
jobs:
  - { name: install, run: install }
  - { name: test, needs: [install], run: test }
`,
  });
  expect(await loadJobsConfig(entry)).toEqual({ rootDir: root, jobs: [
    { name: "install@pipeline.common", needs: [], run: "install" },
    { name: "test@pipeline.common", needs: ["install@pipeline.common"], run: "test" },
    { name: "build@pipeline", needs: ["install@pipeline.common", "test@pipeline.common"], run: "build" },
    { name: "deploy", needs: ["build@pipeline"], run: "deploy" },
  ] });
});

test("imports deduplicate the same file and namespace while allowing multiple namespaces", async () => {
  const { entry } = await fixture({
    "jobs.yaml": `version: 1
imports:
  - { path: ./common.yaml, namespace: left }
  - { path: ./common.yaml, namespace: left }
  - { path: ./common.yaml, namespace: right }
jobs: [{ name: all, needs: [build@left, build@right], run: all }]
`,
    "common.yaml": "version: 1\njobs: [{ name: build, run: build }]\n",
  });
  expect((await loadJobsConfig(entry)).jobs.map(job => job.name)).toEqual(["build@left", "build@right", "all"]);
});

test("duplicate qualified names and import cycles fail before execution", async () => {
  const { entry, root } = await fixture({
    "jobs.yaml": `version: 1
imports:
  - { path: ./first.yaml, namespace: shared }
  - { path: ./second.yaml, namespace: shared }
jobs: []
`,
    "first.yaml": "version: 1\njobs: [{ name: build, run: first }]\n",
    "second.yaml": "version: 1\njobs: [{ name: build, run: second }]\n",
  });
  await expect(loadJobsConfig(entry)).rejects.toThrow('duplicate job "build@shared"');
  await writeFile(join(root, "first.yaml"), "version: 1\nimports: [{ path: ./jobs.yaml, namespace: root }]\njobs: []\n");
  await expect(loadJobsConfig(entry)).rejects.toThrow("circular import");
});

test("malformed configuration, missing dependencies and dependency cycles are rejected", async () => {
  const { entry } = await fixture({});
  await expect(loadJobsConfig(entry)).rejects.toThrow("cannot read");
  const cases: [string, string][] = [
    ["jobs: [", "invalid YAML"],
    ["[]", "expected an object"],
    ["version: 2\njobs: []", "version must be 1"],
    ["version: 1", "jobs must be an array"],
    ["version: 1\nimports: {}\njobs: []", "imports must be an array"],
    ["version: 1\nimports: [./common.yaml]\njobs: []", "imports[0] must be an object"],
    ["version: 1\nimports: [{path: /absolute, namespace: ok}]\njobs: []", "path must be relative"],
    ["version: 1\nimports: [{path: ./file, namespace: invalid.name}]\njobs: []", "namespace must contain only"],
    ["version: 1\njobs: [{name: 'bad@name', run: echo}]", 'name cannot contain "@"'],
    ["version: 1\njobs: [{name: build, run: ' '}]", "run must be a non-empty string"],
    ["version: 1\njobs: [{name: build, run: echo, needs: [x@bad..namespace]}]", "valid job or job@namespace"],
    ["version: 1\njobs: [{name: build, run: echo, needs: [x, x]}]", "needs contains duplicates"],
    ["version: 1\njobs: [{name: build, run: echo, needs: [build]}]", "cannot depend on itself"],
    ["version: 1\njobs: [{name: build, run: echo, needs: [missing]}]", 'missing job "missing"'],
    ["version: 1\njobs: [{name: a, run: echo, needs: [b]}, {name: b, run: echo, needs: [a]}]", "circular job dependency: a -> b -> a"],
  ];
  for (const [yaml, error] of cases) {
    await writeFile(entry, yaml);
    await expect(loadJobsConfig(entry)).rejects.toThrow(error);
  }
});
