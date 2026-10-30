import { afterAll, expect, test } from "bun:test";
import { lstat, mkdir, mkdtemp, readFile, readdir, rm, symlink, writeFile } from "node:fs/promises";
import { join } from "node:path";

const directory = await mkdtemp("/tmp/opencode/native-install-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const root = new URL("../../", import.meta.url).pathname;
const jobs = Bun.YAML.parse(await Bun.file(join(root, "jobs.yaml")).text()) as { jobs: { name: string; run: string }[] };
const run = jobs.jobs.find(job => job.name === "cli-deploy")!.run;
const bytes = await readFile(join(root, "cli/dist/groot-cli"));

async function fixture(name: string) {
  const home = join(directory, name);
  const cwd = join(home, "project");
  const source = join(cwd, "cli/dist/groot-cli");
  const bin = join(home, "bin");
  const target = join(bin, "groot-cli");
  await mkdir(join(cwd, "cli/dist"), { recursive: true });
  await writeFile(join(cwd, "config.env"), await readFile(join(root, "config.env")));
  await writeFile(source, bytes);
  return {
    home, source, bin, target,
    async install(checkOnly = false) {
      const child = Bun.spawn(["sh", "-e", "-c", run], {
        cwd, env: { ...process.env, HOME: home, INSTALL_ONLY: "1", CHECK_ONLY: checkOnly ? "1" : "0", CLI_BIN_DIR: bin },
        stdout: "pipe", stderr: "pipe",
      });
      const [code, stdout, stderr] = await Promise.all([child.exited, new Response(child.stdout).text(), new Response(child.stderr).text()]);
      return { code, output: stdout + stderr };
    },
  };
}

test("Native installation moves the local binary and supports replacing a regular executable", async () => {
  const api = await fixture("install ' % home");
  const result = await api.install();
  expect(result.code, result.output).toBe(0);
  expect((await lstat(api.target)).isFile()).toBe(true);
  expect((await lstat(api.target)).mode & 0o777).toBe(0o755);
  expect(await readdir(api.bin)).toEqual(["groot-cli"]);
  expect((await readFile(api.target)).equals(bytes)).toBe(true);
  expect(await Bun.file(api.source).exists()).toBe(false);
  await writeFile(api.source, "replacement binary");
  expect((await api.install()).code).toBe(0);
  expect(await readFile(api.target, "utf8")).toBe("replacement binary");
  expect(await Bun.file(api.source).exists()).toBe(false);
});

test("installation replaces the legacy release symlink without modifying its target", async () => {
  const api = await fixture("legacy");
  const release = join(api.home, "releases/old");
  await mkdir(release, { recursive: true });
  await mkdir(api.bin);
  const oldBinary = join(release, "groot-cli");
  await writeFile(oldBinary, "old binary");
  await symlink(oldBinary, api.target);
  const result = await api.install();
  expect(result.code, result.output).toBe(0);
  expect((await lstat(api.target)).isFile()).toBe(true);
  expect((await readFile(api.target)).equals(bytes)).toBe(true);
  expect(await readFile(oldBinary, "utf8")).toBe("old binary");
});

test("missing or empty local artifacts preserve the installed command", async () => {
  const api = await fixture("missing");
  expect((await api.install()).code).toBe(0);
  expect((await api.install()).code).not.toBe(0);
  await writeFile(api.source, "");
  expect((await api.install()).code).not.toBe(0);
  expect((await readFile(api.target)).equals(bytes)).toBe(true);
});

test("CHECK_ONLY takes precedence over INSTALL_ONLY and keeps the local binary", async () => {
  const api = await fixture("check");
  const result = await api.install(true);
  expect(result.code, result.output).toBe(0);
  expect((await readFile(api.source)).equals(bytes)).toBe(true);
  expect(await Bun.file(api.target).exists()).toBe(false);
});
