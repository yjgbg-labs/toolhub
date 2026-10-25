#!/usr/bin/env bun
import { mkdtemp, rm } from "node:fs/promises";

const directory = await mkdtemp("/tmp/opencode/sprout-cli-benchmark-");
const schema = await Bun.file("scala-sdk/openapi.json").text();
const socket = `${directory}/http.sock`;
const server = Bun.serve({ unix: socket, fetch: () => new Response(schema, { headers: { "Content-Type": "application/json" } }) });
const candidates = [
  { name: "Scala Native (debug)", command: ["scala-sdk/dist/sprout-native"], artifact: "scala-sdk/dist/sprout-native" },
  { name: "TypeScript / Bun", command: [process.execPath, "cli/dist/sprout.js"], artifact: "cli/dist/sprout.js" },
];
const samples = candidates.map(() => [] as Array<{ ms: number; rssKiB: number }>);
try {
  for (let round = 0; round < 25; round++) {
    for (const index of round % 2 ? [1, 0] : [0, 1]) {
      const candidate = candidates[index]!;
      const start = performance.now();
      const child = Bun.spawn(["/usr/bin/time", "-f", "%M", ...candidate.command, "--help"], {
        env: { ...process.env, SPROUT_BASE_URL: `unix://${socket}` }, stdout: "ignore", stderr: "pipe",
      });
      const [stderr, code] = await Promise.all([new Response(child.stderr).text(), child.exited]);
      if (code !== 0) throw new Error(`${candidate.name}: ${stderr}`);
      if (round >= 5) samples[index]!.push({ ms: performance.now() - start, rssKiB: Number(stderr.trim()) });
    }
  }
  const median = (values: number[]) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)]!;
  const report = {
    workload: "20 fresh-process --help invocations after 5 warmups; identical live OpenAPI via Unix socket; warm filesystem cache",
    schemaBytes: Buffer.byteLength(schema), bunRuntimeBytes: Bun.file(process.execPath).size,
    results: candidates.map((candidate, index) => ({
      implementation: candidate.name, artifactBytes: Bun.file(candidate.artifact).size,
      medianMs: Number(median(samples[index]!.map(sample => sample.ms)).toFixed(2)),
      medianPeakRssKiB: median(samples[index]!.map(sample => sample.rssKiB)),
    })),
  };
  await Bun.write("scala-sdk/dist/cli-benchmark.json", JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report, null, 2));
} finally {
  await server.stop(true);
  await rm(directory, { recursive: true, force: true });
}
