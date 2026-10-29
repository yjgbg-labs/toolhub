import { afterAll, expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, readlink, rm, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { install, runCommand, type Run, type ServerConfig } from "../scripts/install";

const directory = await mkdtemp("/tmp/opencode/server-deploy-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const releases = ["first", "second"].map(value => {
  const bytes = new TextEncoder().encode(`test JAR ${value}`);
  return { bytes, sha256: new Bun.CryptoHasher("sha256").update(bytes).digest("hex") };
});
async function fixture(name: string) {
  const root = join(directory, name);
  await mkdir(root);
  const socket = join(root, "http.sock");
  let started = false;
  let corrupt = false;
  let downloads = 0;
  const http = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
    const path = new URL(request.url).pathname;
    const release = releases.find(value => path.startsWith(`/server/${value.sha256}/`));
    if (!release) return new Response(null, { status: 404 });
    if (path.endsWith(".sha256")) return new Response(`${release.sha256}  groot.jar\n`);
    downloads++;
    return new Response(corrupt ? "corrupt" : release.bytes);
  } });
  const local = Bun.serve({ unix: socket, fetch: () => new Response(started ? "ok" : "starting", { status: started ? 200 : 503 }) });
  const config: ServerConfig = {
    root: join(root, "data '$ 100%"), unitDir: join(root, "units"), socket,
    downloadBase: http.url.href.replace(/\/$/, ""), sha256: releases[0]!.sha256,
    service: "groot-sprout-http.service", startupTimeout: 3000, jdk: "25", groot: Bun.which("groot")!, home: join(root, "config"),
  };
  const commands: string[][] = [];
  const run: Run = async args => {
    commands.push(args);
    if (args[0] === "systemctl") { if (args.includes("restart")) started = true; return; }
    if (args[0] !== config.groot) await runCommand(args);
  };
  return { config, run, commands, downloads: () => downloads, corrupt: () => { corrupt = true; }, stop: async () => { await http.stop(true); await local.stop(true); } };
}

test("server-only artifact deploys a valid user unit, checks socket health and reuses verified releases", async () => {
  const api = await fixture("server");
  try {
    await install(api.config, api.run);
    const unit = join(api.config.unitDir, api.config.service);
    const content = await readFile(unit, "utf8");
    expect((await stat(unit)).mode & 0o777).toBe(0o644);
    expect(content).toContain('"use-jdk" "25" "--" "java"');
    expect(content).toContain(`SPROUT_SOCKET=${api.config.socket}`);
    expect(content).toContain("RuntimeDirectory=sprout-http\nRuntimeDirectoryMode=0700\nUMask=0077");
    expect(content).toContain("data '$$ 100%%/server/current/groot.jar");
    expect(api.commands.filter(args => args[0] === "systemctl").map(args => args[2])).toEqual(["daemon-reload", "enable", "restart", "is-active", "is-enabled"]);
    await install(api.config, api.run);
    expect(api.downloads()).toBe(1);
    await install({ ...api.config, sha256: releases[1]!.sha256 }, api.run);
    expect(await readlink(join(api.config.root, "server/current"))).toEndWith(`${releases[1]!.sha256}/server`);
  } finally { await api.stop(); }
});

test("checksum failure preserves the active server and does not restart it", async () => {
  const api = await fixture("checksum");
  try {
    await install(api.config, api.run);
    const before = api.commands.length;
    api.corrupt();
    await expect(install({ ...api.config, sha256: releases[1]!.sha256 }, api.run)).rejects.toThrow("SHA-256 mismatch");
    expect(api.commands).toHaveLength(before);
    expect(await readlink(join(api.config.root, "server/current"))).toEndWith(`${releases[0]!.sha256}/server`);
  } finally { await api.stop(); }
});

test("an unrelated user service is preserved", async () => {
  const api = await fixture("unmanaged");
  try {
    await mkdir(api.config.unitDir);
    const target = join(api.config.unitDir, api.config.service);
    await writeFile(target, "user-owned unit");
    await expect(install(api.config, api.run)).rejects.toThrow("Unmanaged unit");
    expect(await readFile(target, "utf8")).toBe("user-owned unit");
    expect(api.downloads()).toBe(0);
  } finally { await api.stop(); }
});
