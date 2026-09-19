#!/usr/bin/env bun
import assert from "node:assert/strict";
import { ApiClient, ApiError, type ApiResult, type Schema } from "../cli/src/api";

const config = await Bun.file("config.env").text();
const major = config.match(/^JDK_MAJOR=(\d+)$/m)?.[1];
if (!major) throw new Error("Missing JDK_MAJOR");
const server = Bun.spawn(["groot", "use-jdk", major, "--", "java", "-XX:ActiveProcessorCount=4", "-jar", "server/dist/sprout-example.jar"], {
  env: { ...process.env, SPROUT_PORT: "0" }, stdout: "pipe", stderr: "pipe", detached: true,
});
let logs = "";
let ready = false;
let resolveUrl: (url: string) => void;
let rejectUrl: (reason: Error) => void;
const urlPromise = new Promise<string>((resolve, reject) => { resolveUrl = resolve; rejectUrl = reject; });
const timer = setTimeout(() => rejectUrl(new Error(`Server readiness timed out\n${logs}`)), 30_000);
async function capture(stream: ReadableStream<Uint8Array>) {
  const decoder = new TextDecoder();
  for await (const chunk of stream) {
    logs = (logs + decoder.decode(chunk, { stream: true })).slice(-30_000);
    const match = logs.match(/Sprout HTTP listening at \/127\.0\.0\.1:(\d+)/);
    if (!ready && match) {
      ready = true;
      clearTimeout(timer);
      resolveUrl(`http://127.0.0.1:${match[1]}`);
    }
  }
}
const streams = Promise.all([capture(server.stdout), capture(server.stderr)]);
void server.exited.then(code => { if (!ready) rejectUrl(new Error(`Server exited ${code}\n${logs}`)); });

try {
  const baseUrl = await urlPromise;
  const client = await ApiClient.connect({ baseUrl });
  assert.deepEqual(client.catalog.operations.map(op => op.id), ["health", "users.create", "users.get", "users.list"]);
  assert.equal((await client.call("health")).body, "ok");
  assert.equal((await client.call("users.list")).body[0].name, "Groot");

  async function cli(...args: string[]) {
    const process = Bun.spawn([Bun.which("bun")!, "cli/dist/sprout.js", "--url", baseUrl, ...args], { stdout: "pipe", stderr: "pipe" });
    const [stdout, stderr, code] = await Promise.all([new Response(process.stdout).text(), new Response(process.stderr).text(), process.exited]);
    return { stdout, stderr, code };
  }
  const help = await cli("users", "--help");
  assert.equal(help.code, 0, help.stderr);
  assert.match(help.stdout, /create/);
  assert.match(help.stdout, /get/);
  assert.match(help.stdout, /list/);
  const create = await cli("users", "create", "--body", '{"name":"Ada"}');
  assert.equal(create.code, 0, create.stderr);
  const user = JSON.parse(create.stdout);
  assert.equal(user.name, "Ada");
  const get = await cli("users", "get", "--id", String(user.id));
  assert.equal(get.code, 0, get.stderr);
  assert.deepEqual(JSON.parse(get.stdout), user);
  const rejected = await cli("users", "create", "--body", '{"name":"  "}');
  assert.equal(rejected.code, 1);
  assert.match(rejected.stderr, /Name must not be blank/);
  const missing = await cli("users", "get", "--id", "999999");
  assert.equal(missing.code, 1);
  assert.match(missing.stderr, /HTTP 404/);

  const { default: plugin } = await import(new URL("../plugin/dist/index.js", import.meta.url).href);
  interface Tool {
    name: string;
    options: { namespace: string; codemode: boolean };
    input: Schema;
    execute(input: unknown, context: unknown): Promise<{ output: ApiResult }>;
  }
  const tools: Tool[] = [];
  let disposed = false;
  const cleanup = await plugin.setup({
    options: { baseUrl },
    tool: { transform: async (callback: (editor: unknown) => void) => {
      callback({ namespace() {}, add(tool: Tool) { tools.push(tool); } });
      return { dispose: async () => { disposed = true; } };
    } },
  });
  const tool = tools.find(tool => tool.options.namespace === "sprout_users" && tool.name === "get")!;
  assert.equal(tool.options.codemode, true);
  assert.deepEqual((await tool.execute({ path: { id: user.id } }, {})).output.body, user);
  assert.equal(tools.length, 4);
  await cleanup();
  assert.equal(disposed, true);
  await assert.rejects(client.call("users.create", { body: { name: " " } }), (error: unknown) => error instanceof ApiError && error.response.status === 400);
  console.log("Integration passed: Scala/JDK 25 → OpenAPI → bundled CLI and V2 plugin; defaults, validation, text, JSON and HTTP errors.");
} finally {
  clearTimeout(timer);
  try { process.kill(-server.pid, "SIGTERM"); } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ESRCH") throw error; }
  const kill = setTimeout(() => { try { process.kill(-server.pid, "SIGKILL"); } catch {} }, 5_000);
  await server.exited;
  clearTimeout(kill);
  await streams;
}
