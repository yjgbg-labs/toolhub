import { expect, test } from "bun:test";
import type { Job } from "../../src/main/typescript/jobs/config";
import { executeJobGraph, type JobEvent } from "../../src/main/typescript/jobs/scheduler";

test("a shared dependency runs once, ready jobs respect parallel limits and unrelated jobs stay idle", async () => {
  const leaves = Array.from({ length: 8 }, (_, i) => `task-${i}`);
  const jobs: Job[] = [
    { name: "install", needs: [], run: "install" },
    ...leaves.map(name => ({ name, needs: ["install"], run: name })),
    { name: "build", needs: leaves, run: "build" },
    { name: "unrelated", needs: [], run: "unrelated" },
  ];
  for (const parallel of [1, 2, 5]) {
    const events: JobEvent[] = [];
    let active = 0;
    let peak = 0;
    const results = await executeJobGraph(jobs, "build", parallel, async () => {
      peak = Math.max(peak, ++active);
      await Bun.sleep(5);
      active--;
      return 0;
    }, event => events.push(event));
    expect(peak).toBe(parallel);
    expect(results.map(result => result.name)).toEqual(["install", ...leaves, "build"]);
    expect(results.every(result => result.status === "succeeded")).toBe(true);
    expect(events.filter(event => event.type === "start" && event.name === "install")).toHaveLength(1);
    expect(events.some(event => event.name === "unrelated")).toBe(false);
    for (const leaf of leaves) {
      expect(index(events, "install", "succeeded")).toBeLessThan(index(events, leaf, "start"));
      expect(index(events, leaf, "succeeded")).toBeLessThan(index(events, "build", "start"));
    }
  }
});

test("failures skip all descendants while independent branches finish even with one worker", async () => {
  const jobs: Job[] = [
    { name: "setup", needs: [], run: "setup" },
    { name: "lint", needs: ["setup"], run: "lint" },
    { name: "test", needs: ["setup"], run: "test" },
    { name: "build", needs: ["lint", "test"], run: "build" },
    { name: "deploy", needs: ["build"], run: "deploy" },
  ];
  for (const parallel of [1, 2]) {
    const executed: string[] = [];
    const results = await executeJobGraph(jobs, "deploy", parallel, async job => {
      executed.push(job.name);
      return job.name === "lint" ? 23 : 0;
    });
    expect(executed).toEqual(["setup", "lint", "test"]);
    expect(Object.fromEntries(results.map(result => [result.name, result.status]))).toEqual({
      setup: "succeeded", lint: "failed", test: "succeeded", build: "skipped", deploy: "skipped",
    });
    expect(results.find(result => result.name === "lint")!.exitCode).toBe(23);
  }
});

test("unknown targets, invalid limits and process-spawn failures are reported", async () => {
  const jobs: Job[] = [{ name: "build", needs: [], run: "build" }];
  await expect(executeJobGraph(jobs, "missing", 1, async () => 0)).rejects.toThrow('no job named "missing"');
  await expect(executeJobGraph(jobs, "build", 0, async () => 0)).rejects.toThrow("positive integer");
  expect(await executeJobGraph(jobs, "build", 1, async () => { throw new Error("spawn failed"); }))
    .toEqual([{ name: "build", status: "failed", exitCode: 1 }]);
});

function index(events: JobEvent[], name: string, type: JobEvent["type"]): number {
  return events.findIndex(event => event.name === name && event.type === type);
}
