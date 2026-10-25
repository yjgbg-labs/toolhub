#!/usr/bin/env bun
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { generate } from "../generator/generate";
import { fixture } from "../generator/fixtures";

const directory = await mkdtemp("/tmp/opencode/sdk-generator-platforms-");
try {
  await Bun.write(`${directory}/schema.json`, JSON.stringify(fixture));
  const expected = generate(fixture, "sprout.fixture");
  for (const [target, command] of [
    ["jvm", ["groot", "use-jdk", "25", "--", "java", "-jar", "scala-sdk/dist/generator.jar"]],
    ["native", ["scala-sdk/dist/generator-native"]],
  ] as const) {
    const output = `${directory}/${target}`;
    const child = Bun.spawn([...command, `${directory}/schema.json`, "sprout.fixture", output], { stdout: "pipe", stderr: "pipe" });
    const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
    assert.equal(code, 0, `${stdout}\n${stderr}`);
    for (const [name, contents] of Object.entries(expected)) assert.equal(await Bun.file(`${output}/${name}`).text(), contents, `${target}/${name}`);
  }
  console.log("JVM / Scala.js / Native generators produced identical models, codecs and typed calls");
} finally { await rm(directory, { recursive: true, force: true }); }
