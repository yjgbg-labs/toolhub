#!/usr/bin/env bun
import {mkdir, readdir, lstat, cp, mkdtemp, rename, symlink, rm} from "node:fs/promises";
import {join, dirname, resolve} from "node:path";
import {createHash} from "node:crypto";

const destination = process.env.WINE_HELPER_ROOT!;
if (!destination?.startsWith("/")) throw Error("WINE_HELPER_ROOT must be absolute");
const current = await lstat(destination).catch(error => { if (error.code !== "ENOENT") throw error; return null; });
if (current && !current.isSymbolicLink()) throw Error("Helper destination must be absent or a managed release symlink");
const source = resolve("wine/dist/helper");
async function files(directory: string): Promise<string[]> {
  const entries = await readdir(join(source,directory),{withFileTypes:true});
  return (await Promise.all(entries.map(e => e.isDirectory() ? files(join(directory,e.name)) : Promise.resolve([join(directory,e.name)])))).flat();
}
const hash = createHash("sha256");
for (const path of (await files("")).sort()) { hash.update(path + "\0"); hash.update(new Uint8Array(await Bun.file(join(source,path)).arrayBuffer())); }
const releases = join(dirname(destination), "helper-releases");
await mkdir(releases, {recursive:true});
const release = join(releases,hash.digest("hex"));
if (!await Bun.file(join(release,".complete")).exists()) {
  const stage = await mkdtemp(join(releases,".stage-"));
  try { await cp(source,stage,{recursive:true}); await Bun.write(join(stage,".complete"),"sprout-wine\n"); await rename(stage,release); }
  finally { await rm(stage,{recursive:true,force:true}); }
}
const link = destination + ".next-" + process.pid;
await symlink(release,link);
try { await rename(link,destination); } finally { await rm(link,{force:true}); }
console.log(`Installed Wine helpers: ${release}`);
