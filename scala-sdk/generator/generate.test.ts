import { describe, expect, test } from "bun:test";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { generate } from "./generate";
import { fixture } from "./fixtures";
import { main } from "./main";

describe("OpenAPI to Scala", () => {
  test("deterministic local generation, preserves handwritten files", async () => {
    const directory = await mkdtemp("/tmp/opencode/scala-sdk-generator-");
    try {
      const schema = join(directory, "openapi.json");
      await writeFile(schema, JSON.stringify(fixture));
      const args = ["--schema", schema, "--package", "example.api", "--output", directory];
      await main(args);
      const first = await readFile(join(directory, "Models.scala"), "utf8");
      await main(args);
      expect(await readFile(join(directory, "Models.scala"), "utf8")).toBe(first);
      await writeFile(join(directory, "Client.scala"), "// handwritten\n");
      await expect(main(args)).rejects.toThrow("handwritten");
      expect(await readFile(join(directory, "Client.scala"), "utf8")).toBe("// handwritten\n");
    } finally { await rm(directory, { recursive: true, force: true }); }
  });

  test("rejects unsupported contracts with useful diagnostics", () => {
    for (const [patch, message] of [
      [{ openapi: "3.0.3" }, "3.1"],
      [{ components: { schemas: { Bad: { allOf: [{ type: "string" }] } } } }, "allOf"],
      [{ components: { schemas: { Bad: { $ref: "other.json#/Thing" } } } }, "reference"],
    ] as const) expect(() => generate({ ...fixture, ...patch }, "example.api")).toThrow(message);
  });

  test("rejects operation collisions and never guesses HTTP methods from names", () => {
    const endpoint = (operationId: string) => ({ operationId, responses: { "200": { description: "OK" } } });
    expect(() => generate({ ...fixture, paths: { "/a": { get: endpoint("a") }, "/b": { get: endpoint("a.b") } } }, "test")).toThrow("namespace");
    const output = generate({ ...fixture, paths: { "/remove": { get: endpoint("delete") } } }, "test");
    expect(output["Calls.scala"]).toContain('HttpRequest("GET", "/remove"');
    expect(output["Client.scala"]).toContain("def `delete`");
  });
});
