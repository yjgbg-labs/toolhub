import { afterAll, expect, test } from "bun:test";
import { ApiClient, ApiError, Catalog } from "../src/api";
import { document, fixture } from "./fixture";

const api = fixture();
afterAll(() => api.server.stop(true));
const client = await ApiClient.connect({ baseUrl: api.baseUrl, headers: { Authorization: "Bearer test" } });

test("HTTP execution preserves base paths, escaping, all parameter locations and repeated query values", async () => {
  const result = await client.call("admin.users.get", {
    path: { id: "a/b +?" }, query: { id: 7, tag: ["a&b", "c+d"], active: false },
    header: { "X-Trace": "trace" }, cookie: { session: "a=b" },
  });
  expect(result.status).toBe(200);
  expect(result.body).toEqual({
    path: "/api/admin/users/get/a%2Fb%20%2B%3F", query: [["id", "7"], ["tag", "a&b"], ["tag", "c+d"], ["active", "false"]],
    method: "POST", trace: "trace", cookie: "session=a=b", auth: "Bearer test",
  });
});

test("missing, mistyped, unknown and constrained inputs fail before sending HTTP", async () => {
  const before = api.requests.length;
  for (const input of [
    {}, { path: { id: "a" }, header: { "X-Trace": "t" }, cookie: { session: "s" }, query: { limit: 0 } },
    { path: { id: "a" }, header: { "X-Trace": "t" }, cookie: { session: "s" }, query: { active: "false" } },
    { surprise: true },
  ]) await expect(client.call("admin.users.get", input)).rejects.toThrow("Invalid input");
  expect(api.requests.length).toBe(before);
});

test("recursive body references validate while server-side defaults remain omitted", async () => {
  const body = { name: "root", children: [{ name: "leaf" }] };
  expect((await client.call("users.create", { body })).body).toEqual(body);
  await expect(client.call("users.create", { body: { name: "a", children: [{}] } })).rejects.toThrow("Invalid input");
  expect(client.catalog.byId.get("users.create")!.inputSchema.$defs.Tree.properties.children.items.$ref).toBe("#/$defs/Tree");
});

test("text, empty and HTTP error responses have consistent outcomes", async () => {
  expect((await client.call("health")).body).toBe("ok");
  expect((await client.call("users.clear")).body).toBeNull();
  try { await client.call("users.fail"); throw new Error("Expected failure"); }
  catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).response).toMatchObject({ status: 400, body: { message: "rejected" } });
  }
});

test("text request bodies are sent verbatim with string schema validation; JSON remains preferred", async () => {
  const sent: { body: string; contentType: string | null }[] = [];
  const textSchema = { type: "string", minLength: 1 };
  const operation = (content: object) => ({ post: {
    operationId: "notes.save", requestBody: { required: true, content }, responses: { "200": { description: "ok" } },
  } });
  const doc = { openapi: "3.1.2", info: { title: "Text", version: "1" },
    components: { schemas: { Text: textSchema } },
    paths: { "/notes": operation({ "text/plain": { schema: { $ref: "#/components/schemas/Text" } } }) },
  };
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch(request) {
    if (new URL(request.url).pathname === "/openapi.json") return Response.json(doc);
    const received = { body: await request.text(), contentType: request.headers.get("content-type") };
    sent.push(received);
    return Response.json(received);
  } });
  try {
    const client = await ApiClient.connect({ baseUrl: server.url.toString() });
    const source = "  const text = '中文🙂';\r\nconsole.log(\"quoted\\n\", text);\n";
    expect((await client.call("notes.save", { body: source })).body).toEqual({ body: source, contentType: "text/plain" });
    for (const input of [{}, { body: 42 }, { body: {} }, { body: "" }]) {
      await expect(client.call("notes.save", input)).rejects.toThrow("Invalid input");
    }
    expect(sent).toHaveLength(1);
    textSchema.type = "object";
    expect(() => new Catalog(doc)).toThrow("text/plain body must have a string schema");
    textSchema.type = "string";
    doc.paths["/notes"] = operation({ "text/plain": { schema: textSchema }, "application/json": { schema: textSchema } });
    const jsonClient = await ApiClient.connect({ baseUrl: server.url.toString() });
    expect((await jsonClient.call("notes.save", { body: source })).body).toEqual({ body: JSON.stringify(source), contentType: "application/json" });
    doc.paths["/notes"] = operation({ "application/json": { schema: true } });
    const anyJsonClient = await ApiClient.connect({ baseUrl: server.url.toString() });
    expect((await anyJsonClient.call("notes.save", { body: 42 })).body).toEqual({ body: "42", contentType: "application/json" });
  } finally { await server.stop(true); }
});

test("ambiguous operation IDs and unsupported contracts fail catalog construction", () => {
  const doc = document();
  doc.paths["/health"].get.operationId = "users.create";
  expect(() => new Catalog(doc)).toThrow("Duplicate operationId");
  doc.paths["/health"].get.operationId = "users..health";
  expect(() => new Catalog(doc)).toThrow("operationId");
});

test("RPC group descriptions are independent of tags and optional for command discovery", () => {
  const { "x-rpc": rpc, ...withoutRpc } = document();
  const catalog = new Catalog(withoutRpc);
  expect(catalog.groupDescriptions.size).toBe(0);
  expect(catalog.operations.find(op => op.id === "admin.users.get")?.segments).toEqual(["admin", "users", "get"]);
  const { tags, ...withoutTags } = document();
  expect(new Catalog(withoutTags).groupDescriptions.get("admin")).toBe("Administration\nAdministrative operations");
  expect(new Catalog({ ...withoutRpc, "x-rpc": { commands: [{ name: "admin" }] } }).groupDescriptions.size).toBe(0);
  expect(() => new Catalog({ ...withoutRpc, "x-rpc": null })).toThrow("x-rpc object");
  expect(() => new Catalog({ ...withoutRpc, "x-rpc": { commands: {} } })).toThrow("commands array");
  expect(() => new Catalog({ ...withoutRpc, "x-rpc": { commands: [{ name: "admin", description: 1 }] } })).toThrow("Invalid RPC group");
  expect(() => new Catalog({ ...withoutRpc, "x-rpc": { commands: [{ name: "admin..users" }] } })).toThrow("Invalid RPC group");
  expect(() => new Catalog({ ...withoutRpc, "x-rpc": { commands: [...rpc.commands, rpc.commands[0]] } })).toThrow("Duplicate RPC group");
});

test("abort propagates to HTTP calls without executing the operation", async () => {
  const controller = new AbortController();
  controller.abort();
  await expect(client.call("health", {}, controller.signal)).rejects.toThrow();
});
