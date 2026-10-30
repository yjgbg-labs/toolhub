import { afterAll, expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import { join } from "node:path";

const directory = await mkdtemp("/tmp/opencode/server-deploy-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const root = new URL("../../", import.meta.url).pathname;
const jobs = Bun.YAML.parse(await Bun.file(join(root, "jobs.yaml")).text()) as { jobs: { name: string; run: string }[] };
const run = jobs.jobs.find(job => job.name === "server-deploy")!.run;

async function fixture(name: string) {
  const home = join(directory, name);
  const bin = join(home, "bin");
  const log = join(home, "commands.log");
  const cwd = join(home, "project");
  const source = join(cwd, "server/dist/groot.jar");
  await mkdir(bin, { recursive: true });
  await mkdir(join(cwd, "server/dist"), { recursive: true });
  await writeFile(join(cwd, "config.env"), await readFile(join(root, "config.env")));
  await writeFile(source, "first JAR");
  await writeFile(log, "");
  await writeFile(join(bin, "groot"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
  await writeFile(join(bin, "systemctl"), '#!/bin/sh\nprintf "%s\\n" "$*" >> "$COMMAND_LOG"\n', { mode: 0o755 });
  await writeFile(join(bin, "curl"), `#!/bin/sh
if [ "$1" = -fsS ]; then
  test "$4" = http://127.0.0.1:18080/health || exit 2
  set -- -fsS --max-time 2 "$TEST_HEALTH_URL"
fi
exec "$REAL_CURL" "$@"
`, { mode: 0o755 });
  let healthy = true;
  const local = Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch() {
    const ready = healthy && (await readFile(log, "utf8")).includes("--user restart");
    return new Response(ready ? "ok" : "starting", { status: ready ? 200 : 503 });
  } });
  const env = {
    ...process.env, INSTALL_ONLY: "1", CHECK_ONLY: "0", PATH: `${bin}:${process.env.PATH}`, COMMAND_LOG: log,
    SERVER_INSTALL_ROOT: join(home, "data '$ 100%"), SYSTEMD_UNIT_DIR: join(home, "units"),
    REAL_CURL: Bun.which("curl")!, TEST_HEALTH_URL: `${local.url.href}health`, SPROUT_HOME: join(home, "config"),
    SERVER_SERVICE: "groot-sprout-http.service",
    SERVER_START_TIMEOUT_MS: "1000",
  };
  return {
    env, source,
    commands: () => readFile(log, "utf8"),
    unhealthy: () => { healthy = false; },
    async install(checkOnly = false) {
      const child = Bun.spawn(["sh", "-e", "-c", run], { cwd, env: { ...env, CHECK_ONLY: checkOnly ? "1" : "0" }, stdout: "pipe", stderr: "pipe" });
      const [code, stdout, stderr] = await Promise.all([child.exited, new Response(child.stdout).text(), new Response(child.stderr).text()]);
      return { code, output: stdout + stderr };
    },
    stop: () => local.stop(true),
  };
}

test("inline server deployment installs a local JAR, migrates the user unit and checks TCP health", async () => {
  const api = await fixture("server");
  try {
    const legacy = join(api.env.SERVER_INSTALL_ROOT, "releases/old/server");
    await mkdir(legacy, { recursive: true });
    await writeFile(join(legacy, "groot.jar"), "old JAR");
    await mkdir(join(api.env.SERVER_INSTALL_ROOT, "server"));
    await symlink(legacy, join(api.env.SERVER_INSTALL_ROOT, "server/current"));
    await mkdir(api.env.SYSTEMD_UNIT_DIR);
    await writeFile(join(api.env.SYSTEMD_UNIT_DIR, api.env.SERVER_SERVICE), "# Managed by sprout-http deployment\nold unit");
    const result = await api.install();
    expect(result.code, result.output).toBe(0);
    const unit = join(api.env.SYSTEMD_UNIT_DIR, api.env.SERVER_SERVICE);
    const content = await readFile(unit, "utf8");
    expect((await stat(unit)).mode & 0o777).toBe(0o644);
    expect(content).toContain('use-jdk "25" -- java');
    expect(content).not.toContain("SPROUT_PORT=");
    expect(content).toContain("data '$$ 100%%/server/groot.jar");
    expect(content).not.toContain("/current/");
    expect((await api.commands()).trim().split("\n").map(line => line.split(" ")[1])).toEqual(["daemon-reload", "enable", "restart", "is-active", "is-enabled"]);
    expect((await api.install()).code).toBe(0);
    await writeFile(api.source, "second JAR");
    expect((await api.install()).code).toBe(0);
    expect(await readFile(join(api.env.SERVER_INSTALL_ROOT, "server/groot.jar"), "utf8")).toBe("second JAR");
    expect(await readFile(api.source, "utf8")).toBe("second JAR");
    expect(await readFile(join(legacy, "groot.jar"), "utf8")).toBe("old JAR");
  } finally { await api.stop(); }
});

test("missing or empty local artifacts preserve the active server without restarting", async () => {
  const api = await fixture("missing");
  try {
    expect((await api.install()).code).toBe(0);
    const before = await api.commands();
    const unit = join(api.env.SYSTEMD_UNIT_DIR, api.env.SERVER_SERVICE);
    const beforeUnit = await readFile(unit, "utf8");
    await rm(api.source);
    expect((await api.install()).code).not.toBe(0);
    await writeFile(api.source, "");
    expect((await api.install()).code).not.toBe(0);
    expect(await api.commands()).toBe(before);
    expect(await readFile(unit, "utf8")).toBe(beforeUnit);
    expect(await readFile(join(api.env.SERVER_INSTALL_ROOT, "server/groot.jar"), "utf8")).toBe("first JAR");
  } finally { await api.stop(); }
});

test("CHECK_ONLY takes precedence over INSTALL_ONLY without changing the server", async () => {
  const api = await fixture("check");
  try {
    const result = await api.install(true);
    expect(result.code, result.output).toBe(0);
    expect(await api.commands()).toBe("");
    expect(await Bun.file(join(api.env.SERVER_INSTALL_ROOT, "server/groot.jar")).exists()).toBe(false);
    expect(await Bun.file(join(api.env.SYSTEMD_UNIT_DIR, api.env.SERVER_SERVICE)).exists()).toBe(false);
  } finally { await api.stop(); }
});

test("an unrelated user service is preserved", async () => {
  const api = await fixture("unmanaged");
  try {
    await mkdir(api.env.SYSTEMD_UNIT_DIR);
    const target = join(api.env.SYSTEMD_UNIT_DIR, api.env.SERVER_SERVICE);
    await writeFile(target, "user-owned unit");
    const result = await api.install();
    expect(result.code).not.toBe(0);
    expect(result.output).toContain("Unmanaged unit");
    expect(await readFile(target, "utf8")).toBe("user-owned unit");
    expect(await api.commands()).toBe("");
  } finally { await api.stop(); }
});

test("deployment fails when TCP health never becomes ready", async () => {
  const api = await fixture("unhealthy");
  try {
    api.unhealthy();
    const result = await api.install();
    expect(result.code).not.toBe(0);
    expect(result.output).toContain("Server health check timed out");
  } finally { await api.stop(); }
});
