import { expect, test } from "bun:test";
import type { Context } from "@opencode/plugin/promise/plugin";
import { ApiClient } from "../src/api";
import { sse } from "../src/api/sse";
import { startNotifications } from "../src/notifications";
import { operationTools } from "../src/tools";
import { document } from "./fixture";

const prefix = "notify/session/%2Ftest%2Fone//";

function channel<T>() {
  const values: T[] = [], waiters: ((value: T) => void)[] = [];
  return {
    push(value: T) { const waiter = waiters.shift(); if (waiter) waiter(value); else values.push(value); },
    take(): Promise<T> { return values.length ? Promise.resolve(values.shift()!) : new Promise(resolve => waiters.push(resolve)); },
    get size() { return values.length; },
  };
}

function fixture() {
  const doc = document() as any;
  doc.paths["/notify/send"] = { post: { operationId: "notify.send", "x-expose-on": ["graaljs"],
    responses: { "200": { description: "ok" } } } };
  doc.paths["/notify/subscribe"] = { get: { operationId: "notify.subscribe", "x-expose-on": ["cli"],
    parameters: [{ name: "sessionId", in: "query", required: true, schema: { type: "string" } }],
    responses: { "200": { description: "SSE", content: { "text/event-stream": { schema: { type: "string" } } } } } } };
  const streams = new Map<string, Set<ReadableStreamDefaultController<Uint8Array>>>();
  const connections = channel<string>(), disconnections = channel<string>();
  const encoder = new TextEncoder();
  const requests: { sessionId: string; auth: string | null; accept: string | null }[] = [];
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, idleTimeout: 0, fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/api/openapi.json") return Response.json(doc);
    if (url.pathname !== "/api/notify/subscribe") return new Response(null, { status: 404 });
    const sessionId = url.searchParams.get("sessionId")!;
    requests.push({ sessionId, auth: request.headers.get("Authorization"), accept: request.headers.get("Accept") });
    let streamController: ReadableStreamDefaultController<Uint8Array>;
    let removed = false;
    const remove = () => {
      if (removed) return;
      removed = true;
      streams.get(sessionId)?.delete(streamController);
      disconnections.push(sessionId);
    };
    return new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        streamController = controller;
        if (!streams.has(sessionId)) streams.set(sessionId, new Set());
        streams.get(sessionId)!.add(controller);
        controller.enqueue(encoder.encode('data: {"kind":"ready"}\n\n'));
        connections.push(sessionId);
        request.signal.addEventListener("abort", remove, { once: true });
      }, cancel: remove,
    }), { headers: { "Content-Type": "text/event-stream" } });
  } });
  function send(sessionId: string, message: string, display: string, id = crypto.randomUUID()) {
    const bytes = encoder.encode(`data: ${JSON.stringify({kind: "message", notification: {id, targetSessionId: sessionId, message, display, createdAt: "2026-11-09T00:00:00Z"}})}\r\n\r\n`);
    for (const stream of streams.get(sessionId) ?? []) {
      for (let i = 0; i < bytes.length; i += 2) stream.enqueue(bytes.slice(i, i + 2));
    }
    return id;
  }
  return { baseUrl: `${server.url.toString().replace(/\/$/, "")}/api`, server, streams, connections, disconnections, send, requests };
}

function context(saved = new Map<string, any>()) {
  const hooks = new Map<string, (event: any) => Promise<void>>();
  const delivered = channel<any>();
  const sessions = new Map<string, any>();
  const location = { directory: "/test/one" };
  let events!: ReadableStreamDefaultController<any>;
  let disposed = 0, eventAborted = false;
  const source = new ReadableStream({ start(controller) { events = controller; } });
  const ctx = {
    location,
    session: {
      async get({ sessionID }: any) { return sessions.get(sessionID) ?? { id: sessionID, location, time: {} }; },
      async synthetic(input: any) { delivered.push(input); return input; },
      async hook(name: string, callback: (event: any) => Promise<void>) {
        hooks.set(name, callback);
        return { async dispose() { disposed++; hooks.delete(name); } };
      },
    },
    storage: {
      async set(key: string, value: any) { saved.set(key, value); },
      async remove(key: string) { saved.delete(key); },
      async scan({ prefix, after, limit }: any) {
        const keys = [...saved.keys()].filter(key => key.startsWith(prefix) && (!after || key > after)).sort();
        return { entries: keys.slice(0, limit).map(key => ({key, value: saved.get(key)})), next: keys.length > limit ? keys[limit - 1] : undefined };
      },
    },
    event: { async *subscribe({ signal }: { signal: AbortSignal }) {
      const reader = source.getReader();
      const abort = () => { eventAborted = true; void reader.cancel(); };
      signal.addEventListener("abort", abort, { once: true });
      try { while (!signal.aborted) { const value = await reader.read(); if (value.done) return; yield value.value; } }
      finally { signal.removeEventListener("abort", abort); reader.releaseLock(); }
    } },
  };
  return { ctx: ctx as unknown as Context, hooks, delivered, sessions, saved, emit: (event: any) => events.enqueue(event),
    get disposed() { return disposed; }, get eventAborted() { return eventAborted; } };
}

test("live SSE is discovered without exposing notify tools; hooks subscribe once and deliver message/display", async () => {
  const api = fixture(), fake = context();
  const client = await ApiClient.connect({ baseUrl: api.baseUrl, headers: {Authorization: "Bearer test"}, timeoutMs: 100 });
  expect(client.catalog.streams.has("notify.subscribe")).toBe(true);
  expect(operationTools(client).some(tool => tool.options?.namespace === "notify")).toBe(false);
  await expect(client.call("notify.send", {})).rejects.toThrow("Unknown operation");
  const cleanup = await startNotifications(fake.ctx, client);
  try {
    await Promise.all([fake.hooks.get("prompt")!({sessionID: "ses_one"}), fake.hooks.get("context")!({sessionID: "ses_one"})]);
    expect(api.requests).toEqual([{sessionId: "ses_one", auth: "Bearer test", accept: "text/event-stream"}]);
    const id = api.send("ses_one", "正文 🧪\n下一行", "展示文字");
    expect(await fake.delivered.take()).toMatchObject({sessionID: "ses_one", text: "正文 🧪\n下一行", description: "展示文字",
      id: `msg_notify_${id.replaceAll("-", "")}`, delivery: "steer", resume: true});
    expect(fake.saved.has(prefix + "ses_one")).toBe(true);
  } finally { await cleanup(); await api.server.stop(true); }
  expect(fake.disposed).toBe(2);
  expect(fake.eventAborted).toBe(true);
});

test("EOF reconnects, synthetic admission retries with the same ID, and deleting a session releases its stream", async () => {
  const api = fixture(), fake = context();
  const client = await ApiClient.connect({baseUrl: api.baseUrl});
  const cleanup = await startNotifications(fake.ctx, client);
  try {
    await fake.hooks.get("context")!({sessionID: "ses_one"});
    expect(await api.connections.take()).toBe("ses_one");
    for (const stream of api.streams.get("ses_one")!) stream.close();
    expect(await api.connections.take()).toBe("ses_one");
    let calls = 0;
    const original = fake.ctx.session.synthetic;
    fake.ctx.session.synthetic = async (...args) => { if (++calls === 1) throw new Error("temporary admission failure"); return original(...args); };
    // Closed controllers are removed here because this fixture forces EOF rather than a client disconnect.
    const live = [...api.streams.get("ses_one")!].at(-1)!;
    api.streams.set("ses_one", new Set([live]));
    const id = api.send("ses_one", "resumed", "Ready");
    expect((await fake.delivered.take()).id).toBe(`msg_notify_${id.replaceAll("-", "")}`);
    expect(calls).toBe(2);
    fake.emit({type: "session.deleted", data: {sessionID: "ses_one"}});
    expect(await api.disconnections.take()).toBe("ses_one");
  } finally { await cleanup(); await api.server.stop(true); }
});

test("restore and session lifecycle events respect locations; foreign sessions are never subscribed", async () => {
  const foreignKey = "notify/session/%2Felsewhere//ses_foreign";
  const api = fixture(), fake = context(new Map([[prefix + "ses_saved", true], [foreignKey, true]]));
  fake.sessions.set("ses_foreign", {location: {directory: "/elsewhere"}, time: {}});
  const cleanup = await startNotifications(fake.ctx, await ApiClient.connect({baseUrl: api.baseUrl}));
  try {
    expect(await api.connections.take()).toBe("ses_saved");
    await fake.hooks.get("context")!({sessionID: "ses_foreign"});
    fake.emit({type: "session.created", data: {sessionID: "ses_new", location: {directory: "/test/one"}}});
    expect(await api.connections.take()).toBe("ses_new");
    fake.sessions.set("ses_new", {location: {directory: "/elsewhere"}, time: {}});
    fake.emit({type: "session.moved", data: {sessionID: "ses_new", location: {directory: "/elsewhere"}}});
    expect(await api.disconnections.take()).toBe("ses_new");
    expect(api.requests.map(value => value.sessionId).sort()).toEqual(["ses_new", "ses_saved"]);
    expect(fake.saved.has(foreignKey)).toBe(true);
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
