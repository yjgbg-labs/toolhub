#!/usr/bin/env bun
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";

const directory = await mkdtemp("/tmp/opencode/scala-sdk-http-");
const socket = `${directory}/http.sock`;
const requests: string[] = [];
const openapi = await Bun.file(new URL("../openapi.json", import.meta.url)).json();
const handler = async (request: Request) => {
  const url = new URL(request.url);
  requests.push(`${request.method} ${url.pathname}`);
  if (url.pathname === "/openapi.json") return Response.json(openapi);
  if (url.pathname === "/health") return new Response("ok", { headers: { "Content-Type": "text/plain" } });
  if (url.pathname === "/schedule/list") return Response.json([]);
  if (url.pathname === "/schedule/new") {
    assert.equal(request.method, "POST");
    assert.equal(url.searchParams.get("name"), "a /空+");
    assert.equal(request.headers.get("content-type"), "application/json");
    return Response.json(await request.json());
  }
  if (url.pathname === "/schedule/log") {
    assert.equal(url.searchParams.get("name"), "a /空+");
    return Response.json({ code: "not_found", message: "missing", errors: [] }, { status: 404 });
  }
  return new Response("unknown", { status: 404 });
};
const unix = Bun.serve({ unix: socket, fetch: handler });
const tcp = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: handler });
try {
  for (const baseUrl of [`unix://${socket}`, `http://127.0.0.1:${tcp.port}`]) {
    async function run(...args: string[]) {
      const child = Bun.spawn(["scala-sdk/dist/sprout-native", ...args], { env: { ...process.env, SPROUT_BASE_URL: baseUrl }, stdout: "pipe", stderr: "pipe" });
      const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
      return { stdout, stderr, code };
    }
    assert.deepEqual(await run("health"), { stdout: "ok\n", stderr: "", code: 0 });
    assert.deepEqual(JSON.parse((await run("schedule", "list")).stdout), []);
    const job = { command: ["echo", "hi"], every: "1h", cwd: null };
    const created = await run("schedule", "new", "--name", "a /空+", "--body", JSON.stringify(job));
    assert.equal(created.code, 0, created.stderr);
    assert.deepEqual(JSON.parse(created.stdout), job);
    const error = await run("schedule", "log", "--name", "a /空+");
    assert.equal(error.code, 1);
    assert.match(error.stderr, /HTTP 404/);
  }
  for (const command of [
    ["groot", "use-jdk", "25", "--", "java", "-jar", "scala-sdk/dist/http-check.jar"],
    ["bun", "scala-sdk/dist/http-check.cjs"],
  ]) {
    const child = Bun.spawn([...command, `http://127.0.0.1:${tcp.port}`], { stdout: "pipe", stderr: "pipe" });
    const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
    assert.equal(code, 0, `${command.join(" ")}\n${stdout}\n${stderr}`);
    assert.match(stdout, /HTTP integration passed/);
  }
  assert.equal(requests.filter(request => request === "GET /openapi.json").length, 10);
  assert.equal(requests.length, 30);
  console.log("SDK HTTP integration passed: JVM HttpClient, JS Fetch, Native Curl + Unix Socket; JSON POST, UTF-8 and HTTP errors");
} finally {
  await unix.stop(true);
  await tcp.stop(true);
  await rm(directory, { recursive: true, force: true });
}
