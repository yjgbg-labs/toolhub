#!/usr/bin/env bun
import { chmod, lstat, mkdir, mkdtemp, readFile, readlink, rename, rm, symlink, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join } from "node:path";

const marker = "Managed by sprout-http deployment";
export interface ServerConfig {
  root: string;
  unitDir: string;
  downloadBase: string;
  sha256: string;
  service: string;
  socket: string;
  startupTimeout: number;
  jdk: string;
  groot: string;
  home: string;
}
export type Run = (args: string[]) => Promise<void>;
export const runCommand: Run = async args => {
  const process = Bun.spawn(args, { stdin: "ignore", stdout: "inherit", stderr: "inherit" });
  if (await process.exited !== 0) throw new Error(`Command failed: ${args.join(" ")}`);
};

async function stat(path: string) {
  return lstat(path).catch(error => { if (error.code !== "ENOENT") throw error; return undefined; });
}
function unitQuote(value: string, exec = false) {
  const quoted = value.replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("%", "%%");
  return `"${exec ? quoted.replaceAll("$", () => "$$") : quoted}"`;
}
export function serviceUnit(config: ServerConfig): string {
  const home = join(config.root, "server");
  const path = [...new Set([dirname(process.execPath), dirname(config.groot), "/usr/local/bin", "/usr/bin", "/bin"])].join(":");
  const command = [config.groot, "use-jdk", config.jdk, "--", "java", "-XX:ActiveProcessorCount=4", "-jar", join(home, "current/groot.jar")];
  return `# ${marker}
[Unit]
Description=Sprout HTTP server
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
WorkingDirectory=${home.replaceAll("%", "%%")}
Environment=${unitQuote(`PATH=${path}`)}
Environment=${unitQuote(`SPROUT_SOCKET=${config.socket}`)}
Environment=${unitQuote(`SPROUT_HOME=${config.home}`)}
UnsetEnvironment=SPROUT_PORT
RuntimeDirectory=sprout-http
RuntimeDirectoryMode=0700
UMask=0077
ExecStart=${command.map(value => unitQuote(value, true)).join(" ")}
Restart=on-failure
RestartSec=3
TimeoutStopSec=45

[Install]
WantedBy=default.target
`;
}

export async function install(config: ServerConfig, run: Run = runCommand) {
  if (!/^[a-f0-9]{64}$/.test(config.sha256) || !/^groot-[a-zA-Z0-9_.-]+\.service$/.test(config.service) || !/^\d+$/.test(config.jdk)) throw new Error("Invalid server release or service identity");
  for (const value of [config.root, config.unitDir, config.socket, config.home, config.groot]) {
    if (!isAbsolute(value) || /[\r\n\0]/.test(value)) throw new Error("Expected absolute deployment path");
  }
  if (!Number.isSafeInteger(config.startupTimeout) || config.startupTimeout <= 0) throw new Error("Invalid startup timeout");
  const target = join(config.unitDir, config.service);
  if (await stat(target) && !(await readFile(target, "utf8")).includes(marker)) throw new Error(`Unmanaged unit: ${target}`);
  const releases = join(config.root, "releases");
  const current = join(config.root, "server/current");
  const existing = await stat(current);
  if (existing && (!existing.isSymbolicLink() || !(await readlink(current)).startsWith(releases + "/"))) throw new Error(`Unmanaged release link: ${current}`);
  await mkdir(releases, { recursive: true });
  const release = join(releases, config.sha256);
  if (await stat(release)) {
    const hash = new Bun.CryptoHasher("sha256").update(await Bun.file(join(release, "server/groot.jar")).bytes()).digest("hex");
    if (hash !== config.sha256) throw new Error("Installed server SHA-256 mismatch");
  } else {
    const temporary = await mkdtemp(join(releases, ".download-"));
    try {
      const url = `${config.downloadBase.replace(/\/$/, "")}/server/${config.sha256}/groot.jar`;
      const [response, checksum] = await Promise.all([
        fetch(url, { signal: AbortSignal.timeout(120_000) }),
        fetch(`${url}.sha256`, { signal: AbortSignal.timeout(30_000) }),
      ]);
      if (!response.ok || !checksum.ok) throw new Error(`Artifact download failed: ${response.status}/${checksum.status}`);
      const bytes = await response.arrayBuffer();
      if ((await checksum.text()).trim().split(/\s+/)[0] !== config.sha256 || new Bun.CryptoHasher("sha256").update(bytes).digest("hex") !== config.sha256) throw new Error("Downloaded server SHA-256 mismatch");
      await mkdir(join(temporary, "server"));
      await Bun.write(join(temporary, "server/groot.jar"), bytes);
      await rename(temporary, release);
    } finally { await rm(temporary, { recursive: true, force: true }); }
  }
  await run([config.groot, "use-jdk", config.jdk, "--", "java", "--version"]);
  await mkdir(dirname(current), { recursive: true });
  await mkdir(config.unitDir, { recursive: true });
  const stage = await mkdtemp(join(config.root, ".unit-"));
  const stagedUnit = `${target}.${crypto.randomUUID()}.tmp`;
  const stagedLink = `${current}.${crypto.randomUUID()}`;
  try {
    const unit = join(stage, config.service);
    const content = serviceUnit(config);
    await writeFile(unit, content, { mode: 0o644 });
    await run(["systemd-analyze", "--user", "verify", unit]);
    await writeFile(stagedUnit, content, { mode: 0o644, flag: "wx" });
    await chmod(stagedUnit, 0o644);
    await rename(stagedUnit, target);
    await symlink(join(release, "server"), stagedLink);
    await rename(stagedLink, current);
  } finally {
    await rm(stage, { recursive: true, force: true });
    await rm(stagedUnit, { force: true });
    await rm(stagedLink, { force: true });
  }
  await run(["systemctl", "--user", "daemon-reload"]);
  await run(["systemctl", "--user", "enable", config.service]);
  await run(["systemctl", "--user", "restart", config.service]);
  const deadline = Date.now() + config.startupTimeout;
  while (true) {
    if (Date.now() >= deadline) throw new Error(`Server health check timed out: ${config.socket}`);
    const healthy = await fetch("http://localhost/health", { unix: config.socket, signal: AbortSignal.timeout(Math.min(2000, deadline - Date.now())) })
      .then(async response => response.ok && await response.text() === "ok").catch(() => false);
    if (healthy) break;
    await Bun.sleep(250);
  }
  await run(["systemctl", "--user", "is-active", "--quiet", config.service]);
  await run(["systemctl", "--user", "is-enabled", config.service]);
  console.log(`Deployed server: ${target} (${config.sha256})`);
}

if (import.meta.main) {
  const groot = Bun.which("groot");
  if (!groot) throw new Error("groot is required for deployment");
  await install({
    root: process.env.SERVER_INSTALL_ROOT!, unitDir: process.env.SYSTEMD_UNIT_DIR!,
    downloadBase: process.env.ARTIFACT_DOWNLOAD_BASE!, sha256: process.env.SERVER_SHA256!,
    service: process.env.SERVER_SERVICE!, socket: process.env.SERVER_SOCKET!,
    startupTimeout: Number(process.env.SERVER_START_TIMEOUT_MS), jdk: process.env.JDK_MAJOR!,
    groot, home: process.env.SPROUT_HOME!,
  });
}
