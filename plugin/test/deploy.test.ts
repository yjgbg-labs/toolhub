import { afterAll, expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const directory = await mkdtemp("/tmp/opencode/plugin-deploy-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const root = new URL("../../", import.meta.url).pathname;
const jobs = Bun.YAML.parse(await Bun.file(join(root, "jobs.yaml")).text()) as { jobs: { name: string; run: string }[] };
const run = jobs.jobs.find(job => job.name === "pluging-deploy")!.run;
const bytes = new TextEncoder().encode('export default { id: "sprout", setup(ctx) { return ctx.options; } };');
const sha256 = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
let corrupt = false;
const server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
  if (new URL(request.url).pathname.endsWith(".sha256")) return new Response(`${sha256}  index.js\n`);
  return new Response(corrupt ? "corrupt" : bytes);
} });
afterAll(() => server.stop(true));
const config = { root: join(directory, "releases ' % home"), pluginDir: join(directory, "plugins") };
async function install(value = config) {
  const child = Bun.spawn(["sh", "-e", "-c", run], {
    cwd: root, stdout: "pipe", stderr: "pipe",
    env: { ...process.env, INSTALL_ONLY: "1", PLUGIN_SHA256: sha256, PLUGIN_INSTALL_ROOT: value.root, OPENCODE_PLUGIN_DIR: value.pluginDir, ARTIFACT_DOWNLOAD_BASE: server.url.href.replace(/\/$/, "") },
  });
  const [code, stdout, stderr] = await Promise.all([child.exited, new Response(child.stdout).text(), new Response(child.stderr).text()]);
  if (code !== 0) throw new Error(stdout + stderr);
}

test("plugin deploy loads an independent bundle and preserves plugin options", async () => {
  await install(config);
  const { default: plugin } = await import(pathToFileURL(join(config.pluginDir, "sprout.ts")).href);
  expect(plugin.id).toBe("sprout");
  expect(plugin.setup({ options: { baseUrl: "http://localhost:9000" } })).toEqual({ baseUrl: "http://localhost:9000" });
  await install(config);
});

test("checksum failure leaves the installed plugin entry intact", async () => {
  const target = join(config.pluginDir, "sprout.ts");
  const before = await readFile(target, "utf8");
  corrupt = true;
  try {
    await expect(install(config)).rejects.toThrow("FAILED");
    expect(await readFile(target, "utf8")).toBe(before);
  } finally { corrupt = false; }
});

test("deployment preserves an unrelated plugin", async () => {
  const pluginDir = join(directory, "user-plugins");
  await mkdir(pluginDir);
  const target = join(pluginDir, "sprout.ts");
  await writeFile(target, "user-owned plugin");
  await expect(install({ ...config, pluginDir })).rejects.toThrow("Unmanaged plugin");
  expect(await readFile(target, "utf8")).toBe("user-owned plugin");
});
