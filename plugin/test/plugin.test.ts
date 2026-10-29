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
  const create = tools.find(tool => tool.options?.namespace === "users" && tool.name === "create")!;
  expect(create.options?.codemode).toBe(true);
  expect(create.input).toHaveProperty("properties.body.$ref", "#/$defs/Tree");
  expect(create.output).toHaveProperty("properties.status.type", "integer");
  const result = await create.execute({ body: { name: "Ada" } }, {} as ToolContext);
  expect(result.output).toMatchObject({ status: 201, body: { name: "Ada" } });
  await expect(create.execute({ body: {} }, {} as ToolContext)).rejects.toThrow("Invalid input");
  await cleanup!();
  expect(disposed).toBe(true);
});

test("normalization collisions fail rather than silently overriding a tool", () => {
  const doc = document();
  doc.paths["/health"].get.operationId = "admin_users.get";
  const client = new ApiClient(new Catalog(doc), { baseUrl: api.baseUrl });
  expect(() => operationTools(client)).toThrow("Code Mode name collision");
});
