import { expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { migrateSchedule, type MigrationOptions } from "./migrate-schedule";

async function fixture() {
  const root = await mkdtemp("/tmp/opencode/sprout-migration-test-");
  const source = join(root, "groot");
  const destination = join(root, "sprout");
  await mkdir(join(source, "schedule/state"), { recursive: true });
  await mkdir(join(source, "schedule/logs"));
  await mkdir(destination);
  const original = { resources: {}, tickMs: 1000, jobs: { sample: { every: "1h", command: ["/bin/true"], resource: {}, enabled: true } } };
  const empty = { resources: {}, tickMs: 1000, jobs: {} };
  const state = { lastRunAt: "2026-09-19T00:00:00Z", lastExitCode: 7 };
  await writeFile(join(source, "schedule.json"), JSON.stringify(original));
  await writeFile(join(source, "schedule/state/sample.json"), JSON.stringify(state));
  await writeFile(join(source, "schedule/state/unrelated-scratch.json"), "not a scheduler state");
  await writeFile(join(source, "schedule/logs/sample.log"), "historical output\n");
  await writeFile(join(destination, "schedule.json"), JSON.stringify(empty));
  const status = { oldActive: true, newActive: true, enabled: true, failMigrated: false };
  const commands: string[][] = [];
  const doc = { openapi: "3.1.2", info: { title: "migration fixture", version: "1" }, paths: Object.fromEntries(
    ["schedule.config.read", "schedule.list"].map(id => ["/" + id.replaceAll(".", "/"), { post: {
      operationId: id, responses: { "200": { description: "ok", content: { "application/json": { schema: { type: id === "schedule.list" ? "array" : "object" } } } } },
    } }]),
  ) };
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch(request) {
    if (!status.newActive) return new Response("Unavailable", { status: 503 });
    const path = new URL(request.url).pathname;
    if (path === "/openapi.json") return Response.json(doc);
    const config = JSON.parse(await readFile(join(destination, "schedule.json"), "utf8"));
    if (status.failMigrated && Object.keys(config.jobs).length) return new Response("failed startup", { status: 503 });
    return Response.json(path === "/schedule/list" ? Object.keys(config.jobs).map(name => ({ name })) : config);
  } });
  const run = async (args: string[]) => {
    commands.push(args);
    if (args[0] === "/usr/bin/env") {
      expect(status.oldActive).toBe(false);
      expect(status.newActive).toBe(false);
      return JSON.stringify(original);
    }
    const action = args[2];
    const old = args[3] === "groot-schedule.service";
    if (action === "show") {
      if (args[5] === "WorkingDirectory") return "/legacy/workdir";
      if (args[5] === "UnitFileState") return status.enabled ? "enabled" : "disabled";
      return (old ? status.oldActive : status.newActive) ? "active" : "inactive";
    }
    if (action === "stop") { if (old) status.oldActive = false; else status.newActive = false; }
    if (action === "start") { if (old) status.oldActive = true; else status.newActive = true; }
    if (action === "disable") status.enabled = false;
    if (action === "enable") status.enabled = true;
    return "";
  };
  const options: MigrationOptions = {
    source, destination, service: "groot-sprout-http.service", oldService: "groot-schedule.service",
    baseUrl: server.url.href.replace(/\/$/, ""), timeout: 100, groot: "/test/groot",
  };
  return { root, source, destination, state, original, empty, options, status, commands, run, async close() { server.stop(true); await rm(root, { recursive: true, force: true }); } };
}

test("migration stops old execution before copying and preserves jobs, runtime state, cwd and logs", async () => {
  const f = await fixture();
  try {
    await migrateSchedule(f.options, f.run);
    expect(f.status).toMatchObject({ oldActive: false, newActive: true, enabled: false });
    const config = JSON.parse(await readFile(join(f.destination, "schedule.json"), "utf8"));
    expect(config.jobs.sample).toEqual({ ...f.original.jobs.sample, cwd: "/legacy/workdir" });
    expect(JSON.parse(await readFile(join(f.destination, "schedule/state/sample.json"), "utf8"))).toEqual(f.state);
    expect(await readdir(join(f.destination, "schedule/state"))).toEqual(["sample.json"]);
    expect(await readFile(join(f.destination, "schedule/logs/sample.log"), "utf8")).toBe("historical output\n");
    expect(JSON.parse(await readFile(join(f.source, "schedule.json"), "utf8"))).toEqual(f.original);
    expect(f.commands.at(-1)).toEqual(["systemctl", "--user", "disable", "groot-schedule.service"]);
  } finally { await f.close(); }
});

test("a failed cutover stops Sprout before restoring and restarting the old executor", async () => {
  const f = await fixture();
  try {
    f.status.failMigrated = true;
    await expect(migrateSchedule(f.options, f.run)).rejects.toThrow("did not become healthy");
    expect(f.status).toMatchObject({ oldActive: true, newActive: true, enabled: true });
    expect(JSON.parse(await readFile(join(f.destination, "schedule.json"), "utf8"))).toEqual(f.empty);
    expect(f.commands.slice(-3)).toEqual([
      ["systemctl", "--user", "stop", "groot-sprout-http.service"],
      ["systemctl", "--user", "start", "groot-schedule.service"],
      ["systemctl", "--user", "start", "groot-sprout-http.service"],
    ]);
    expect((await readdir(f.root)).some(name => name.includes("failed-migration"))).toBe(true);
  } finally { await f.close(); }
});

test("migration does not replace an already populated Sprout scheduler", async () => {
  const f = await fixture();
  try {
    await writeFile(join(f.destination, "schedule.json"), JSON.stringify(f.original));
    await expect(migrateSchedule(f.options, f.run)).rejects.toThrow("already has schedule jobs");
    expect(f.commands).toEqual([]);
  } finally { await f.close(); }
});
