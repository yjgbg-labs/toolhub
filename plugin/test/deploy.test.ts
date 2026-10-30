import { afterAll, expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const directory = await mkdtemp("/tmp/opencode/plugin-deploy-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const root = new URL("../../", import.meta.url).pathname;
const jobs = Bun.YAML.parse(await Bun.file(join(root, "jobs.yaml")).text()) as { jobs: { name: string; run: string }[] };
const run = jobs.jobs.find(job => job.name === "pluging-deploy")!.run;
const bundle = 'export default { id: "sprout", setup(ctx) { return ctx.options; } };';

async function fixture(name: string) {
  const home = join(directory, name);
  const cwd = join(home, "project");
  const source = join(cwd, "plugin/dist/index.js");
  const pluginDir = join(home, "plugins");
  await mkdir(join(cwd, "plugin/dist"), { recursive: true });
  await writeFile(join(cwd, "config.env"), await readFile(join(root, "config.env")));
  await writeFile(source, bundle);
  return {
    source, pluginDir, target: join(pluginDir, "sprout.ts"),
    async install(checkOnly = false) {
      const child = Bun.spawn(["sh", "-e", "-c", run], {
        cwd, stdout: "pipe", stderr: "pipe",
        env: { ...process.env, INSTALL_ONLY: "1", CHECK_ONLY: checkOnly ? "1" : "0", OPENCODE_PLUGIN_DIR: pluginDir },
      });
      const [code, stdout, stderr] = await Promise.all([child.exited, new Response(child.stdout).text(), new Response(child.stderr).text()]);
      return { code, output: stdout + stderr };
    },
  };
}

test("plugin deploy replaces the legacy wrapper with a standalone bundle and preserves options", async () => {
  const api = await fixture("install ' % home");
  await mkdir(api.pluginDir);
  await writeFile(api.target, '// Managed by sprout-http deployment\nexport { default } from "file:///old-release/index.js";');
  const result = await api.install();
  expect(result.code, result.output).toBe(0);
  const { default: plugin } = await import(pathToFileURL(api.target).href);
  expect(plugin.id).toBe("sprout");
  expect(plugin.setup({ options: { baseUrl: "http://localhost:9000" } })).toEqual({ baseUrl: "http://localhost:9000" });
  expect((await stat(api.target)).mode & 0o777).toBe(0o644);
  expect(await readFile(api.source, "utf8")).toBe(bundle);
  await writeFile(api.source, bundle.replace('id: "sprout"', 'id: "updated"'));
  expect((await api.install()).code).toBe(0);
  expect(await readFile(api.target, "utf8")).toContain('id: "updated"');
});

test("missing or empty local artifacts leave the installed plugin intact", async () => {
  const api = await fixture("missing");
  expect((await api.install()).code).toBe(0);
  const before = await readFile(api.target, "utf8");
  await rm(api.source);
  expect((await api.install()).code).not.toBe(0);
  await writeFile(api.source, "");
  expect((await api.install()).code).not.toBe(0);
  expect(await readFile(api.target, "utf8")).toBe(before);
});

test("deployment preserves an unrelated plugin", async () => {
  const api = await fixture("unmanaged");
  await mkdir(api.pluginDir);
  await writeFile(api.target, "user-owned plugin");
  const result = await api.install();
  expect(result.code).not.toBe(0);
  expect(result.output).toContain("Unmanaged plugin");
  expect(await readFile(api.target, "utf8")).toBe("user-owned plugin");
});

test("CHECK_ONLY takes precedence over INSTALL_ONLY without installing a plugin", async () => {
  const api = await fixture("check");
  const result = await api.install(true);
  expect(result.code, result.output).toBe(0);
  expect(await Bun.file(api.target).exists()).toBe(false);
  expect(await readFile(api.source, "utf8")).toBe(bundle);
});
