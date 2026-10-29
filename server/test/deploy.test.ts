import { afterAll, expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, readlink, rm, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";

const directory = await mkdtemp("/tmp/opencode/server-deploy-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const root = new URL("../../", import.meta.url).pathname;
const jobs = Bun.YAML.parse(await Bun.file(join(root, "jobs.yaml")).text()) as { jobs: { name: string; run: string }[] };
const run = jobs.jobs.find(job => job.name === "server-deploy")!.run;
const releases = ["first", "second"].map(value => {
  const bytes = new TextEncoder().encode(`test JAR ${value}`);
  return { bytes, sha256: new Bun.CryptoHasher("sha256").update(bytes).digest("hex") };
});

async function fixture(name: string) {
  const home = join(directory, name);
  const bin = join(home, "bin");
  const log = join(home, "commands.log");
  await mkdir(bin, { recursive: true });
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
  let corrupt = false;
  let healthy = true;
  const artifacts = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
    const path = new URL(request.url).pathname;
    const release = releases.find(value => path.startsWith(`/server/${value.sha256}/`));
    if (!release) return new Response(null, { status: 404 });
    if (path.endsWith(".sha256")) return new Response(`${release.sha256}  groot.jar\n`);
    return new Response(corrupt ? "corrupt" : release.bytes);
  } });
  const local = Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch() {
    const ready = healthy && (await readFile(log, "utf8")).includes("--user restart");
    return new Response(ready ? "ok" : "starting", { status: ready ? 200 : 503 });
  } });
  const env = {
    ...process.env, INSTALL_ONLY: "1", PATH: `${bin}:${process.env.PATH}`, COMMAND_LOG: log,
    SERVER_INSTALL_ROOT: join(home, "data '$ 100%"), SYSTEMD_UNIT_DIR: join(home, "units"),
    REAL_CURL: Bun.which("curl")!, TEST_HEALTH_URL: `${local.url.href}health`, SPROUT_HOME: join(home, "config"),
    ARTIFACT_DOWNLOAD_BASE: artifacts.url.href.replace(/\/$/, ""), SERVER_SERVICE: "groot-sprout-http.service",
    SERVER_START_TIMEOUT_MS: "1000",
  };
  return {
    env,
    commands: () => readFile(log, "utf8"),
    corrupt: () => { corrupt = true; },
    unhealthy: () => { healthy = false; },
    async install(sha256 = releases[0]!.sha256) {
      const child = Bun.spawn(["sh", "-e", "-c", run], { cwd: root, env: { ...env, SERVER_SHA256: sha256 }, stdout: "pipe", stderr: "pipe" });
      const [code, stdout, stderr] = await Promise.all([child.exited, new Response(child.stdout).text(), new Response(child.stderr).text()]);
      return { code, output: stdout + stderr };
    },
    stop: async () => { await artifacts.stop(true); await local.stop(true); },
  };
}

test("inline server deployment verifies artifacts and a user unit, then checks TCP health", async () => {
  const api = await fixture("server");
  try {
    const result = await api.install();
    expect(result.code, result.output).toBe(0);
    const unit = join(api.env.SYSTEMD_UNIT_DIR, api.env.SERVER_SERVICE);
    const content = await readFile(unit, "utf8");
    expect((await stat(unit)).mode & 0o777).toBe(0o644);
    expect(content).toContain('use-jdk "25" -- java');
    expect(content).not.toContain("SPROUT_PORT=");
    expect(content).toContain("data '$$ 100%%/server/current/groot.jar");
    expect((await api.commands()).trim().split("\n").map(line => line.split(" ")[1])).toEqual(["daemon-reload", "enable", "restart", "is-active", "is-enabled"]);
    expect((await api.install()).code).toBe(0);
    expect((await api.install(releases[1]!.sha256)).code).toBe(0);
    expect(await readlink(join(api.env.SERVER_INSTALL_ROOT, "server/current"))).toEndWith(`${releases[1]!.sha256}/server`);
  } finally { await api.stop(); }
});

test("checksum failure preserves the active server and does not restart it", async () => {
  const api = await fixture("checksum");
  try {
    expect((await api.install()).code).toBe(0);
    const before = await api.commands();
    api.corrupt();
    const result = await api.install(releases[1]!.sha256);
    expect(result.code).not.toBe(0);
    expect(result.output).toContain("FAILED");
    expect(await api.commands()).toBe(before);
    expect(await readlink(join(api.env.SERVER_INSTALL_ROOT, "server/current"))).toEndWith(`${releases[0]!.sha256}/server`);
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
