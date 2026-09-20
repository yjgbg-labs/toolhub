import { afterAll, expect, test } from "bun:test";
import { ApiClient, ApiError, Catalog } from "../src/api";
import { run } from "../src/main";
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

test("hierarchical CLI help lists groups and location-qualified ambiguous parameters", async () => {
  const root = await run(["--url", api.baseUrl, "--help"], {});
  expect(root).toContain("admin  Administration");
  expect(root).not.toContain("Administrative operations");
  expect(root).not.toContain("unused");
  expect(await run(["--url", api.baseUrl, "admin", "--help"], {})).toContain("Administration\nAdministrative operations");
  expect(await run(["--url", api.baseUrl, "admin", "--help"], {})).toContain("users  Administer users");
  expect(await run(["--url", api.baseUrl, "admin", "users", "--help"], {})).toContain("get  Get a user");
  const leaf = await run(["--url", api.baseUrl, "admin", "users", "get", "--help"], {});
  expect(leaf).toContain("--path.id <string>");
  expect(leaf).toContain("--query.id <integer>");
  expect(leaf).toContain("default 20");
});

test("CLI flags convert types and support repeated arrays, JSON bodies and complete inputs", async () => {
  const result = JSON.parse(await run(["admin", "users", "get", "--url", api.baseUrl, "--path.id", "9", "--query.id=2", "--tag", "a", "--tag", "b", "--active", "false", "--X-Trace", "trace", "--session", "s"], {}));
  expect(result.query).toEqual([["id", "2"], ["tag", "a"], ["tag", "b"], ["active", "false"]]);
  expect(JSON.parse(await run(["users", "create", "--body", '{"name":"Ada"}'], { SPROUT_URL: api.baseUrl }))).toEqual({ name: "Ada" });
  expect(JSON.parse(await run(["--url", api.baseUrl, "users", "create", "--input", '{"body":{"name":"Ada"}}'], {}))).toEqual({ name: "Ada" });
  await expect(run(["--url", api.baseUrl, "users", "create", "--typo", "x"], {})).rejects.toThrow("Unknown option");
});

test("CLI process reports HTTP errors on stderr with a failing exit code", async () => {
  const child = Bun.spawn([process.execPath, "cli/src/main.ts", "--url", api.baseUrl, "users", "fail"], { stdout: "pipe", stderr: "pipe" });
  const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
  expect(code).toBe(1);
  expect(stdout).toBe("");
  expect(stderr).toContain("HTTP 400");
  expect(stderr).toContain("rejected");
});

test("CLI discovers changed operation IDs from OpenAPI on each invocation", async () => {
  const dynamic = fixture();
  const env = { SPROUT_URL: dynamic.baseUrl };
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
