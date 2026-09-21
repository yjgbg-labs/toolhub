import { afterAll, expect, test } from "bun:test";
import { ApiClient, ApiError, Catalog } from "../src/api";
import { run } from "../src/main";
import { document, fixture } from "./fixture";

const api = fixture();
const env = { SPROUT_BASE_URL: api.baseUrl };
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

test("hierarchical CLI help lists groups and location-qualified ambiguous parameters", async () => {
  const root = await run(["--help"], env);
  expect(root).toContain("admin  Administration");
  expect(root).not.toContain("Administrative operations");
  expect(root).not.toContain("unused");
  expect(root).toContain("Environment: SPROUT_BASE_URL");
  expect(root).not.toMatch(/--url|--schema|--timeout|SPROUT_URL|SPROUT_OPENAPI_URL|SPROUT_TOKEN/);
  expect(await run(["admin", "--help"], env)).toContain("Administration\nAdministrative operations");
  expect(await run(["admin", "--help"], env)).toContain("users  Administer users");
  expect(await run(["admin", "users", "--help"], env)).toContain("get  Get a user");
  const leaf = await run(["admin", "users", "get", "--help"], env);
  expect(leaf).toContain("--path.id <string>");
  expect(leaf).toContain("--query.id <integer>");
  expect(leaf).toContain("default 20");
});

test("CLI flags convert types and support repeated arrays, JSON bodies and complete inputs", async () => {
  const result = JSON.parse(await run(["admin", "users", "get", "--path.id", "9", "--query.id=2", "--tag", "a", "--tag", "b", "--active", "false", "--X-Trace", "trace", "--session", "s"], env));
  expect(result.query).toEqual([["id", "2"], ["tag", "a"], ["tag", "b"], ["active", "false"]]);
  expect(JSON.parse(await run(["users", "create", "--body", '{"name":"Ada"}'], env))).toEqual({ name: "Ada" });
  expect(JSON.parse(await run(["users", "create", "--input", '{"body":{"name":"Ada"}}'], env))).toEqual({ name: "Ada" });
  await expect(run(["users", "create", "--typo", "x"], env)).rejects.toThrow("Unknown option");
  for (const name of ["url", "schema", "timeout"]) {
    await expect(run(["health", `--${name}`, "unused"], env)).rejects.toThrow(`Unknown option --${name}`);
  }
});

test("CLI names missing required arguments, shows usage and does not execute an incomplete operation", async () => {
  const before = api.requests.length;
  const missing = "Missing required arguments: <id>, --X-Trace, --session\nUsage: sprout admin users get <id> [options]";
  await expect(run(["admin", "users", "get"], env)).rejects.toThrow(missing);
  await expect(run(["admin", "users", "get", "--input", '{"path":{}}'], env)).rejects.toThrow(missing);
  await expect(run(["admin", "users", "get", "9", "--X-Trace", "t"], env)).rejects.toThrow("Missing required argument: --session");
  await expect(run(["users", "create"], env)).rejects.toThrow("Missing required argument: --body <json|@file|->");
  expect(api.requests.length).toBe(before);

  const input = { path: { id: "9" }, header: { "X-Trace": "t" }, cookie: { session: "s" } };
  for (const args of [
    ["9", "--X-Trace", "t", "--session", "s"],
    ["--path.id", "9", "--X-Trace", "t", "--session", "s"],
    ["--input", JSON.stringify(input)],
  ]) {
    expect(JSON.parse(await run(["admin", "users", "get", ...args], env)).path).toBe("/api/admin/users/get/9");
  }
});

test("CLI process reports HTTP errors on stderr with a failing exit code", async () => {
  const child = Bun.spawn([process.execPath, "cli/src/main.ts", "users", "fail"], { env: { ...process.env, ...env }, stdout: "pipe", stderr: "pipe" });
  const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
  expect(code).toBe(1);
  expect(stdout).toBe("");
  expect(stderr).toContain("HTTP 400");
  expect(stderr).toContain("rejected");
});

test("CLI discovers changed operation IDs from OpenAPI on each invocation", async () => {
  const dynamic = fixture();
  const env = { SPROUT_BASE_URL: dynamic.baseUrl };
  try {
    dynamic.doc.paths["/health"].get.operationId = "reports.daily.summary";
    dynamic.doc.tags.push({ name: "reports", description: "Report operations" }, { name: "reports.daily", description: "Daily reports" });
    expect(await run(["--help"], env)).toContain("reports  Report operations");
    expect(await run(["reports", "--help"], env)).toContain("daily  Daily reports");
    expect(await run(["reports", "daily", "--help"], env)).toContain("summary");
    expect(await run(["reports", "daily", "summary"], env)).toBe("ok");
    await expect(run(["health"], env)).rejects.toThrow("Unknown command");
    dynamic.doc.paths["/health"].get.operationId = "reports.latest";
    expect(await run(["reports", "latest"], env)).toBe("ok");
    expect(await run(["reports", "--help"], env)).not.toContain("Daily reports");
    await expect(run(["reports", "daily", "summary"], env)).rejects.toThrow("Unknown command");
  } finally { dynamic.server.stop(true); }
});

test("ambiguous operation IDs and unsupported contracts fail catalog construction", () => {
  const doc = document();
  doc.paths["/health"].get.operationId = "users.create";
  expect(() => new Catalog(doc)).toThrow("Duplicate operationId");
  doc.paths["/health"].get.operationId = "users..health";
  expect(() => new Catalog(doc)).toThrow("operationId");
});

test("group descriptions are optional and malformed tag metadata is rejected", () => {
  const { tags, ...withoutTags } = document();
  expect(new Catalog(withoutTags).groupDescriptions.size).toBe(0);
  expect(() => new Catalog({ ...withoutTags, tags: {} })).toThrow("tags array");
  expect(() => new Catalog({ ...withoutTags, tags: [{ name: "admin", description: 1 }] })).toThrow("Invalid OpenAPI tag");
  expect(() => new Catalog({ ...withoutTags, tags: [...tags, tags[0]] })).toThrow("Duplicate OpenAPI tag");
});

test("abort propagates to HTTP calls without executing the operation", async () => {
  const controller = new AbortController();
  controller.abort();
  await expect(client.call("health", {}, controller.signal)).rejects.toThrow();
});
