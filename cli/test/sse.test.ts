import { expect, test } from "bun:test";
import { resolve } from "node:path";

const binary = resolve("cli/dist/sprout-native");
const encode = (value: string) => new TextEncoder().encode(value);
const headers = { "Content-Type": "Text/Event-Stream; charset=utf-8" };

function fixture(respond: (request: Request) => Response) {
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, idleTimeout: 0, fetch(request) {
    if (new URL(request.url).pathname === "/openapi.json") return Response.json({
      openapi: "3.1.0", info: { title: "Streaming fixture", version: "1" },
      paths: { "/events": { get: { operationId: "watch", responses: {
        // Actual HTTP headers, rather than the advertised schema, select SSE handling.
        "200": { description: "Result", content: { "application/json": { schema: {} } } },
      } } } },
    });
    return respond(request);
  } });
  const child = Bun.spawn([binary, "watch"], {
    env: { ...process.env, SPROUT_BASE_URL: server.url.href.replace(/\/$/, "") },
    stdin: "ignore", stdout: "pipe", stderr: "pipe",
  });
  return {
    server, child,
    async close() {
      if (child.exitCode === null && child.signalCode === null) child.kill();
      await child.exited;
      await server.stop(true);
    },
  };
}

async function within<T>(promise: Promise<T>, ms = 3000): Promise<T> {
  let timer: ReturnType<typeof setTimeout>;
  try {
    return await Promise.race([promise, new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error(`Timed out after ${ms}ms`)), ms);
    })]);
  } finally { clearTimeout(timer!); }
}

async function readOutput(reader: ReadableStreamDefaultReader<Uint8Array>, expected: string) {
  const decoder = new TextDecoder();
  let output = "";
  await within((async () => {
    while (output.length < expected.length) {
      const { done, value } = await reader.read();
      if (done) break;
      output += decoder.decode(value, { stream: true });
    }
  })());
  expect(output).toBe(expected);
}

test("SSE flushes to a pipe before EOF and stays connected beyond the old 30-second timeout", async () => {
  let controller: ReadableStreamDefaultController<Uint8Array>;
  const api = fixture(() => new Response(new ReadableStream<Uint8Array>({ start(value) {
    controller = value;
    value.enqueue(encode("data: first\n\n"));
  } }), { headers }));
  try {
    const reader = api.child.stdout.getReader();
    await readOutput(reader, "first\n");
    await Promise.race([
      Bun.sleep(31_000),
      api.child.exited.then(code => { throw new Error(`Stream exited while idle: ${code}`); }),
    ]);
    controller!.enqueue(encode('data: {"next":2}\n\n'));
    await readOutput(reader, '{"next":2}\n');
    controller!.close();
    expect(await within(api.child.exited)).toBe(0);
    expect((await reader.read()).done).toBe(true);
    expect(await new Response(api.child.stderr).text()).toBe("");
  } finally { await api.close(); }
}, 40_000);

test("SSE decodes fragmented UTF-8, line endings, multiline/empty data and ignores metadata", async () => {
  const bytes = encode('\uFEFF: heartbeat\r\nid: 7\r\nevent: change\r\nretry: 10\r\n\r\n' +
    'data: {"serial":9223372036854775807,"name":"空"}\r\n\r\n' +
    'data:first\rdata: second\rdata:  spaced\r\rdata\n\n' +
    'data: last\n\ndata: incomplete');
  let offset = 0;
  const api = fixture(() => new Response(new ReadableStream<Uint8Array>({ async pull(controller) {
    if (offset === bytes.length) controller.close();
    else {
      controller.enqueue(bytes.slice(offset, ++offset));
      await Bun.sleep(1);
    }
  } }), { headers }));
  try {
    const output = await within(new Response(api.child.stdout).text());
    expect(output).toBe('{"serial":9223372036854775807,"name":"空"}\nfirst\nsecond\n spaced\n\nlast\n');
    expect(await api.child.exited).toBe(0);
    expect(await new Response(api.child.stderr).text()).toBe("");
  } finally { await api.close(); }
});

test("non-success event streams stay on stderr; ordinary text is not interpreted as SSE", async () => {
  for (const [status, contentType] of [[503, "text/event-stream"], [200, "text/plain"]] as const) {
    const api = fixture(() => new Response("data: literal\n\n", { status, headers: { "Content-Type": contentType } }));
    try {
      const [stdout, stderr, code] = await within(Promise.all([
        new Response(api.child.stdout).text(), new Response(api.child.stderr).text(), api.child.exited,
      ]));
      expect(code).toBe(status === 200 ? 0 : 1);
      expect(stdout).toBe(status === 200 ? "data: literal\n\n\n" : "");
      if (status === 200) expect(stderr).toBe("");
      else expect(stderr).toContain("HTTP 503: data: literal");
    } finally { await api.close(); }
  }
});

test("Ctrl+C terminates a live SSE connection promptly", async () => {
  const disconnected = Promise.withResolvers<void>();
  const api = fixture(request => {
    request.signal.addEventListener("abort", () => disconnected.resolve(), { once: true });
    return new Response(new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(encode("data: connected\n\n")); },
      cancel() { disconnected.resolve(); },
    }), { headers });
  });
  try {
    await readOutput(api.child.stdout.getReader(), "connected\n");
    api.child.kill("SIGINT");
    const code = await within(api.child.exited);
    expect(api.child.signalCode === "SIGINT" || code === 130).toBe(true);
    await within(disconnected.promise);
  } finally { await api.close(); }
});

test("a broken HTTP stream reports an error after already printed events", async () => {
  const api = fixture(() => new Response(new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(encode("data: delivered\n\n")); },
  }), { headers }));
  try {
    await readOutput(api.child.stdout.getReader(), "delivered\n");
    await api.server.stop(true);
    expect(await within(api.child.exited)).toBe(1);
    expect(await new Response(api.child.stderr).text()).toMatch(/curl|transfer|response/i);
  } finally { await api.close(); }
});
