import { expect, test } from "bun:test";
import type { Plugin } from "@opencode/plugin";
import type { Info, ToolEditor, ToolContext } from "@opencode/plugin/promise/tool";
import { ApiClient, Catalog } from "../src/api";
import plugin from "../src/index";

function document() {
  const responses = { "200": { description: "ok", content: { "application/json": { schema: { type: "string" } } } } };
  const operation = (id: string, clients?: string[]) => ({ get: {
    operationId: id, responses, ...(clients === undefined ? {} : { "x-expose-on": clients }),
  } });
  return {
    openapi: "3.1.2", info: { title: "Exposure", version: "1" },
    "x-rpc": { commands: [
      { name: "private", description: "Hidden namespace" },
      { name: "mixed", description: "Visible children" },
    ] },
    paths: {
      "/legacy": operation("legacy"),
      "/all": operation("all", ["cli", "codemode", "graaljs"]),
      "/code": operation("code", ["codemode"]),
      "/mixed": operation("mixed", ["cli"]),
      "/mixed/read": operation("mixed.read", ["codemode", "graaljs"]),
      "/empty": operation("empty", []),
      "/future": operation("future", ["future-client"]),
      // A hidden operation must not require Code Mode to understand its contract.
      "/private/read": { post: {
        operationId: "private.read", "x-expose-on": ["graaljs"], responses,
        requestBody: { content: { "application/octet-stream": { schema: { type: "string" } } } },
      } },
    },
  };
}

test("Code Mode filters discovery and direct calls before compiling hidden contracts", async () => {
  const catalog = new Catalog(document());
  expect(catalog.operations.map(op => op.id)).toEqual(["all", "code", "legacy", "mixed.read"]);
  expect([...catalog.groupDescriptions]).toEqual([["mixed", "Visible children"]]);
  let requests = 0;
  const client = new ApiClient(catalog, { baseUrl: "http://localhost", fetch: async () => {
    requests++;
    return Response.json("ok");
  } });
  for (const id of ["mixed", "private.read", "empty", "future"]) {
    await expect(client.call(id)).rejects.toThrow("Unknown operation");
  }
  expect(requests).toBe(0);
  expect((await client.call("mixed.read")).body).toBe("ok");
  expect(requests).toBe(1);
});

test("plugin registration exposes only Code Mode tools and reachable namespaces", async () => {
  const doc = document();
  const tools: Info[] = [];
  const namespaces: string[] = [];
  let disposed = false;
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
    return new URL(request.url).pathname === "/openapi.json" ? Response.json(doc) : Response.json("ok");
  } });
  try {
    const cleanup = await plugin.setup({
      options: { baseUrl: server.url.toString() },
      tool: { transform: async (callback: (editor: ToolEditor) => void) => {
        callback({
          add: (tool: Info) => { tools.push(tool); },
          namespace: ({ name }: { name: string }) => { namespaces.push(name); },
          list: () => [], get: () => undefined, update: () => {}, remove: () => {},
        });
        return { dispose: async () => { disposed = true; } };
      } },
    } as unknown as Plugin.Context);
    expect(tools.map(tool => [tool.options?.namespace, tool.name].filter(Boolean).join(".")))
      .toEqual(["all", "code", "legacy", "mixed.read"]);
    expect(namespaces).toEqual(["mixed"]);
    expect((await tools.at(-1)!.execute({}, {} as ToolContext)).output).toMatchObject({ body: "ok" });
    await cleanup!();
    expect(disposed).toBe(true);
  } finally { await server.stop(true); }
});

test("malformed exposure metadata fails instead of silently exposing commands", () => {
  for (const clients of [null, "codemode", {}, [1], ["codemode", null]]) {
    const doc = document();
    Object.assign(doc.paths["/code"].get, { "x-expose-on": clients });
    expect(() => new Catalog(doc)).toThrow("x-expose-on");
  }
});
