import { expect, test } from "bun:test";
import type { Context } from "@opencode/plugin/promise/plugin";
import { ApiClient } from "../src/api";
import { sse } from "../src/api/sse";
import { startNotifications } from "../src/notifications";
import { operationTools } from "../src/tools";
import { document } from "./fixture";

function channel<T>() {
  const values: T[] = [], waiters: ((value: T) => void)[] = [];
  return {
    push(value: T) { const waiter = waiters.shift(); if (waiter) waiter(value); else values.push(value); },
    take(): Promise<T> { return values.length ? Promise.resolve(values.shift()!) : new Promise(resolve => waiters.push(resolve)); },
  };
}

function fixture() {
  const doc = document() as any;
  doc.paths["/notify/send"] = { post: { operationId: "notify.send", "x-expose-on": ["graaljs"],
    responses: { "200": { description: "ok" } } } };
  doc.paths["/notify/subscribe"] = { get: { operationId: "notify.subscribe", "x-expose-on": ["cli"],
    responses: { "200": { description: "SSE", content: { "text/event-stream": { schema: { type: "string" } } } } } } };
  const streams = new Map<number, ReadableStreamDefaultController<Uint8Array>>();
  const connections = channel<number>(), disconnections = channel<number>();
  const encoder = new TextEncoder();
  const requests: { query: string; auth: string | null; accept: string | null }[] = [];
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, idleTimeout: 0, fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/api/openapi.json") return Response.json(doc);
    if (url.pathname !== "/api/notify/subscribe") return new Response(null, { status: 404 });
    const id = requests.push({ query: url.search, auth: request.headers.get("Authorization"), accept: request.headers.get("Accept") });
    const remove = () => { if (streams.delete(id)) disconnections.push(id); };
    return new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        streams.set(id, controller);
        controller.enqueue(encoder.encode('data: {"kind":"ready"}\n\n'));
        connections.push(id);
        request.signal.addEventListener("abort", remove, { once: true });
      }, cancel: remove,
    }), { headers: { "Content-Type": "text/event-stream" } });
  } });
  function send(targetSessionId: string, message: string, display: string, id = crypto.randomUUID()) {
    const bytes = encoder.encode(`data: ${JSON.stringify({kind: "message", notification: {id, targetSessionId, message, display, createdAt: "2026-11-09T00:00:00Z"}})}\r\n\r\n`);
    for (const stream of streams.values()) {
      for (let i = 0; i < bytes.length; i += 2) stream.enqueue(bytes.slice(i, i + 2));
    }
    return id;
  }
  function disconnect(id: number) {
    streams.get(id)!.close();
    if (streams.delete(id)) disconnections.push(id);
  }
  return { baseUrl: `${server.url.toString().replace(/\/$/, "")}/api`, server, connections, disconnections, send, requests, disconnect };
}

function context() {
  const delivered = channel<any>();
  // No session hooks, location discovery, event subscription or persistent session registry is needed.
  const ctx = { session: { async synthetic(input: any) { delivered.push(input); return input; } } };
  return { ctx: ctx as unknown as Context, delivered };
}

test("one parameterless authenticated stream delivers all targets without exposing notify tools", async () => {
  const api = fixture(), fake = context();
  const client = await ApiClient.connect({ baseUrl: api.baseUrl, headers: {Authorization: "Bearer test"}, timeoutMs: 100 });
  expect(client.catalog.streams.has("notify.subscribe")).toBe(true);
  expect(operationTools(client).some(tool => tool.options?.namespace === "notify")).toBe(false);
  await expect(client.call("notify.send", {})).rejects.toThrow("Unknown operation");
  const cleanup = await startNotifications(fake.ctx, client);
  try {
    expect(api.requests).toEqual([{query: "", auth: "Bearer test", accept: "text/event-stream"}]);
    const id = api.send("ses_one", "正文 🧪\n下一行", "展示文字");
    api.send("ses_preexisting_idle", "another target", "Done");
    expect(await fake.delivered.take()).toMatchObject({sessionID: "ses_one", text: "正文 🧪\n下一行", description: "展示文字",
      id: `msg_notify_${id.replaceAll("-", "")}`, delivery: "steer", resume: true});
    expect(await fake.delivered.take()).toMatchObject({sessionID: "ses_preexisting_idle", text: "another target", description: "Done"});
    expect(api.requests).toHaveLength(1);
    await cleanup();
    expect(await api.disconnections.take()).toBe(1);
  } finally { await cleanup(); await api.server.stop(true); }
});

test("EOF reconnects and synthetic admission retries with the same notification ID", async () => {
  const api = fixture(), fake = context();
  const cleanup = await startNotifications(fake.ctx, await ApiClient.connect({baseUrl: api.baseUrl}));
  try {
    expect(await api.connections.take()).toBe(1);
    api.disconnect(1);
    expect(await api.connections.take()).toBe(2);
    let calls = 0;
    const original = fake.ctx.session.synthetic;
    fake.ctx.session.synthetic = async (...args) => { if (++calls === 1) throw new Error("temporary admission failure"); return original(...args); };
    const id = api.send("ses_one", "resumed", "Ready");
    expect((await fake.delivered.take()).id).toBe(`msg_notify_${id.replaceAll("-", "")}`);
    expect(calls).toBe(2);
    expect(api.requests.every(request => request.query === "")).toBe(true);
  } finally { await cleanup(); await api.server.stop(true); }
});

test("an unavailable target cannot indefinitely block notifications for other sessions", async () => {
  const api = fixture(), fake = context();
  let failures = 0;
  const original = fake.ctx.session.synthetic;
  fake.ctx.session.synthetic = async (...args) => {
    if (args[0].sessionID === "ses_missing") { failures++; throw new Error("session not found"); }
    return original(...args);
  };
  const cleanup = await startNotifications(fake.ctx, await ApiClient.connect({baseUrl: api.baseUrl}));
  try {
    api.send("ses_missing", "unavailable", "Missing");
    api.send("ses_other", "delivered", "Ready");
    expect((await fake.delivered.take()).sessionID).toBe("ses_other");
    expect(failures).toBe(3);
    expect(api.requests).toHaveLength(1);
  } finally { await cleanup(); await api.server.stop(true); }
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
