import { expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { join, resolve } from "node:path";

const binary = resolve("cli/dist/groot-cli");
const headers = { "Content-Type": "Application/X-Sh; charset=utf-8" };
const scriptResponse = (script: string) => new Response(script, { headers });

async function fixture(respond: () => Response, discovery = false) {
  const directory = await mkdtemp("/tmp/opencode/shell-cli-");
  const marker = join(directory, "executed");
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, idleTimeout: 0, fetch(request) {
    if (!discovery && new URL(request.url).pathname === "/openapi.json") return Response.json({
      openapi: "3.1.0", info: { title: "Shell fixture", version: "1" },
      paths: { "/run": { get: { operationId: "run", responses: {
        // Execution is selected by the actual response, not the advertised schema.
        "200": { description: "Result", content: { "application/json": { schema: {} } } },
      } } } },
    });
    return respond();
  } });
  const child = Bun.spawn([binary, "run"], {
    cwd: directory,
    env: { ...process.env, SPROUT_BASE_URL: server.url.href.replace(/\/$/, ""),
      SHELL_FIXTURE_VALUE: '空 "quoted"', SHELL_FIXTURE_MARKER: marker },
    stdin: "pipe", stdout: "pipe", stderr: "pipe",
  });
  return {
    directory, marker, server, child,
    async close() {
      child.stdin.end();
      if (child.exitCode === null && child.signalCode === null) child.kill();
      await child.exited;
      await server.stop(true);
      await rm(directory, { recursive: true, force: true });
    },
  };
}

async function output(child: Awaited<ReturnType<typeof fixture>>["child"]) {
  const [stdout, stderr, code] = await Promise.all([
    new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited,
  ]);
  return { stdout, stderr, code };
}

test("Shell runs locally with inherited cwd, environment, stdin/stdout/stderr and exact exit status", async () => {
  const script = `printf '%s\\n' "$SHELL_FIXTURE_VALUE" "$PWD"
IFS= read -r line
printf 'input:%s\\n' "$line"
printf 'diagnostic\\n' >&2
printf executed > "$SHELL_FIXTURE_MARKER"
exit 23
`;
  const api = await fixture(() => scriptResponse(script));
  try {
    api.child.stdin.write("from stdin\n");
    api.child.stdin.end();
    expect(await output(api.child)).toEqual({
      stdout: `空 "quoted"\n${api.directory}\ninput:from stdin\n`, stderr: "diagnostic\n", code: 23,
    });
    expect(await Bun.file(api.marker).text()).toBe("executed");
  } finally { await api.close(); }
});

test("Shell output is visible while the child is still running, without echoing the script", async () => {
  const api = await fixture(() => scriptResponse("printf 'ready\\n'\nIFS= read -r line\nprintf '%s\\n' \"$line\""));
  try {
    const reader = api.child.stdout.getReader();
    const first = await reader.read();
    expect(new TextDecoder().decode(first.value)).toBe("ready\n");
    expect(api.child.exitCode).toBeNull();
    api.child.stdin.write("continued\n");
    api.child.stdin.end();
    let remaining = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      remaining += new TextDecoder().decode(value);
    }
    expect(remaining).toBe("continued\n");
    expect(await api.child.exited).toBe(0);
    expect(await new Response(api.child.stderr).text()).toBe("");
  } finally { await api.close(); }
});

test("only successful Shell command responses execute; text, JSON, SSE, HTTP errors and discovery do not", async () => {
  const script = 'printf executed > "$SHELL_FIXTURE_MARKER"';
  const cases = [
    { response: () => new Response(script, { status: 403, headers }), code: 1 },
    { response: () => new Response(script, { headers: { "Content-Type": "text/plain" } }), code: 0 },
    { response: () => Response.json(script), code: 0 },
    { response: () => new Response(`data: ${script}\n\n`, { headers: { "Content-Type": "text/event-stream" } }), code: 0 },
    { response: () => scriptResponse(script), code: 1, discovery: true },
  ];
  for (const entry of cases) {
    const api = await fixture(entry.response, entry.discovery);
    try {
      api.child.stdin.end();
      const result = await output(api.child);
      expect(result.code).toBe(entry.code);
      expect(result.stdout).toBe(entry.code === 0 ? `${script}\n` : "");
      expect(await Bun.file(api.marker).exists()).toBe(false);
    } finally { await api.close(); }
  }
});

test("Shell waits for a complete response and never executes a truncated transfer", async () => {
  for (const complete of [true, false]) {
    let controller: ReadableStreamDefaultController<Uint8Array>;
    const sent = Promise.withResolvers<void>();
    const api = await fixture(() => new Response(new ReadableStream<Uint8Array>({ start(value) {
      controller = value;
      value.enqueue(new TextEncoder().encode('printf executed > "$SHELL_FIXTURE_MARKER"\n'));
      sent.resolve();
    } }), { headers }));
    try {
      api.child.stdin.end();
      await sent.promise;
      await Bun.sleep(100);
      expect(await Bun.file(api.marker).exists()).toBe(false);
      if (complete) controller!.close();
      else await api.server.stop(true);
      const result = await output(api.child);
      expect(result.code).toBe(complete ? 0 : 1);
      expect(result.stdout).toBe("");
      expect(await Bun.file(api.marker).exists()).toBe(complete);
    } finally { await api.close(); }
  }
});
