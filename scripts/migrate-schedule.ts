#!/usr/bin/env bun
import { cp, lstat, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { isDeepStrictEqual } from "node:util";
import { ApiClient } from "../cli/src/api";

export interface MigrationOptions {
  source: string;
  destination: string;
  service: string;
  oldService: string;
  baseUrl: string;
  timeout: number;
  groot: string;
}
type Command = (args: string[]) => Promise<string>;
const command: Command = async args => {
  const child = Bun.spawn(args, { stdin: "ignore", stdout: "pipe", stderr: "pipe" });
  const [out, error, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
  if (code !== 0) throw new Error(`${args.join(" ")} exited ${code}: ${error}`);
  return out.trim();
};
async function exists(path: string): Promise<boolean> {
  try { await lstat(path); return true; }
  catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return false; throw error; }
}

export async function migrateSchedule(options: MigrationOptions, run: Command = command) {
  const source = resolve(options.source);
  const destination = resolve(options.destination);
  if (source === destination) throw new Error("Source and destination must differ");
  const client = await ApiClient.connect({ baseUrl: options.baseUrl });
  const current = (await client.call("schedule.config.read")).body;
  if (Object.keys(current.jobs).length) throw new Error("Sprout already has schedule jobs; migration requires an empty scheduler");
  const systemctl = (...args: string[]) => run(["systemctl", "--user", ...args]);
  const oldActive = await systemctl("show", options.oldService, "-p", "ActiveState", "--value") === "active";
  const oldEnabled = await systemctl("show", options.oldService, "-p", "UnitFileState", "--value") === "enabled";
  const cwd = await systemctl("show", options.oldService, "-p", "WorkingDirectory", "--value");
  await mkdir(dirname(destination), { recursive: true });
  const stage = await mkdtemp(join(dirname(destination), ".sprout-schedule-migration-"));
  const backup = `${destination}.before-schedule-migration-${Date.now()}`;
  let stopped = false;
  let backedUp = false;
  let installed = false;
  let oldDisabled = false;
  try {
    // Stop the sole executor before taking the final runtime snapshot.
    await systemctl("stop", options.oldService);
    stopped = true;
    await systemctl("stop", options.service);
    const config = JSON.parse(await run(["/usr/bin/env", `GROOT_HOME=${source}`, options.groot, "schedule", "config", "read"]));
    // Preserve the old service's working directory for relative command arguments.
    if (cwd) for (const job of Object.values(config.jobs) as Array<Record<string, unknown>>) job.cwd ??= cwd;
    if (await exists(destination)) {
      const existing = JSON.parse(await readFile(join(destination, "schedule.json"), "utf8"));
      if (Object.keys(existing.jobs).length) throw new Error("Sprout configuration changed during migration");
      await cp(destination, stage, { recursive: true });
    }
    await mkdir(join(stage, "schedule"), { recursive: true });
    // Copy only scheduler-owned state, not application scratch files that happen to share the old state directory.
    await rm(join(stage, "schedule", "state"), { recursive: true, force: true });
    await mkdir(join(stage, "schedule", "state"), { recursive: true });
    for (const name of Object.keys(config.jobs)) {
      const filename = `${encodeURIComponent(name)}.json`;
      const path = join(source, "schedule", "state", filename);
      if (await exists(path)) await cp(path, join(stage, "schedule", "state", filename));
    }
    const logs = join(source, "schedule", "logs");
    if (await exists(logs)) await cp(logs, join(stage, "schedule", "logs"), { recursive: true });
    await writeFile(join(stage, "schedule.json"), JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
    await writeFile(join(stage, "schedule", "migration.json"), JSON.stringify({ source, migratedAt: new Date().toISOString(), jobs: Object.keys(config.jobs) }, null, 2) + "\n");
    if (await exists(destination)) { await rename(destination, backup); backedUp = true; }
    await rename(stage, destination);
    installed = true;
    await systemctl("start", options.service);
    const deadline = Date.now() + options.timeout;
    let healthy = false;
    while (Date.now() < deadline) {
      try {
        const actual = (await client.call("schedule.config.read")).body;
        if (!isDeepStrictEqual(actual, config)) throw new Error("Migrated config does not match");
        const rows = (await client.call("schedule.list")).body;
        if (rows.length !== Object.keys(config.jobs).length) throw new Error("Migrated jobs do not match");
        healthy = true;
        break;
      } catch { await Bun.sleep(250); }
    }
    if (!healthy) throw new Error("Sprout did not become healthy with the migrated scheduler");
    await systemctl("disable", options.oldService);
    oldDisabled = true;
    console.log(`Migrated ${Object.keys(config.jobs).length} jobs to ${destination}; ${options.oldService} stopped and disabled. Previous Sprout home: ${backedUp ? backup : "none"}`);
  } catch (error) {
    if (stopped) {
      // Never restore the old executor until the new executor has stopped.
      await systemctl("stop", options.service);
      if (installed) await rename(destination, `${destination}.failed-migration-${Date.now()}`);
      if (backedUp) await rename(backup, destination);
      if (oldDisabled && oldEnabled) await systemctl("enable", options.oldService);
      if (oldActive) await systemctl("start", options.oldService);
      await systemctl("start", options.service);
    }
    throw error;
  } finally { await rm(stage, { recursive: true, force: true }); }
}

if (import.meta.main) {
  const home = process.env.HOME!;
  await migrateSchedule({
    source: process.env.GROOT_HOME ?? join(home, ".config/groot"),
    destination: process.env.SPROUT_HOME!, service: process.env.SERVER_SERVICE!,
    oldService: "groot-schedule.service", baseUrl: process.env.CLIENT_BASE_URL!,
    timeout: Number(process.env.SERVER_START_TIMEOUT_MS), groot: Bun.which("groot")!,
  });
}
