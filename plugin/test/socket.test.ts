import { afterAll, expect, test } from "bun:test";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { ApiClient, defaultBaseUrl, socketUrl, transport } from "../src/api";
import { document } from "./fixture";

const runtime = await mkdtemp("/tmp/opencode/sprout-uds-");
await mkdir(join(runtime, "sprout-http"));
function serve(path: string, name: string) {
  return Bun.serve({ unix: path, async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/openapi.json") return Response.json(document());
    if (url.pathname === "/health") return new Response(name);
    if (url.pathname === "/fail") return Response.json({ message: "rejected" }, { status: 400 });
    if (url.pathname === "/users/create") return Response.json(await request.json(), { status: 201 });
    return new Response(null, { status: 404 });
  } });
}
const first = serve(join(runtime, "sprout-http/http.sock"), "first");
const secondUrl = socketUrl(join(runtime, "other #%.sock"));
const second = serve(join(runtime, "other #%.sock"), "second");
afterAll(async () => { await Promise.all([first.stop(true), second.stop(true)]); await rm(runtime, { recursive: true, force: true }); });

test("plugin clients default to the runtime socket and support independent socket overrides", async () => {
  const env = { XDG_RUNTIME_DIR: runtime };
  const firstClient = await ApiClient.connect({ baseUrl: defaultBaseUrl(env) });
  const secondClient = await ApiClient.connect({ baseUrl: secondUrl });
  expect((await firstClient.call("health")).body).toBe("first");
  expect((await secondClient.call("health")).body).toBe("second");
  expect((await firstClient.call("health")).body).toBe("first");
  expect(defaultBaseUrl({ HOME: "/home/example" })).toBe("unix:///home/example/.local/run/sprout-http/http.sock");
});

test("socket discovery and requests preserve JSON bodies, HTTP errors and cancellation", async () => {
  const client = await ApiClient.connect({ baseUrl: defaultBaseUrl({ XDG_RUNTIME_DIR: runtime }) });
  expect((await client.call("users.create", { body: { name: "Ada" } })).body).toEqual({ name: "Ada" });
  await expect(client.call("users.fail")).rejects.toThrow("HTTP 400");
  const controller = new AbortController();
  controller.abort();
  await expect(client.call("health", {}, controller.signal)).rejects.toThrow();
});

test("socket addresses reject remote hosts, relative paths and URL modifiers", () => {
  for (const value of ["unix://remote/tmp/a.sock", "unix:relative.sock", "unix:///", "unix:///tmp/a.sock?x", "unix:///tmp/%00", "file:///tmp/a.sock"]) {
    expect(() => transport(value)).toThrow();
  }
});
