import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { resolve } from "node:path";

const binary = resolve(import.meta.dir, "../dist/groot-agy");

const mockOpenApi = {
  openapi: "3.1.0",
  info: { title: "Groot", version: "1.0.0" },
  "x-rpc": {
    commands: [
      { name: "shell", description: "Run shell commands" },
      { name: "fs", description: "Server filesystem" },
    ],
  },
  paths: {
    "/health": {
      get: {
        operationId: "health",
        summary: "Health check",
        "x-expose-on": ["cli", "codemode", "graaljs"],
        responses: { "200": { content: { "text/plain": { schema: { type: "string" } } } } },
      },
    },
    "/fs/read": {
      get: {
        operationId: "fs.read",
        summary: "Read server file",
        "x-expose-on": ["graaljs"],
        parameters: [{ name: "path", in: "query", required: true, schema: { type: "string" } }],
        responses: { "200": { content: { "application/json": { schema: { type: "object", properties: { content: { type: "string" } } } } } } },
      },
    },
  },
};

let server: ReturnType<typeof Bun.serve>;
let baseUrl = "";
let lastSubmittedScript = "";

beforeAll(() => {
  server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    async fetch(req) {
      const url = new URL(req.url);
      if (url.pathname === "/openapi.json") {
        return Response.json(mockOpenApi);
      }
      if (url.pathname === "/graaljs/start" && req.method === "POST") {
        lastSubmittedScript = await req.text();
        return Response.json({ id: "gjs_e2e_1", state: "running" });
      }
      if (url.pathname === "/graaljs/logsFollow") {
        const body = "data: ok-from-e2e\n\ndata: [graaljs] state=succeeded cpu=1.0ms elapsed=2.0ms\n\n";
        return new Response(body, { headers: { "Content-Type": "text/event-stream" } });
      }
      if (url.pathname === "/graaljs/status") {
        return Response.json({ id: "gjs_e2e_1", state: "succeeded", logPath: "/tmp/gjs_e2e_1.log" });
      }
      if (url.pathname === "/graaljs/cancel" && req.method === "POST") {
        return Response.json({ id: "gjs_e2e_1", state: "cancelled" });
      }
      return new Response("not found", { status: 404 });
    },
  });
  baseUrl = `http://127.0.0.1:${server.port}`;
});

afterAll(() => {
  server.stop(true);
});

describe("groot-agy native binary", () => {
  test("hook session-start injects namespaces", async () => {
    const proc = Bun.spawn([binary, "hook", "session-start"], {
      env: { ...process.env, GROOT_BASE_URL: baseUrl },
      stdin: new Response("{}"),
      stdout: "pipe",
      stderr: "pipe",
    });
    const out = await new Response(proc.stdout).text();
    expect(await proc.exited).toBe(0);
    const json = JSON.parse(out);
    const msg: string = json.injectSteps[0].systemMessage.systemMessage;
    expect(msg).toContain("<groot_codemode>");
    expect(msg).toContain("- fs (1 ops)");
    expect(msg).toContain("tools.health");
  });

  test("hook pre-tool-use records sideband context without overwriting args and mcp execute consumes it", async () => {
    const ctxDir = `/tmp/groot-agy-e2e-ctx-${Date.now()}`;
    const script = "return ctx.conversationId;";
    const payload = JSON.stringify({
      conversationId: "ses_sideband_99",
      cwd: "/tmp/work",
      workspacePaths: ["/tmp/work"],
      toolCall: {
        id: "t1",
        name: "call_mcp_tool",
        args: {
          ServerName: "groot_codemode",
          ToolName: "execute",
          Arguments: { code: script },
        },
      },
    });
    const hookProc = Bun.spawn([binary, "hook", "pre-tool-use"], {
      env: { ...process.env, GROOT_AGY_CTX_DIR: ctxDir },
      stdin: new Response(payload),
      stdout: "pipe",
      stderr: "pipe",
    });
    const hookOut = await new Response(hookProc.stdout).text();
    expect(await hookProc.exited).toBe(0);
    const hookJson = JSON.parse(hookOut);
    expect(hookJson).toEqual({ decision: "allow" });

    const proc = Bun.spawn([binary, "mcp"], {
      env: { ...process.env, GROOT_BASE_URL: baseUrl, GROOT_AGY_CTX_DIR: ctxDir },
      stdin: "pipe",
      stdout: "pipe",
      stderr: "pipe",
    });
    const reader = proc.stdout.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    async function nextMessage(): Promise<any> {
      while (true) {
        const idx = buffer.indexOf("\n");
        if (idx >= 0) {
          const line = buffer.slice(0, idx).trim();
          buffer = buffer.slice(idx + 1);
          if (line) return JSON.parse(line);
        }
        const chunk = await reader.read();
        if (chunk.done) throw new Error("EOF");
        buffer += decoder.decode(chunk.value, { stream: true });
      }
    }
    function send(msg: unknown) {
      proc.stdin.write(JSON.stringify(msg) + "\n");
    }

    send({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-03-26" } });
    const init = await nextMessage();
    expect(init.result.serverInfo.name).toBe("groot");

    send({ jsonrpc: "2.0", id: 2, method: "tools/list" });
    const list = await nextMessage();
    expect(list.result.tools.map((t: any) => t.name)).toEqual(["search", "execute"]);
    expect(Object.keys(list.result.tools[1].inputSchema.properties)).toEqual(["code", "timeout"]);

    send({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "search", arguments: { namespace: "fs" } } });
    const search = await nextMessage();
    expect(search.result.content[0].text).toContain("tools.fs.read");

    send({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: {
        name: "execute",
        arguments: { code: script, timeout: "10s" },
      },
    });
    const exec = await nextMessage();
    expect(exec.result.isError).toBeUndefined();
    expect(exec.result.content[0].text).toBe("ok-from-e2e");
    expect(lastSubmittedScript).toContain('"conversationId":"ses_sideband_99"');

    proc.stdin.end();
    expect(await proc.exited).toBe(0);
  });
});
