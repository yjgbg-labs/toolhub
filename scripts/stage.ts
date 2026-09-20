#!/usr/bin/env bun
import { cp, chmod, mkdir } from "node:fs/promises";
import { join } from "node:path";

const stage = process.argv[2];
if (!stage) throw new Error("Expected a staging directory");
await mkdir(join(stage, "server"), { recursive: true });
await cp("server/dist/maven", join(stage, "server/maven"), { recursive: true });
await cp("server/dist/groot.jar", join(stage, "server/groot.jar"));
for (const name of ["cli", "plugin"]) {
  await mkdir(join(stage, name), { recursive: true });
  await cp(`${name}/dist`, join(stage, name, "dist"), { recursive: true });
  const manifest = await Bun.file(`${name}/package.json`).json();
  const exports = name === "cli" ? { "./api": "./dist/api.js" } : { ".": "./dist/index.js" };
  await Bun.write(join(stage, name, "package.json"), JSON.stringify({ ...manifest, exports, dependencies: {} }, null, 2) + "\n");
}
await chmod(join(stage, "cli/dist/sprout.js"), 0o755);
