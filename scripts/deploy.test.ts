import { afterAll, expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, readlink, rm, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { deploy, runCommand, type DeploymentConfig, type Run } from "./deploy";
import { socketUrl } from "../cli/src/api/transport";

const temporary = await mkdtemp("/tmp/opencode/sprout-deploy-test-");
afterAll(() => rm(temporary, { recursive: true, force: true }));

async function bundle(version: string) {
  const directory = join(temporary, `bundle-${version}`);
  const payload = join(directory, "payload");
  for (const path of ["server", "cli/dist", "plugin/dist"]) await mkdir(join(payload, path), { recursive: true });
  await writeFile(join(payload, "server/groot.jar"), "test JAR");
  await writeFile(join(payload, "cli/dist/sprout.js"), `console.log(JSON.stringify({ version: ${JSON.stringify(version)}, url: process.env.SPROUT_BASE_URL, args: process.argv.slice(2) }));`);
  await writeFile(join(payload, "plugin/dist/index.js"), `export default { id: "sprout", setup(ctx) { return ctx.options; } };`);
  await writeFile(join(payload, "plugin/package.json"), JSON.stringify({ name: "opencode-sprout-plugin", type: "module", dependencies: {} }));
  const file = join(directory, "bundle.tar.gz");
  await runCommand(["tar", "-czf", file, "-C", payload, "."]);
  const bytes = new Uint8Array(await Bun.file(file).arrayBuffer());
  const sha256 = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
  return { bytes, sha256, artifact: `sprout-http-${sha256}.tar.gz` };
}

const first = await bundle("first");
const second = await bundle("second");
async function fixture(name: string) {
  const directory = join(temporary, name);
  await mkdir(directory, { recursive: true });
  const socket = join(directory, "http.sock");
  let downloads = 0;
  let corrupt = false;
  let started = false;
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return new Response(started && request.method === "GET" ? "ok" : "starting", { status: started ? 200 : 503 });
    const entry = [first, second].find(entry => url.pathname.endsWith(entry.artifact) || url.pathname.endsWith(`${entry.artifact}.sha256`));
    if (!entry) return new Response("Not found", { status: 404 });
    if (url.pathname.endsWith(".sha256")) return new Response(`${entry.sha256}  ${entry.artifact}\n`);
    downloads++;
    return new Response(corrupt ? "corrupt archive" : entry.bytes);
  } });
  const baseUrl = server.url.href.replace(/\/$/, "");
  const local = Bun.serve({ unix: socket, fetch(request) {
    return new Response(started && request.method === "GET" && new URL(request.url).pathname === "/health" ? "ok" : "starting", { status: started ? 200 : 503 });
  } });
  const config: DeploymentConfig = {
    root: join(directory, "data '$ 100%"), binDir: join(directory, "bin"), unitDir: join(directory, "units"), pluginDir: join(directory, "plugins"),
    downloadBase: `${baseUrl}/public/sprout-http`, artifact: first.artifact, sha256: first.sha256,
    service: "groot-sprout-http.service", socket, startupTimeout: 3000,
    baseUrl: socketUrl(socket), jdk: "25", bun: process.execPath, groot: Bun.which("groot")!, home: join(directory, "config"),
  };
  return {
    config, stop: () => { server.stop(true); local.stop(true); }, downloads: () => downloads,
    corrupt: () => { corrupt = true; }, start: () => { started = true; },
  };
}

test("CLI deploy verifies the remote release, forwards arguments and atomically upgrades its own link", async () => {
  const api = await fixture("cli");
  try {
    await deploy("cli", api.config);
    const path = join(api.config.binDir, "sprout");
    expect((await stat(path)).mode & 0o777).toBe(0o755);
    const process = Bun.spawn([path, "users", "get", "--id", "a b"], { env: { ...Bun.env, SPROUT_BASE_URL: "" }, stdout: "pipe", stderr: "pipe" });
    const result = JSON.parse(await new Response(process.stdout).text());
    expect(await process.exited).toBe(0);
    expect(result).toEqual({ version: "first", url: api.config.baseUrl, args: ["users", "get", "--id", "a b"] });
    const override = Bun.spawn([path, "health"], { env: { ...Bun.env, SPROUT_BASE_URL: "http://localhost:9000/api" }, stdout: "pipe", stderr: "pipe" });
    expect(JSON.parse(await new Response(override.stdout).text()).url).toBe("http://localhost:9000/api");
    expect(await override.exited).toBe(0);
    await deploy("cli", api.config);
    expect(api.downloads()).toBe(1);
    await deploy("cli", { ...api.config, artifact: second.artifact, sha256: second.sha256 });
    expect(await readlink(join(api.config.root, "cli/current"))).toEndWith(`${second.sha256}/cli`);
  } finally { api.stop(); }
});

test("checksum failure preserves the active release and installed CLI", async () => {
  const api = await fixture("checksum");
  try {
    await deploy("cli", api.config);
    const path = join(api.config.binDir, "sprout");
    const before = await readFile(path, "utf8");
    api.corrupt();
    await expect(deploy("cli", { ...api.config, artifact: second.artifact, sha256: second.sha256 })).rejects.toThrow("SHA-256 mismatch");
    expect(await readFile(path, "utf8")).toBe(before);
    expect(await readlink(join(api.config.root, "cli/current"))).toEndWith(`${first.sha256}/cli`);
  } finally { api.stop(); }
});

test("server deploy renders a valid user unit, manages the runtime directory and checks Unix socket health", async () => {
  const api = await fixture("server");
  const commands: string[][] = [];
  const run: Run = async (args, cwd) => {
    commands.push(args);
    if (args[0] === "systemctl") { if (args.includes("restart")) api.start(); return; }
    if (args[0] === api.config.groot) return;
    await runCommand(args, cwd);
  };
  try {
    await deploy("server", api.config, run);
    const unit = join(api.config.unitDir, api.config.service);
    const content = await readFile(unit, "utf8");
    expect((await stat(unit)).mode & 0o777).toBe(0o644);
    expect(content).toContain('"use-jdk" "25" "--" "java"');
    expect(content).toContain(`SPROUT_SOCKET=${api.config.socket}`);
    expect(content).toContain("RuntimeDirectory=sprout-http\nRuntimeDirectoryMode=0700\nUMask=0077");
    expect(content).toContain(`SPROUT_HOME=${api.config.home}`);
    expect(content).toContain("WantedBy=default.target");
    expect(content).toContain("data '$$ 100%%/server/current/groot.jar");
    expect(commands).toContainEqual([api.config.groot, "use-jdk", "25", "--", "java", "--version"]);
    expect(commands.filter(args => args[0] === "systemctl").map(args => args[2])).toEqual(["daemon-reload", "enable", "restart", "is-active", "is-enabled"]);
  } finally { api.stop(); }
});

test("plugin deploy loads the self-contained bundle without installing dependencies", async () => {
  const api = await fixture("plugin");
  const run: Run = async (args, cwd) => {
    expect(args[0]).not.toBe(api.config.bun);
    await runCommand(args, cwd);
  };
  try {
    await deploy("plugin", api.config, run);
    const { default: plugin } = await import(pathToFileURL(join(api.config.pluginDir, "sprout.ts")).href);
    expect(plugin.id).toBe("sprout");
    expect(plugin.setup({ options: {} })).toEqual({ baseUrl: api.config.baseUrl });
    expect(plugin.setup({ options: { baseUrl: "http://localhost:9000" } }).baseUrl).toBe("http://localhost:9000");
  } finally { api.stop(); }
});

test("deployment preserves an existing unrelated command", async () => {
  const api = await fixture("existing");
  try {
    await mkdir(api.config.binDir, { recursive: true });
    const path = join(api.config.binDir, "sprout");
    await writeFile(path, "user-owned command");
    await expect(deploy("cli", api.config)).rejects.toThrow("unmanaged file");
    expect(await readFile(path, "utf8")).toBe("user-owned command");
    expect(api.downloads()).toBe(0);
  } finally { api.stop(); }
});
