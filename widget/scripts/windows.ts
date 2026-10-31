#!/usr/bin/env bun
import { createHash } from "node:crypto";
import { copyFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";

const action = process.argv[2];
if (!["test", "build", "deploy"].includes(action)) throw Error("Expected test, build or deploy");
const baseURL = process.env.WIDGET_BASE_URL!;
const url = new URL(baseURL);
if (!["http:", "https:"].includes(url.protocol) || url.search || url.hash || url.username || url.password) throw Error("Invalid WIDGET_BASE_URL");
const mount = process.env.WIDGET_WINDOWS_MOUNT || "/mnt/windows-c";
const quote = (text: string) => `'${text.replaceAll("'", "''")}'`;
async function run(args: string[], script?: string, capture = false): Promise<string> {
  const child = Bun.spawn(args, { stdin: script ? new Blob([script]) : "ignore", stdout: capture ? "pipe" : "inherit", stderr: "inherit" });
  const output = capture ? await new Response(child.stdout as ReadableStream).text() : "";
  if (await child.exited !== 0) throw Error(`Failed: ${args[0]} ${args[1] ?? ""}`);
  return output.trim();
}
const windows = (script: string, capture = false) => run(["groot", "windows", "exec", "--stdin"], `$ErrorActionPreference = 'Stop'\n${script}`, capture);
const mounted = (path: string) => {
  if (!/^C:\\/i.test(path)) throw Error("Windows build directory must be on the mounted C: drive");
  return resolve(mount, path.slice(3).replaceAll("\\", "/"));
};

await run(["mountpoint", "-q", mount]);
await mkdir("widget/dist", { recursive: true });
const archive = resolve("widget/dist/windows-source.tar.gz");
await run(["tar", "--exclude=bin", "--exclude=obj", "-czf", archive, "-C", "widget", "windows", "windows-tests", "scripts"]);
const hash = createHash("sha256").update(new Uint8Array(await Bun.file(archive).arrayBuffer())).digest("hex");
const root = await windows('Join-Path $env:LOCALAPPDATA "GrootWidget\\build"', true);
const stage = `${root}\\${hash}`;
await windows(`New-Item -ItemType Directory -Force -Path ${quote(stage)} | Out-Null`);
await copyFile(archive, `${mounted(stage)}/source.tar.gz`);
await windows(`Set-Location ${quote(stage)}
if ((Get-FileHash source.tar.gz -Algorithm SHA256).Hash.ToLowerInvariant() -ne '${hash}') { throw 'Source archive checksum mismatch' }
& tar.exe -xzf source.tar.gz
if ($LASTEXITCODE -ne 0) { throw 'Source extraction failed' }`);
const dotnet = '& "$env:ProgramFiles\\dotnet\\dotnet.exe"';
if (action === "test") {
  await windows(`Set-Location ${quote(stage)}\n${dotnet} run --project windows-tests/WidgetTests.csproj -c Release\nif ($LASTEXITCODE -ne 0) { throw 'Windows widget tests failed' }`);
} else if (action === "build") {
  await windows(`Set-Location ${quote(stage)}\n${dotnet} publish windows/GrootWidget.csproj -c Release -o dist/app --nologo\nif ($LASTEXITCODE -ne 0) { throw 'Windows widget build failed' }\nCompress-Archive -Path dist/app/* -DestinationPath dist/GrootWidget-windows.zip -Force`);
  const remote = `${stage}\\dist\\GrootWidget-windows.zip`;
  const expected = await windows(`(Get-FileHash ${quote(remote)} -Algorithm SHA256).Hash.ToLowerInvariant()`, true);
  await copyFile(mounted(remote), "widget/dist/GrootWidget-windows.zip");
  const actual = createHash("sha256").update(new Uint8Array(await Bun.file("widget/dist/GrootWidget-windows.zip").arrayBuffer())).digest("hex");
  if (expected !== actual) throw Error("App archive checksum mismatch");
  console.log(`Windows app sha256=${actual}`);
} else {
  await windows(`Set-Location ${quote(stage)}\n& ./scripts/install-windows.ps1 -SourceDir ./dist/app -BaseURL ${quote(baseURL)} -Diagnostics $${process.env.WIDGET_DIAGNOSTICS === "1" ? "true" : "false"}`);
}
