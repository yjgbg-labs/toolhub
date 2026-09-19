#!/usr/bin/env bun
import assert from "node:assert/strict";
import { cp, mkdtemp, rm } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { ApiClient, ApiError, type ApiResult, type Schema } from "../cli/src/api";

const config = await Bun.file("config.env").text();
const major = config.match(/^JDK_MAJOR=(\d+)$/m)?.[1];
if (!major) throw new Error("Missing JDK_MAJOR");
const home = await mkdtemp("/tmp/opencode/sprout-integration-");
const server = Bun.spawn(["groot", "use-jdk", major, "--", "java", "-XX:ActiveProcessorCount=4", "-jar", "server/dist/sprout-example.jar"], {
  env: { ...process.env, SPROUT_PORT: "0", SPROUT_HOME: home }, stdout: "pipe", stderr: "pipe", detached: true,
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
  const scheduleOperations = ["schedule.config.read", "schedule.config.write", "schedule.delete", "schedule.list", "schedule.log", "schedule.new", "schedule.reset"];
  assert.deepEqual(client.catalog.operations.map(op => op.id), ["health", ...scheduleOperations, "users.create", "users.get", "users.list"]);
  assert.equal((await client.call("health")).body, "ok");
  assert.equal((await client.call("users.list")).body[0].name, "Groot");

  async function cliWithInput(args: string[], stdin?: string) {
    const child = Bun.spawn([Bun.which("bun")!, "cli/dist/sprout.js", "--url", baseUrl, ...args], {
      stdout: "pipe", stderr: "pipe", stdin: stdin === undefined ? "ignore" : new Blob([stdin]),
    });
    const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
    return { stdout, stderr, code };
  }
  const cli = (...args: string[]) => cliWithInput(args);
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

  // Load outside the workspace so missing runtime dependencies cannot be hidden by node_modules.
  await cp("plugin/dist", `${home}/plugin`, { recursive: true });
  const { default: plugin } = await import(pathToFileURL(`${home}/plugin/index.js`).href);
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
  assert.equal(tools.length, 11);
  assert.equal(tools.filter(tool => tool.options.namespace.startsWith("sprout_schedule")).length, 7);
  const configuration = { resources: { cpu: 1 }, tickMs: 20, jobs: {} };
  const write = await cliWithInput(["schedule", "config", "write", "--body", "-"], JSON.stringify(configuration));
  assert.equal(write.code, 0, write.stderr);
  assert.deepEqual(JSON.parse(write.stdout), configuration);
  const configHelp = await cli("schedule", "config", "--help");
  assert.equal(configHelp.code, 0, configHelp.stderr);
  assert.match(configHelp.stdout, /read/);
  assert.match(configHelp.stdout, /write/);
  assert.doesNotMatch(configHelp.stdout, /edit/);
  const schedule = await cli("schedule", "new", "integration/a +空", "--body", JSON.stringify({
    every: "1h", resource: { cpu: 1 }, command: ["/bin/echo", "literal --help --url value $HOME && *"],
  }));
  assert.equal(schedule.code, 0, schedule.stderr);
  const listTool = tools.find(tool => tool.options.namespace === "sprout_schedule" && tool.name === "list")!;
  const deadline = Date.now() + 5000;
  let jobs: any[] = [];
  while (Date.now() < deadline) {
    jobs = (await listTool.execute({}, {})).output.body;
    if (jobs[0]?.lastExitCode === 0) break;
    await Bun.sleep(20);
  }
  assert.equal(jobs[0]?.lastExitCode, 0, JSON.stringify(jobs));
  assert.equal(jobs[0].status, "waiting");
  const tail = await cli("schedule", "log", "integration/a +空", "--lines", "2");
  assert.equal(tail.code, 0, tail.stderr);
  assert.match(tail.stdout, /literal --help --url value \$HOME && \*/);
  const staticConfig = (await client.call("schedule.config.read")).body;
  staticConfig.jobs["integration/a +空"].enabled = false;
  await client.call("schedule.config.write", { body: staticConfig });
  const resetTool = tools.find(tool => tool.options.namespace === "sprout_schedule" && tool.name === "reset")!;
  assert.deepEqual((await resetTool.execute({ path: { name: "integration/a +空" } }, {})).output.body, { lastRunAt: null, lastExitCode: null });
  const invalid = await cli("schedule", "new", "integration/a +空", "--body", JSON.stringify({ every: "1.5h", command: ["/bin/true"] }));
  assert.equal(invalid.code, 1);
  assert.match(invalid.stderr, /Invalid duration/);
  const configFile = JSON.parse(await Bun.file(`${home}/schedule.json`).text());
  assert.equal(configFile.jobs["integration/a +空"].enabled, false);
  assert.equal(Object.hasOwn(configFile.jobs["integration/a +空"], "lastRunAt"), false);
  assert.equal((await cli("schedule", "delete", "integration/a +空")).code, 0);
  assert.deepEqual((await listTool.execute({}, {})).output.body, []);
  const edit = await cli("schedule", "config", "edit");
  assert.equal(edit.code, 1);
  assert.match(edit.stderr, /Unknown command/);
  const missingBody = await cliWithInput(["schedule", "config", "write"], JSON.stringify(configuration));
  assert.equal(missingBody.code, 1);
  assert.match(missingBody.stderr, /Invalid input/);
  const updated = { ...configuration, tickMs: 50 };
  const writeInput = await cliWithInput(["schedule", "config", "write", "--input", "-"], JSON.stringify({ body: updated }));
  assert.equal(writeInput.code, 0, writeInput.stderr);
  assert.deepEqual((await client.call("schedule.config.read")).body, updated);
  await cleanup();
  assert.equal(disposed, true);
  await assert.rejects(client.call("users.create", { body: { name: " " } }), (error: unknown) => error instanceof ApiError && error.response.status === 400);
  console.log("Integration passed: Scala/JDK 25 → OpenAPI → bundled CLI and V2 plugin; real scheduled commands, state, logs, configuration, validation and HTTP errors.");
} finally {
  clearTimeout(timer);
  try { process.kill(-server.pid, "SIGTERM"); } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ESRCH") throw error; }
  const kill = setTimeout(() => { try { process.kill(-server.pid, "SIGKILL"); } catch {} }, 5_000);
  await server.exited;
  clearTimeout(kill);
  await streams;
  await rm(home, { recursive: true, force: true });
}
