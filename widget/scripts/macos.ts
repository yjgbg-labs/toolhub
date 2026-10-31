#!/usr/bin/env bun
import { createHash } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";

const action = process.argv[2];
if (!["test", "build", "deploy"].includes(action)) throw Error("Expected test, build or deploy");
const host = process.env.WIDGET_MAC_HOST!;
const baseURL = process.env.WIDGET_BASE_URL!;
if (!host || host.startsWith("-") || !/^[\w.@:-]+$/.test(host)) throw Error("Invalid WIDGET_MAC_HOST");
const url = new URL(baseURL);
if (!["http:", "https:"].includes(url.protocol) || url.search || url.hash) throw Error("Invalid WIDGET_BASE_URL");
const quote = (text: string) => `'${text.replaceAll("'", "'\\''")}'`;
async function run(args: string[], capture = false): Promise<string> {
  const child = Bun.spawn(args, { stdout: capture ? "pipe" : "inherit", stderr: "inherit" });
  const output = capture ? await new Response(child.stdout as ReadableStream).text() : "";
  if (await child.exited !== 0) throw Error(`Failed: ${args[0]} ${args[1] ?? ""}`);
  return output.trim();
}
const ssh = (script: string, capture = false) => run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host, script], capture);
await mkdir("widget/dist", { recursive: true });
const archive = resolve("widget/dist/source.tar.gz");
await run(["tar", "--exclude=.build", "--exclude=.DS_Store", "-czf", archive, "-C", "widget", "macos", "scripts"]);
const hash = createHash("sha256").update(new Uint8Array(await Bun.file(archive).arrayBuffer())).digest("hex");
const home = await ssh('printf "%s" "$HOME"', true);
const stage = `${home}/Library/Caches/groot-widget/${hash}`;
await ssh(`mkdir -p ${quote(stage)}`);
await run(["scp", "-q", archive, `${host}:${stage}/source.tar.gz`]);
await ssh(`cd ${quote(stage)} && printf '%s  source.tar.gz\n' ${quote(hash)} | shasum -a 256 -c - && tar -xzf source.tar.gz`);
if (action === "test") {
  await ssh(`cd ${quote(stage + "/macos")} && xcrun swift test`);
} else if (action === "build") {
  await ssh(`cd ${quote(stage)} && /bin/sh scripts/build-macos.sh`);
  await run(["scp", "-q", `${host}:${stage}/dist/GrootWidget.tar.gz`, "widget/dist/GrootWidget-macos.tar.gz"]);
  const expected = await ssh(`shasum -a 256 ${quote(stage + "/dist/GrootWidget.tar.gz")}`, true);
  const actual = createHash("sha256").update(new Uint8Array(await Bun.file("widget/dist/GrootWidget-macos.tar.gz").arrayBuffer())).digest("hex");
  if (expected.split(/\s/)[0] !== actual) throw Error("App archive checksum mismatch");
  console.log(`macOS app sha256=${actual}`);
} else {
  await ssh(`cd ${quote(stage)} && xcrun swift scripts/install-macos.swift ${quote(baseURL)} ${process.env.WIDGET_DIAGNOSTICS === "1" ? "1" : "0"}`);
}
