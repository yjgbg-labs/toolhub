import { afterAll, expect, test } from "bun:test";
import { ApiClient, ApiError, Catalog } from "../src/api";
import { document, fixture } from "./fixture";

const api = fixture();
afterAll(() => api.server.stop(true));
const client = await ApiClient.connect({ baseUrl: api.baseUrl, headers: { Authorization: "Bearer test" } });

test("HTTP execution preserves base paths, escaping, all parameter locations and repeated query values", async () => {
  const result = await client.call("admin.users.get", {
    id: "a/b +?", revision: 7, tag: ["a&b", "c+d"], active: false,
    "X-Trace": "trace", session: "a=b",
  });
  expect(result.status).toBe(200);
  expect(result.body).toEqual({
    path: "/api/admin/users/get/a%2Fb%20%2B%3F", query: [["revision", "7"], ["tag", "a&b"], ["tag", "c+d"], ["active", "false"]],
    method: "POST", trace: "trace", cookie: "session=a=b", auth: "Bearer test",
  });
});

test("missing, mistyped, unknown and constrained inputs fail before sending HTTP", async () => {
  const before = api.requests.length;
  for (const input of [
    {}, { id: "a", "X-Trace": "t", session: "s", limit: 0 },
    { id: "a", "X-Trace": "t", session: "s", active: "false" },
    { id: "a", "X-Trace": "t", session: "s", query: { limit: 1 } },
    { surprise: true },
  ]) await expect(client.call("admin.users.get", input)).rejects.toThrow("Invalid input");
  expect(api.requests.length).toBe(before);
});

test("recursive body references validate while server-side defaults remain omitted", async () => {
  const body = { name: "root", children: [{ name: "leaf" }] };
  expect((await client.call("users.create", body)).body).toEqual(body);
  await expect(client.call("users.create", { name: "a", children: [{}] })).rejects.toThrow("Invalid input");
  await expect(client.call("users.create", { body })).rejects.toThrow("Invalid input");
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
    expect((await client.call("notes.save", source)).body).toEqual({ body: source, contentType: "text/plain" });
    for (const input of [undefined, null, {}, 42, "", { body: source }]) {
      await expect(client.call("notes.save", input)).rejects.toThrow("Invalid input");
    }
    expect(sent).toHaveLength(1);
    textSchema.type = "object";
    expect(() => new Catalog(doc)).toThrow("text/plain body must have a string schema");
    textSchema.type = "string";
    doc.paths["/notes"] = operation({ "text/plain": { schema: textSchema }, "application/json": { schema: textSchema } });
    const jsonClient = await ApiClient.connect({ baseUrl: server.url.toString() });
    expect((await jsonClient.call("notes.save", source)).body).toEqual({ body: JSON.stringify(source), contentType: "application/json" });
    doc.paths["/notes"] = operation({ "application/json": { schema: true } });
    const anyJsonClient = await ApiClient.connect({ baseUrl: server.url.toString() });
    expect((await anyJsonClient.call("notes.save", 42)).body).toEqual({ body: "42", contentType: "application/json" });
  } finally { await server.stop(true); }
});

test("ambiguous operation IDs and unsupported contracts fail catalog construction", () => {
  const doc = document();
  doc.paths["/health"].get.operationId = "users.create";
  expect(() => new Catalog(doc)).toThrow("Duplicate operationId");
  doc.paths["/health"].get.operationId = "users..health";
  expect(() => new Catalog(doc)).toThrow("operationId");
});

test("parameter names are unique across locations and body is reserved when a request body exists", () => {
  const doc = document();
  doc.paths["/admin/users/get/{id}"].post.parameters[1]!.name = "id";
  expect(() => new Catalog(doc)).toThrow("Duplicate SDK parameter name");
  const reserved = { ...document(), paths: { "/mixed": { post: {
    operationId: "mixed", parameters: [{ name: "body", in: "query", schema: { type: "string" } }],
    requestBody: { content: { "application/json": { schema: { type: "object" } } } },
    responses: { "200": { description: "ok" } },
  } } } };
  expect(() => new Catalog(reserved)).toThrow("reserved body");
  const repeated = document();
  const params = repeated.paths["/admin/users/get/{id}"].post.parameters;
  params.push(params[1]!);
  expect(() => new Catalog(repeated)).toThrow("Duplicate SDK parameter name");
  params.pop();
  const inherited = { ...repeated, paths: { "/health": {
    parameters: [{ name: "limit", in: "query", schema: { type: "integer", minimum: 1 } }],
    get: { ...repeated.paths["/health"].get, parameters: [
      { name: "limit", in: "query", schema: { type: "integer", minimum: 2 } },
    ] },
  } } };
  expect(new Catalog(inherited).byId.get("health")!.inputSchema.properties.limit.minimum).toBe(2);
});

test("mixed inputs retain body even when optional parameters are omitted, and body-only values are never reinterpreted", async () => {
  const sent: { url: string; body: string | undefined; type: string | null }[] = [];
  const body = { content: { "application/json": { schema: { type: ["object", "array", "null", "boolean", "number"] } } } };
  const responses = { "200": { description: "ok" } };
  const catalog = new Catalog({ openapi: "3.1.2", paths: {
    "/mixed": { post: { operationId: "mixed", parameters: [
      { name: "name", in: "query", schema: { type: "string" } },
    ], requestBody: { ...body, required: true }, responses } },
    "/only": { post: { operationId: "only", requestBody: body, responses } },
    "/named": { get: { operationId: "named", parameters: [
      { name: "body", in: "query", required: true, schema: { type: "string" } },
    ], responses } },
  } });
  const client = new ApiClient(catalog, { baseUrl: "http://example.com", fetch: async (url, init) => {
    sent.push({ url: String(url), body: init?.body as string | undefined, type: new Headers(init?.headers).get("content-type") });
    return Response.json({ ok: true });
  } });
  expect(catalog.byId.get("mixed")!.inputSchema.required).toEqual(["body"]);
  expect(catalog.byId.get("mixed")!.inputSchema.properties.name.type).toBe("string");
  await client.call("mixed", { name: "a&b", body: { value: 1 } });
  await client.call("mixed", { body: { name: "inside" } });
  await expect(client.call("mixed", { name: "missing body" })).rejects.toThrow("Invalid input");
  await expect(client.call("mixed", { query: { name: "old" }, body: {} })).rejects.toThrow("Invalid input");
  const values = [{ body: "business field", query: { name: "nested" } }, null, [], [1, 2], false, 0];
  for (const value of values) await client.call("only", value);
  await client.call("only");
  await client.call("named", { body: "query value" });
  expect(sent).toEqual([
    { url: "http://example.com/mixed?name=a%26b", body: '{"value":1}', type: "application/json" },
    { url: "http://example.com/mixed", body: '{"name":"inside"}', type: "application/json" },
    ...values.map(value => ({ url: "http://example.com/only", body: JSON.stringify(value), type: "application/json" })),
    { url: "http://example.com/only", body: undefined, type: null },
    { url: "http://example.com/named?body=query+value", body: undefined, type: null },
  ]);
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
