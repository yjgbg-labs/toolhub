import { afterAll, expect, test } from "bun:test";
import { ApiClient, Catalog } from "@sprout/cli/api";
import type { Plugin } from "@opencode/plugin";
import type { Info, ToolEditor, ToolContext } from "@opencode/plugin/promise/tool";
import plugin from "../src/index";
import { operationTools } from "../src/tools";
import { document, fixture } from "../../cli/test/fixture";

const api = fixture();
afterAll(() => api.server.stop(true));

test("V2 plugin setup registers all operations for Code Mode with typed inputs, outputs and cleanup", async () => {
  const tools: Info[] = [];
  const namespaces: string[] = [];
  let disposed = false;
  const cleanup = await plugin.setup({
    options: { baseUrl: api.baseUrl, namespace: "test" },
    tool: { transform: async (callback: (editor: ToolEditor) => void) => {
      callback({
        add: (tool: Info) => { tools.push(tool); },
        namespace: ({ name }: { name: string }) => { namespaces.push(name); },
        list: () => [], get: () => undefined, update: () => {}, remove: () => {},
      });
      return { dispose: async () => { disposed = true; } };
    } },
  } as unknown as Plugin.Context);
  expect(tools).toHaveLength(5);
  expect(namespaces).toContain("test_admin_users");
  const create = tools.find(tool => tool.options?.namespace === "test_users" && tool.name === "create")!;
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
