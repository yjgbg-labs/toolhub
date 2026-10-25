#!/usr/bin/env bun
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { resolve } from "node:path";

// Deliberately unrelated to Sprout's business API. The very same binary is also
// tested after changing the live contract, and when linked from Maven artifacts.
const binary = resolve(process.argv[2] ?? "scala-sdk/dist/sprout-native");
const directory = await mkdtemp("/tmp/opencode/native-cli-");
const string = { type: "string" };
const responses = { "200": { description: "Result", content: { "application/json": { schema: {} } } } };
const parameter = (name: string, location: string, schema: object = string, required = false) => ({ name, in: location, schema, required });
const document = {
  openapi: "3.1.0", info: { title: "Inventory fixture", version: "1" },
  "x-rpc": { commands: [{ name: "inventory", description: "Inventory commands" }, { name: "inventory.parts", description: "Manage parts" }] },
  components: {
    parameters: { Part: parameter("part", "path", string, true) },
    schemas: {
      Tag: string,
      Update: {
        type: "object", required: ["serial", "note"], additionalProperties: false,
        properties: { serial: { type: "integer", format: "int64" }, note: { type: ["string", "null"] } },
      },
    },
    requestBodies: { Update: { required: true, content: { "application/json": { schema: { $ref: "#/components/schemas/Update" } } } } },
  },
  paths: {
    "/v2/parts/{part}": {
      parameters: [{ $ref: "#/components/parameters/Part" }, parameter("limit", "query", { type: "string" })],
      patch: {
        operationId: "inventory.parts.revise", summary: "Revise a part", responses,
        parameters: [
          parameter("tag", "query", { type: "array", items: { $ref: "#/components/schemas/Tag" } }),
          { ...parameter("code", "query", { type: "array", items: { type: "integer" } }), explode: false },
          parameter("limit", "query", { type: "integer", minimum: 1, default: 5 }),
          parameter("enabled", "query", { type: "boolean" }),
          parameter("token", "query"), parameter("token", "header", string, true),
          parameter("session", "cookie"), parameter("help", "query"), parameter("name", "query"),
        ],
        requestBody: { $ref: "#/components/requestBodies/Update" },
      },
      delete: { operationId: "inventory.parts.remove", responses: { "204": { description: "Removed" } } },
    },
    "/message": { get: { operationId: "greeting", responses } },
    "/missing": { get: { operationId: "inventory.missing", responses } },
    "/exact": { get: { operationId: "numbers", responses } },
  },
};
let served: unknown = document;
let discoveryStatus = 200;
let discoveryCount = 0;
const requests: Array<{ method: string; path: string; query: string[][]; token: string | null; cookie: string | null; contentType: string | null; body: string }> = [];
const handler = async (request: Request) => {
  const url = new URL(request.url);
  // Exercise base URLs with a path prefix as well as Unix sockets.
  const path = url.pathname.replace(/^\/gateway/, "");
  if (path === "/openapi.json") {
    discoveryCount++;
    return Response.json(served, { status: discoveryStatus });
  }
  const received = {
    method: request.method, path, query: [...url.searchParams], token: request.headers.get("token"),
    cookie: request.headers.get("cookie"), contentType: request.headers.get("content-type"), body: await request.text(),
  };
  requests.push(received);
  if (path.startsWith("/v2/parts/")) return request.method === "DELETE" ? new Response(null, { status: 204 }) : Response.json(received);
  if (path === "/message") return Response.json("hello from an unrelated API");
  if (path === "/missing") return Response.json({ error: "fixture missing" }, { status: 404 });
  if (path === "/exact") return new Response('{"serial":9223372036854775807}', { headers: { "Content-Type": "application/problem+json" } });
  if (path === "/new-endpoint") return new Response("discovered after compilation", { headers: { "Content-Type": "text/plain" } });
  return new Response("unknown", { status: 404 });
};
const socket = `${directory}/http.sock`;
const unix = Bun.serve({ unix: socket, fetch: handler });
const tcp = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: handler });
try {
  for (const baseUrl of [`unix://${socket}`, `http://127.0.0.1:${tcp.port}/gateway`]) {
    served = document;
    discoveryStatus = 200;
    async function run(args: string[], stdin?: string) {
      const previousDiscoveries = discoveryCount;
      const child = Bun.spawn([binary, ...args], {
        env: { ...process.env, SPROUT_BASE_URL: baseUrl },
        stdin: stdin === undefined ? "ignore" : new Blob([stdin]), stdout: "pipe", stderr: "pipe",
      });
      const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
      assert.equal(discoveryCount, previousDiscoveries + 1, `Must discover the live schema: ${args.join(" ")}`);
      return { stdout, stderr, code };
    }
    async function ok(args: string[], stdin?: string) {
      const result = await run(args, stdin);
      assert.equal(result.code, 0, `${args.join(" ")}\n${result.stderr}`);
      assert.equal(result.stderr, "");
      return result.stdout;
    }
    async function invalid(args: string[], message: RegExp) {
      const before = requests.length;
      const result = await run(args);
      assert.equal(result.code, 1, args.join(" "));
      assert.equal(result.stdout, "");
      assert.match(result.stderr, message);
      assert.equal(requests.length, before, "Invalid input must not invoke the operation");
    }
    const initialRequests = requests.length;
    assert.match(await ok(["--help"]), /inventory  Inventory commands/);
    assert.match(await ok([]), /greeting/);
    assert.match(await ok(["inventory", "parts"]), /Manage parts/);
    const help = await ok(["inventory", "parts", "revise", "--help"]);
    assert.match(help, /PATCH \/v2\/parts\/\{part\}/);
    assert.match(help, /--limit <integer>.*default 5/);
    assert.match(help, /--header.token <string>/);
    assert.match(help, /--query.help <string>/);
    assert.equal(requests.length, initialRequests, "Help must only fetch the contract");

    const command = ["inventory", "parts", "revise"];
    const body = '{"serial":9223372036854775807,"note":null}';
    const required = ["--header.token", "secret", "--body", body];
    const result = JSON.parse(await ok([...command, "a /空+", ...required, "--tag", "first", "--tag", '["空","last"]', "--code", "[1,2]", "--enabled=false", "--limit", "3", "--query.token", "query", "--session", "x /+", "--query.help", "literal", "--name", "-h"]));
    assert.equal(result.method, "PATCH");
    assert.equal(decodeURIComponent(result.path), "/v2/parts/a /空+");
    assert.equal(result.path.split("/").length, 4, "The slash inside the path parameter must remain encoded");
    assert.deepEqual(result.query, [["limit", "3"], ["tag", "first"], ["tag", "空"], ["tag", "last"], ["code", "1,2"], ["enabled", "false"], ["token", "query"], ["help", "literal"], ["name", "-h"]]);
    assert.equal(result.token, "secret");
    assert.equal(result.cookie, "session=x%20%2F%2B");
    assert.equal(result.contentType, "application/json");
    assert.equal(result.body, body, "64-bit JSON must survive without rounding");
    const literal = JSON.parse(await ok([...command, "--path.part=--help", ...required, "--name=--help"]));
    assert.equal(literal.path, "/v2/parts/--help");
    assert.deepEqual(literal.query, [["name", "--help"]], "Optional defaults must not be sent");

    const input = `{"path":{"part":"file"},"header":{"token":"from-input"},"body":${body}}`;
    await Bun.write(`${directory}/input.json`, input);
    await Bun.write(`${directory}/body.json`, body);
    for (const source of [input, `@${directory}/input.json`, "-"]) {
      const response = JSON.parse(await ok([...command, "--input", source], source === "-" ? input : undefined));
      assert.equal(response.body, body);
      assert.equal(response.token, "from-input");
    }
    for (const source of [`@${directory}/body.json`, "-"]) {
      assert.equal(JSON.parse(await ok([...command, "body", "--header.token", "secret", "--body", source], body)).body, body);
    }
    await invalid([...command, ...required], /Missing required argument.*path.part/);
    await invalid([...command, "part", "--body", body], /Missing required argument.*header.token/);
    await invalid([...command, "part", "--header.token", "secret"], /Missing required argument.*--body/);
    await invalid([...command, "part", ...required, "--token", "ambiguous"], /Unknown option --token/);
    await invalid([...command, "part", ...required, "--enabled", "maybe"], /Expected true or false/);
    await invalid([...command, "part", ...required, "--limit", "0"], /minimum/);
    await invalid([...command, "part", ...required, "--limit", "1.5"], /expected integer/);
    await invalid([...command, "part", ...required, "--name", "one", "--name", "two"], /Duplicate/);
    await invalid([...command, "part", "--header.token", "secret", "--body", '{"serial":1}'], /missing required field/);
    await invalid([...command, "part", "--input", input], /--input must be used alone/);
    await invalid([...command, "--input", '{"path":{"part":"p"},"query":{"unknown":1}}'], /Unknown query parameter/);
    await invalid(["inventory", "no-such-command"], /Unknown command/);
    assert.equal(await ok(["inventory", "parts", "remove", "part"]), "");
    assert.equal(requests.at(-1)!.method, "DELETE");
    assert.equal(await ok(["greeting"]), "hello from an unrelated API\n");
    assert.match(await ok(["numbers"]), /9223372036854775807/);
    const failure = await run(["inventory", "missing"]);
    assert.equal(failure.code, 1);
    assert.match(failure.stderr, /HTTP 404.*fixture missing/);

    served = { ...document, paths: { "/new-endpoint": { put: { operationId: "fresh.deep.command", responses } } } };
    const refreshed = await ok(["--help"]);
    assert.match(refreshed, /fresh/);
    assert.doesNotMatch(refreshed, /inventory|greeting/);
    assert.equal(await ok(["fresh", "deep", "command"]), "discovered after compilation\n");
    assert.equal(requests.at(-1)!.method, "PUT");
    await invalid(["inventory", "parts"], /Unknown command/);

    served = { error: "discovery unavailable" };
    discoveryStatus = 503;
    await invalid(["--help"], /HTTP 503.*discovery unavailable/);
    discoveryStatus = 200;
    await invalid(["--help"], /Missing string: openapi/);
  }
  console.log("Dynamic Native CLI passed: live OpenAPI replacement, nested commands, parameters, JSON/file/stdin, exact int64, errors, TCP + Unix Socket");
} finally {
  await unix.stop(true);
  await tcp.stop(true);
  await rm(directory, { recursive: true, force: true });
}
