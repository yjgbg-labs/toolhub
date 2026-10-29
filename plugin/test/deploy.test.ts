import { afterAll, expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { install } from "../scripts/install";

const directory = await mkdtemp("/tmp/opencode/plugin-deploy-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const bytes = new TextEncoder().encode('export default { id: "sprout", setup(ctx) { return ctx.options; } };');
const sha256 = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
let corrupt = false;
const server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
  if (new URL(request.url).pathname.endsWith(".sha256")) return new Response(`${sha256}  index.js\n`);
  return new Response(corrupt ? "corrupt" : bytes);
} });
afterAll(() => server.stop(true));
const config = { root: join(directory, "releases-home"), pluginDir: join(directory, "plugins"), downloadBase: server.url.href, sha256 };

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
    await expect(install({ ...config, root: join(directory, "corrupt-release") })).rejects.toThrow("SHA-256 mismatch");
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
