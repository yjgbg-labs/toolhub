import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { ApiClient } from "../api";
import { takeOption } from "./input";

export function scheduleArgs(id: string, args: string[]): string[] {
  if (id === "schedule.config.write" && args.length === 0) return ["--body", "-"];
  if (id !== "schedule.new") return args;
  const separator = args.indexOf("--");
  if (separator < 0) return args;
  const command = args.slice(separator + 1);
  if (!command.length) throw new Error("schedule new: missing command after --");
  const flags = args.slice(0, separator);
  const rest: string[] = [];
  let every: string | undefined;
  let resource: Record<string, number> | undefined;
  for (let index = 0; index < flags.length;) {
    if (!flags[index]!.startsWith("--")) { rest.push(flags[index++]!); continue; }
    const [name, value, consumed] = takeOption(flags, index);
    if (name === "every") {
      if (every !== undefined) throw new Error("Duplicate --every");
      every = value;
    } else if (name === "resource") {
      if (resource) throw new Error("Duplicate --resource");
      resource = Object.create(null) as Record<string, number>;
      if (value) for (const pair of value.split(",")) {
        const parts = pair.split("=");
        const [key, raw] = parts;
        if (parts.length !== 2 || !key || !raw?.trim() || !Number.isFinite(Number(raw)) || Number(raw) < 0 || Object.hasOwn(resource, key)) {
          throw new Error(`Invalid resource pair: ${pair}`);
        }
        resource[key] = Number(raw);
      }
    } else if (name === "body" || name === "input") {
      throw new Error("Use either --body/--input or --every … -- command");
    } else rest.push(...flags.slice(index, index + consumed));
    index += consumed;
  }
  if (!every) throw new Error("schedule new: missing --every");
  return [...rest, "--body", JSON.stringify({ every, resource: resource ?? {}, command, enabled: true })];
}

export async function editSchedule(client: ApiClient, env: Record<string, string | undefined>): Promise<string> {
  const before = (await client.call("schedule.config.read")).body;
  const directory = await mkdtemp(join(tmpdir(), "sprout-schedule-edit-"));
  try {
    const file = join(directory, "schedule.json");
    await Bun.write(file, JSON.stringify(before, null, 2) + "\n");
    const editor = env.VISUAL || env.EDITOR || "vi";
    const child = Bun.spawn(["/bin/sh", "-c", `exec ${editor} "$1"`, "sprout-config-edit", file], {
      stdin: "inherit", stdout: "inherit", stderr: "inherit",
    });
    if (await child.exited !== 0) throw new Error("Schedule editor exited unsuccessfully; configuration was not written");
    const updated = await Bun.file(file).json();
    if (JSON.stringify(updated) === JSON.stringify(before)) return "No changes";
    await client.call("schedule.config.write", { body: updated });
    return "Wrote scheduler configuration";
  } finally { await rm(directory, { recursive: true, force: true }); }
}
