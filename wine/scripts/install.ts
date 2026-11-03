#!/usr/bin/env bun
import {mkdir, readdir, rename, symlink, copyFile} from "node:fs/promises";
import {join} from "node:path";
import {createHash} from "node:crypto";

const root = process.env.WINE_RUNTIME_ROOT!;
if (!root?.startsWith("/")) throw Error("WINE_RUNTIME_ROOT must be absolute");
const cache = join(root, "packages");
await mkdir(cache, {recursive: true});
async function run(args: string[], cwd = root) {
  const child = Bun.spawn(args, {cwd, stdout: "inherit", stderr: "inherit"});
  if (await child.exited !== 0) throw Error(`Failed: ${args.join(" ")}`);
}
// Resolve against the host's apt metadata, download only; no root or system package changes.
if (!await Bun.file(join(root, "apt-ready")).exists()) {
  const wanted = ["wine64", "wine", "xvfb"];
  const simulation = Bun.spawnSync(["apt-get", "-s", "install", "--no-install-recommends", ...wanted], {env: {...process.env, LC_ALL: "C"}});
  if (simulation.exitCode) throw Error(simulation.stderr.toString());
  const packages = [...new Set([...wanted, "libwine", "wine-common", "libz-mingw-w64", "xserver-common",
    ...[...simulation.stdout.toString().matchAll(/^Inst (\S+)/gm)].map(m => m[1])])];
  await run(["apt-get", "download", ...packages], cache);
  for (const file of await readdir(cache)) if (file.endsWith(".deb")) await run(["dpkg-deb", "-x", join(cache, file), root]);
  await Bun.write(join(root, "apt-ready"), packages.join("\n"));
}
const sdkVersion = process.env.WINE_DOTNET_SDK!;
// Debian's configured datadir already ends in wine; the relocatable lookup appends wine again.
await symlink(".", join(root, "usr/share/wine/wine")).catch(error => { if (error.code !== "EEXIST") throw error; });
for (const [arch, toolchain] of [["x86_64", "x86_64"], ["i386", "i686"]]) {
  await copyFile(join(root, `usr/${toolchain}-w64-mingw32/lib/zlib1.dll`), join(root, `usr/lib/x86_64-linux-gnu/wine/${arch}-windows/zlib1.dll`));
}
const runtimeVersion = process.env.WINE_DOTNET_RUNTIME!;
const manifest = await (await fetch("https://builds.dotnet.microsoft.com/dotnet/release-metadata/10.0/releases.json")).json() as any;
async function install(directory: string, entry: {url: string, hash: string}) {
  if (await Bun.file(join(root, directory, ".version")).text().catch(() => "") === entry.url) return;
  const archive = join(cache, entry.url.split("/").at(-1)!);
  if (!await Bun.file(archive).exists()) {
    await run(["curl", "-fsSL", "--retry", "3", "-o", archive + ".tmp", entry.url]);
    await rename(archive + ".tmp", archive);
  }
  const hash = createHash("sha512");
  for await (const chunk of Bun.file(archive).stream()) hash.update(chunk);
  if (hash.digest("hex") !== entry.hash.toLowerCase()) throw Error(`SHA-512 mismatch: ${archive}`);
  const target = join(root, directory);
  await mkdir(target, {recursive: true});
  await run(archive.endsWith(".zip") ? ["unzip", "-qo", archive, "-d", target] : ["tar", "-xzf", archive, "-C", target]);
  await Bun.write(join(target, ".version"), entry.url);
}
const sdk = manifest.releases.flatMap((r: any) => r.sdks ?? [r.sdk]).find((s: any) => s.version === sdkVersion);
const runtime = manifest.releases.find((r: any) => r.runtime?.version === runtimeVersion)?.runtime;
if (!sdk || !runtime) throw Error("Pinned .NET release not found in official manifest");
await Promise.all([
  install("sdk", sdk.files.find((f: any) => f.name === "dotnet-sdk-linux-x64.tar.gz")),
  install("dotnet-x64", runtime.files.find((f: any) => f.name === "dotnet-runtime-win-x64.zip")),
  install("dotnet-x86", runtime.files.find((f: any) => f.name === "dotnet-runtime-win-x86.zip")),
]);
// The pinned MSI digest is published in Wine 10.0's dlls/appwiz.cpl/addons.c.
const mono = join(root, "wine-mono-9.4.0-x86.msi");
if (!await Bun.file(mono).exists()) {
  await run(["curl", "-fsSL", "--retry", "3", "-o", mono + ".tmp", "https://dl.winehq.org/wine/wine-mono/9.4.0/wine-mono-9.4.0-x86.msi"]);
  await rename(mono + ".tmp", mono);
}
const monoHash = createHash("sha256");
for await (const chunk of Bun.file(mono).stream()) monoHash.update(chunk);
if (monoHash.digest("hex") !== "cf6173ae94b79e9de13d9a74cdb2560a886fc3d271f9489acb1cfdbd961cacb2") throw Error("Wine Mono MSI checksum mismatch");
console.log(`Wine and .NET installed in ${root}`);
