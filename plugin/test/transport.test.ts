import { afterAll, expect, test } from "bun:test";
import { ApiClient, defaultBaseUrl, transport } from "../src/api";
import { sse } from "../src/api/sse";
import { document } from "./fixture";

function serve(name: string) {
  return Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/openapi.json") return Response.json(document());
    if (url.pathname === "/health") return new Response(name);
    if (url.pathname === "/fail") return Response.json({ message: "rejected" }, { status: 400 });
    if (url.pathname === "/users/create") return Response.json(await request.json(), { status: 201 });
    return new Response(null, { status: 404 });
  } });
}
const first = serve("first");
const second = serve("second");
afterAll(async () => { await Promise.all([first.stop(true), second.stop(true)]); });

test("plugin clients use the local TCP port and support independent URL overrides", async () => {
  const env = { GROOT_PORT: String(first.port) };
  const firstClient = await ApiClient.connect({ baseUrl: defaultBaseUrl(env) });
  const secondClient = await ApiClient.connect({ baseUrl: second.url.href });
  expect((await firstClient.call("health")).body).toBe("first");
  expect((await secondClient.call("health")).body).toBe("second");
  expect((await firstClient.call("health")).body).toBe("first");
  expect(defaultBaseUrl({})).toBe("http://127.0.0.1:18080");
});

test("HTTP discovery and requests preserve JSON bodies, HTTP errors and cancellation", async () => {
  const client = await ApiClient.connect({ baseUrl: first.url.href });
  expect((await client.call("users.create", { name: "Ada" })).body).toEqual({ name: "Ada" });
  await expect(client.call("users.fail")).rejects.toThrow("HTTP 400");
  const controller = new AbortController();
  controller.abort();
  await expect(client.call("health", {}, controller.signal)).rejects.toThrow();
});

test("base URLs reject unsupported protocols, credentials and URL modifiers", () => {
  for (const value of ["file:///tmp/api", "ftp://localhost", "/relative", "http://user:password@localhost", "http://localhost?x", "http://localhost#x"]) {
    expect(() => transport(value)).toThrow();
  }
});

test("SSE parser handles comments, split CRLF/UTF-8 and multiline data; rejects unbounded frames", async () => {
  async function parse(text: string) {
    const bytes = new TextEncoder().encode(text);
    const body = new ReadableStream<Uint8Array>({ start(controller) { for (const byte of bytes) controller.enqueue(Uint8Array.of(byte)); controller.close(); } });
    const values = [];
    for await (const value of sse(body)) values.push(value);
    return values;
  }
  expect(await parse(": keepalive\r\ndata: 中文\r\ndata: second\r\n\r\ndata: incomplete")).toEqual(["中文\nsecond"]);
  const body = new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new TextEncoder().encode("data: " + "x".repeat(1024 * 1024))); } });
  await expect(sse(body).next()).rejects.toThrow("exceeds");
});
