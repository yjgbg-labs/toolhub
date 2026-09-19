#!/usr/bin/env bun
import { chmod, lstat, mkdir, mkdtemp, readFile, readlink, rename, rm, symlink, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const marker = "Managed by sprout-http deployment";
const receipt = ".sprout-release.json";
export type Component = "server" | "cli" | "plugin";
export type Run = (args: string[], cwd?: string) => Promise<void>;
export interface DeploymentConfig {
  root: string;
  binDir: string;
  unitDir: string;
  pluginDir: string;
  downloadBase: string;
  artifact: string;
  sha256: string;
  service: string;
  port: number;
  startupTimeout: number;
  baseUrl: string;
  namespace: string;
  jdk: string;
  bun: string;
  groot: string;
  home: string;
}

export function configFromEnv(env = process.env): DeploymentConfig {
  function required(key: string): string {
    const value = env[key];
    if (!value || /[\r\n\0]/.test(value)) throw new Error(`Missing or invalid ${key}`);
    return value;
  }
  function directory(key: string): string {
    const value = required(key);
    if (!isAbsolute(value)) throw new Error(`${key} must be an absolute path`);
    return resolve(value);
  }
  const artifact = required("ARTIFACT_NAME");
  const sha256 = required("ARTIFACT_SHA256");
  const service = required("SERVER_SERVICE");
  const port = Number(required("SERVER_PORT"));
  const startupTimeout = Number(required("SERVER_START_TIMEOUT_MS"));
  const jdk = required("JDK_MAJOR");
  const namespace = required("PLUGIN_NAMESPACE");
  if (!/^[a-zA-Z0-9_-]+\.tar\.gz$/.test(artifact) || !/^[a-f0-9]{64}$/.test(sha256)) throw new Error("Invalid artifact identity");
  if (!/^groot-[a-zA-Z0-9_.-]+\.service$/.test(service)) throw new Error("SERVER_SERVICE must be a groot-*.service unit");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid SERVER_PORT");
  if (!Number.isSafeInteger(startupTimeout) || startupTimeout <= 0) throw new Error("Invalid SERVER_START_TIMEOUT_MS");
  if (!/^\d+$/.test(jdk)) throw new Error("Invalid JDK_MAJOR");
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(namespace)) throw new Error("Invalid PLUGIN_NAMESPACE");
  const downloadBase = required("DEPLOY_DOWNLOAD_BASE").replace(/\/$/, "");
  const baseUrl = required("CLIENT_BASE_URL").replace(/\/$/, "");
  for (const value of [downloadBase, baseUrl]) {
    const url = new URL(value);
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error(`Invalid deployment URL: ${value}`);
  }
  const groot = Bun.which("groot");
  if (!groot) throw new Error("groot is required for deployment");
  return {
    root: directory("DEPLOY_ROOT"), binDir: directory("CLI_BIN_DIR"), unitDir: directory("SYSTEMD_UNIT_DIR"),
    pluginDir: directory("OPENCODE_PLUGIN_DIR"), artifact, sha256, service, port, startupTimeout, jdk,
    downloadBase, baseUrl, namespace, bun: process.execPath, groot, home: directory("SPROUT_HOME"),
  };
}

export const runCommand: Run = async (args, cwd) => {
  const child = Bun.spawn(args, { cwd, stdout: "inherit", stderr: "inherit", stdin: "ignore" });
  const code = await child.exited;
  if (code !== 0) throw new Error(`${args[0]} failed with exit ${code}`);
};

async function stat(path: string) {
  try { return await lstat(path); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined; throw error; }
}

async function assertManaged(path: string) {
  if (await stat(path)) {
    const content = await readFile(path, "utf8");
    if (!content.includes(marker)) throw new Error(`Refusing to replace an unmanaged file: ${path}`);
  }
}

async function installFile(path: string, content: string, mode: number) {
  await assertManaged(path);
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.${crypto.randomUUID()}.tmp`;
  try {
    await writeFile(temporary, content, { mode, flag: "wx" });
    await chmod(temporary, mode);
    await rename(temporary, path);
  } finally { await rm(temporary, { force: true }); }
}

async function prepareRelease(config: DeploymentConfig, run: Run): Promise<string> {
  const releases = join(config.root, "releases");
  const destination = join(releases, config.sha256);
  async function verifyReceipt() {
    const installed = JSON.parse(await readFile(join(destination, receipt), "utf8"));
    if (installed.sha256 !== config.sha256 || installed.artifact !== config.artifact) throw new Error(`Invalid installed release: ${destination}`);
  }
  if (await stat(destination)) { await verifyReceipt(); return destination; }
  await mkdir(releases, { recursive: true });
  const temporary = await mkdtemp(join(releases, ".download-"));
  try {
    const url = `${config.downloadBase}/${config.artifact}`;
    const [archive, checksum] = await Promise.all([
      fetch(url, { signal: AbortSignal.timeout(120_000) }),
      fetch(`${url}.sha256`, { signal: AbortSignal.timeout(30_000) }),
    ]);
    if (!archive.ok || !checksum.ok) throw new Error(`Artifact download failed: ${archive.status}/${checksum.status}`);
    const expected = (await checksum.text()).trim().split(/\s+/)[0];
    if (expected !== config.sha256) throw new Error("Remote SHA-256 does not match artifact.env");
    const bytes = await archive.arrayBuffer();
    const actual = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
    if (actual !== config.sha256) throw new Error("Downloaded artifact SHA-256 mismatch");
    const file = join(temporary, config.artifact);
    const payload = join(temporary, "payload");
    await Bun.write(file, bytes);
    await mkdir(payload);
    await run(["tar", "-xzf", file, "--no-same-owner", "-C", payload]);
    for (const entry of ["server/sprout-example.jar", "cli/dist/sprout.js", "plugin/dist/index.js", "plugin/package.json"]) {
      if (!(await stat(join(payload, entry)))?.isFile()) throw new Error(`Missing artifact file: ${entry}`);
    }
    await writeFile(join(payload, receipt), JSON.stringify({ artifact: config.artifact, sha256: config.sha256 }));
    try { await rename(payload, destination); }
    catch (error) {
      if (!["EEXIST", "ENOTEMPTY"].includes((error as NodeJS.ErrnoException).code ?? "")) throw error;
      await verifyReceipt();
    }
    return destination;
  } finally { await rm(temporary, { recursive: true, force: true }); }
}

async function activate(config: DeploymentConfig, component: Component, release: string) {
  const current = join(config.root, component, "current");
  const existing = await stat(current);
  if (existing && (!existing.isSymbolicLink() || !(await readlink(current)).startsWith(join(config.root, "releases") + "/"))) {
    throw new Error(`Unmanaged release link: ${current}`);
  }
  await mkdir(dirname(current), { recursive: true });
  const temporary = `${current}.${crypto.randomUUID()}`;
  try {
    await symlink(join(release, component), temporary);
    await rename(temporary, current);
  } finally { await rm(temporary, { force: true }); }
  return current;
}

function shellQuote(value: string): string { return `'${value.replaceAll("'", "'\\''")}'`; }
function unitQuote(value: string, exec = false): string {
  const quoted = value.replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("%", "%%");
  return `"${exec ? quoted.replaceAll("$", () => "$$") : quoted}"`;
}

export function serviceUnit(config: DeploymentConfig): string {
  const home = join(config.root, "server");
  const path = [...new Set([dirname(config.bun), dirname(config.groot), "/usr/local/bin", "/usr/bin", "/bin"])].join(":");
  const command = [config.groot, "use-jdk", config.jdk, "--", "java", "-XX:ActiveProcessorCount=4", "-jar", join(home, "current/sprout-example.jar")];
  return `# ${marker}
[Unit]
Description=Sprout HTTP server
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
WorkingDirectory=${home.replaceAll("%", "%%")}
Environment=${unitQuote(`PATH=${path}`)}
Environment=${unitQuote(`SPROUT_PORT=${config.port}`)}
Environment=${unitQuote(`SPROUT_HOME=${config.home}`)}
ExecStart=${command.map(value => unitQuote(value, true)).join(" ")}
Restart=on-failure
RestartSec=3
TimeoutStopSec=45

[Install]
WantedBy=default.target
`;
}

async function waitForServer(config: DeploymentConfig, run: Run) {
  const url = `http://127.0.0.1:${config.port}/health`;
  const deadline = Date.now() + config.startupTimeout;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { method: "POST", signal: AbortSignal.timeout(Math.min(2000, deadline - Date.now())) });
      if (response.ok && await response.text() === "ok") {
        await run(["systemctl", "--user", "is-active", "--quiet", config.service]);
        return;
      }
    } catch {}
    await Bun.sleep(250);
  }
  throw new Error(`Server health check timed out: ${url}; inspect journalctl --user -u ${config.service}`);
}

export async function deploy(component: Component, config: DeploymentConfig, run: Run = runCommand) {
  const target = component === "server" ? join(config.unitDir, config.service)
    : component === "cli" ? join(config.binDir, "sprout") : join(config.pluginDir, "sprout.ts");
  await assertManaged(target);
  const release = await prepareRelease(config, run);
  if (component === "server") {
    await run([config.groot, "use-jdk", config.jdk, "--", "java", "--version"]);
    await mkdir(join(config.root, "server"), { recursive: true });
    const content = serviceUnit(config);
    const temporary = await mkdtemp(join(config.root, ".unit-"));
    try {
      const unit = join(temporary, config.service);
      await writeFile(unit, content, { mode: 0o644 });
      await run(["systemd-analyze", "--user", "verify", unit]);
      await installFile(target, content, 0o644);
    } finally { await rm(temporary, { recursive: true, force: true }); }
    await activate(config, component, release);
    await run(["systemctl", "--user", "daemon-reload"]);
    await run(["systemctl", "--user", "enable", config.service]);
    await run(["systemctl", "--user", "restart", config.service]);
    await waitForServer(config, run);
    await run(["systemctl", "--user", "is-enabled", config.service]);
  } else if (component === "cli") {
    const current = await activate(config, component, release);
    await installFile(target, `#!/bin/sh
# ${marker}
if [ -z "\${SPROUT_URL:-}" ]; then
  export SPROUT_URL=${shellQuote(config.baseUrl)}
fi
exec ${shellQuote(config.bun)} ${shellQuote(join(current, "dist/sprout.js"))} "$@"
`, 0o755);
  } else {
    const directory = join(release, "plugin");
    await run([config.bun, "install", "--ignore-scripts"], directory);
    const entry = pathToFileURL(join(directory, "dist/index.js")).href;
    const defaults = JSON.stringify({ baseUrl: config.baseUrl, namespace: config.namespace });
    await installFile(target, `// ${marker}
import plugin from ${JSON.stringify(entry)};
export default {
  ...plugin,
  setup(ctx) {
    return plugin.setup({ ...ctx, options: { ...${defaults}, ...ctx.options } });
  },
};
`, 0o644);
  }
  console.log(`Deployed ${component}: ${target} (${config.sha256})`);
}

if (import.meta.main) {
  const component = process.argv[2];
  if (!["server", "cli", "plugin"].includes(component ?? "")) throw new Error("Usage: deploy.ts server|cli|plugin");
  await deploy(component as Component, configFromEnv());
}
