import { afterAll, expect, test } from "bun:test";
import { ApiClient, Catalog } from "../src/api";
import type { Plugin } from "@opencode/plugin";
import type { Info, ToolEditor, ToolContext } from "@opencode/plugin/promise/tool";
import plugin from "../src/index";
import { operationTools } from "../src/tools";
import { document, fixture } from "./fixture";

const api = fixture();
afterAll(() => api.server.stop(true));

test("V2 plugin setup registers all operations for Code Mode with typed inputs, outputs and cleanup", async () => {
  const tools: Info[] = [];
  const namespaces = new Map<string, string | undefined>();
  let disposed = false;
  const cleanup = await plugin.setup({
    options: { baseUrl: api.baseUrl },
    tool: { transform: async (callback: (editor: ToolEditor) => void) => {
      callback({
        add: (tool: Info) => { tools.push(tool); },
        namespace: ({ name, description }: { name: string; description?: string }) => { namespaces.set(name, description); },
        list: () => [], get: () => undefined, update: () => {}, remove: () => {},
      });
      return { dispose: async () => { disposed = true; } };
    } },
  } as unknown as Plugin.Context);
  expect(tools).toHaveLength(5);
  expect(namespaces.get("admin")).toBe("Administration\nAdministrative operations");
  expect(namespaces.get("admin.users")).toBe("Administer users");
  expect(namespaces.get("users")).toBe("Manage users");
  expect(tools.map(tool => [tool.options?.namespace, tool.name].filter(Boolean).join("."))).toEqual([
    "admin.users.get", "health", "users.clear", "users.create", "users.fail",
  ]);
  const health = tools.find(tool => tool.name === "health")!;
  expect(health.options?.namespace).toBeUndefined();
  expect((await health.execute({}, {} as ToolContext)).output).toMatchObject({ status: 200, body: "ok" });
  const get = tools.find(tool => tool.options?.namespace === "admin.users" && tool.name === "get")!;
  expect(get.input).toHaveProperty("properties.id.type", "string");
  expect(get.input).toHaveProperty("properties.revision.type", "integer");
  expect(get.input).toHaveProperty("required", ["id", "X-Trace", "session"]);
  expect(get.input).not.toHaveProperty("properties.query");
  const create = tools.find(tool => tool.options?.namespace === "users" && tool.name === "create")!;
  expect(create.options?.codemode).toBe(true);
  expect(create.input).toHaveProperty("$ref", "#/$defs/Tree");
  expect(create.output).toHaveProperty("properties.status.type", "integer");
  const result = await create.execute({ name: "Ada" }, {} as ToolContext);
  expect(result.output).toMatchObject({ status: 201, body: { name: "Ada" } });
  await expect(create.execute({}, {} as ToolContext)).rejects.toThrow("Invalid input");
  await cleanup!();
  expect(disposed).toBe(true);
});

test("normalization collisions fail rather than silently overriding a tool", () => {
  const doc = document();
  doc.paths["/health"].get.operationId = "admin_users.get";
  const client = new ApiClient(new Catalog(doc), { baseUrl: api.baseUrl });
  expect(() => operationTools(client)).toThrow("Code Mode name collision");
});

test("sessionId and cwd parameters are hidden from tool schema and implicitly supplied from ToolContext", async () => {
  const received: { session: string | null; cwd: string | null; body: unknown } = { session: null, cwd: null, body: null };
  const srv = Bun.serve({
    hostname: "127.0.0.1",
    port: 0,
    async fetch(req) {
      const u = new URL(req.url);
      if (u.pathname === "/notify/at") {
        received.session = u.searchParams.get("sessionId");
        received.cwd = u.searchParams.get("cwd");
        received.body = await req.json();
        return Response.json({ ok: true });
      }
      return new Response("not found", { status: 404 });
    },
  });
  try {
    const doc = document() as any;
    doc.paths["/notify/at"] = {
      post: {
        operationId: "notify.at",
        parameters: [
          { name: "sessionId", in: "query", required: true, schema: { type: "string" } },
          { name: "cwd", in: "query", required: false, schema: { type: "string" } },
        ],
        requestBody: {
          required: true,
          content: {
            "application/json": {
              schema: {
                type: "object",
                required: ["at", "message"],
                properties: { at: { type: "string" }, message: { type: "string" } },
              },
            },
          },
        },
        responses: { "200": { description: "ok", content: { "application/json": { schema: { type: "object" } } } } },
      },
    };
    const client = new ApiClient(new Catalog(doc), { baseUrl: srv.url.toString().replace(/\/$/, "") });
    const notifyAt = operationTools(client).find(t => t.options?.namespace === "notify" && t.name === "at")!;
    expect(notifyAt.input).toHaveProperty("required", ["at", "message"]);
    expect(notifyAt.input).not.toHaveProperty("properties.sessionId");
    expect(notifyAt.input).not.toHaveProperty("properties.body");
    await notifyAt.execute({ at: "+5m", message: "ping" }, { sessionID: "ses_auto_42" } as unknown as ToolContext);
    expect(received.session).toBe("ses_auto_42");
    expect(received.cwd).toBe(process.cwd());
    expect(received.body).toEqual({ at: "+5m", message: "ping" });
  } finally {
    srv.stop(true);
  }
});
