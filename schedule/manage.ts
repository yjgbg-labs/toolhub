#!/usr/bin/env bun
import {copyFile, mkdir, rename, chmod} from "node:fs/promises";
import {isAbsolute, join} from "node:path";

const base = process.env.SPROUT_BASE_URL || `http://127.0.0.1:${process.env.SERVER_PORT || 18080}`;
const home = process.env.SPROUT_HOME;
if (!home || !isAbsolute(home)) throw Error("SPROUT_HOME must be an absolute local server path");
const definitions = await Bun.file(join(import.meta.dir, "jobs.json")).json();
const names = Object.keys(definitions);

async function api(path: string, body?: unknown) {
  const response = await fetch(base + path, body === undefined ? {} : {
    method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body),
  });
  if (!response.ok) throw Error(`${path}: HTTP ${response.status}: ${await response.text()}`);
  return response.json();
}

async function check(name: string) {
  const source = await Bun.file(join(import.meta.dir, "scripts", name + ".js")).text();
  const query = new URLSearchParams({cpu: definitions[name].cpu, timeout: definitions[name].timeout});
  const response = await fetch(base + "/graaljs/start?" + query, {
    method: "POST", headers: {"Content-Type": "text/plain"}, body: source,
  });
  if (!response.ok) throw Error(`${name}: HTTP ${response.status}: ${await response.text()}`);
  const task = await response.json();
  const follow = await fetch(base + "/graaljs/logs-follow?id=" + encodeURIComponent(task.id));
  if (!follow.ok) throw Error(`${name}: log stream HTTP ${follow.status}`);
  const logs = await follow.text();
  const done = await api("/graaljs/status?id=" + encodeURIComponent(task.id));
  console.log(JSON.stringify({name, id: task.id, state: done.state, elapsedMs: done.elapsedTimeMs}));
  console.log(logs.trim());
  if (done.state !== "succeeded") throw Error(`${name}: ${JSON.stringify(done.error)}`);
}

switch (process.argv[2]) {
  case "check": {
    const checked = await Promise.allSettled(names.map(check));
    const failures = checked.filter(result => result.status === "rejected");
    if (failures.length) throw new AggregateError(failures.map(result => result.reason), "Schedule live checks failed");
    break;
  }
  case "deploy": {
    const config = await api("/schedule/config/read");
    for (const name of names) {
      for (const [key, amount] of Object.entries(definitions[name].resource) as [string, number][]) {
        if (!(config.resources[key] >= amount)) throw Error(`${name}: insufficient ${key} resource capacity`);
      }
    }
    const backup = join(home, "backups", "remote-schedules-" + new Date().toISOString().replace(/[:.]/g, "-"));
    await mkdir(backup, {recursive: true, mode: 0o700});
    await Bun.write(join(backup, "schedule-before.json"), JSON.stringify(config, null, 2) + "\n");
    await chmod(join(backup, "schedule-before.json"), 0o600);
    for (const name of names) {
      const previous = config.jobs[name]?.script;
      if (previous && await Bun.file(previous).exists()) {
        await copyFile(previous, join(backup, name + ".js"));
        await chmod(join(backup, name + ".js"), 0o600);
      }
    }
    console.log("Backup: " + backup);
    const destination = join(home, "scripts");
    await mkdir(destination, {recursive: true, mode: 0o700});
    for (const name of names) {
      const target = join(destination, name + ".js");
      const source = await Bun.file(join(import.meta.dir, "scripts", name + ".js")).text();
      const current = Bun.file(target);
      if (!await current.exists() || await current.text() !== source) {
        const stage = target + "." + crypto.randomUUID() + ".tmp";
        await Bun.write(stage, source);
        await chmod(stage, 0o600);
        await rename(stage, target);
      }
      await api("/schedule/new?name=" + encodeURIComponent(name), {
        ...config.jobs[name], ...definitions[name], script: target,
      });
      console.log("Installed " + name + " -> " + target);
    }
    break;
  }
  default: throw Error("Usage: bun schedule/manage.ts <check|deploy>");
}
