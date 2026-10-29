#!/usr/bin/env bun
import { lstat, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import { pathToFileURL } from "node:url";

const marker = "Managed by sprout-http deployment";
export interface PluginConfig { root: string; pluginDir: string; downloadBase: string; sha256: string }
async function exists(path: string) {
  return lstat(path).then(() => true, error => { if (error.code !== "ENOENT") throw error; return false; });
}

export async function install(config: PluginConfig) {
  if (!/^[a-f0-9]{64}$/.test(config.sha256)) throw new Error("Invalid plugin SHA-256");
  if (![config.root, config.pluginDir].every(value => isAbsolute(value) && !/[\r\n\0]/.test(value))) throw new Error("Expected absolute deployment path");
  const target = join(config.pluginDir, "sprout.ts");
  if (await exists(target) && !(await readFile(target, "utf8")).includes(marker)) throw new Error(`Unmanaged plugin: ${target}`);
  const releases = join(config.root, "releases");
  const release = join(releases, config.sha256);
  const entry = join(release, "index.js");
  await mkdir(releases, { recursive: true });
  if (await exists(release)) {
    if (new Bun.CryptoHasher("sha256").update(await Bun.file(entry).bytes()).digest("hex") !== config.sha256) throw new Error("Installed plugin SHA-256 mismatch");
  } else {
    const stage = await mkdtemp(join(releases, ".download-"));
    try {
      const url = `${config.downloadBase.replace(/\/$/, "")}/plugin/${config.sha256}/index.js`;
      const [response, checksum] = await Promise.all([
        fetch(url, { signal: AbortSignal.timeout(120_000) }),
        fetch(`${url}.sha256`, { signal: AbortSignal.timeout(30_000) }),
      ]);
      if (!response.ok || !checksum.ok) throw new Error(`Artifact download failed: ${response.status}/${checksum.status}`);
      const bytes = await response.arrayBuffer();
      if ((await checksum.text()).trim().split(/\s+/)[0] !== config.sha256 || new Bun.CryptoHasher("sha256").update(bytes).digest("hex") !== config.sha256) throw new Error("Downloaded plugin SHA-256 mismatch");
      await Bun.write(join(stage, "index.js"), bytes);
      await rename(stage, release);
    } finally { await rm(stage, { recursive: true, force: true }); }
  }
  await mkdir(config.pluginDir, { recursive: true });
  const temporary = `${target}.${crypto.randomUUID()}.tmp`;
  try {
    await writeFile(temporary, `// ${marker}\nexport { default } from ${JSON.stringify(pathToFileURL(entry).href)};\n`, { mode: 0o644, flag: "wx" });
    await rename(temporary, target);
  } finally { await rm(temporary, { force: true }); }
  console.log(`Deployed plugin: ${target} (${config.sha256})`);
}

if (import.meta.main) await install({
  root: process.env.PLUGIN_INSTALL_ROOT!, pluginDir: process.env.OPENCODE_PLUGIN_DIR!,
  downloadBase: process.env.ARTIFACT_DOWNLOAD_BASE!, sha256: process.env.PLUGIN_SHA256!,
});
