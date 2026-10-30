import { join } from "node:path";
import { loadJobsConfig, type Job } from "./config";
import { executeJobGraph, type JobEvent } from "./scheduler";

// Bundled into the server JAR and returned as Shell. Runs only on the CLI machine.
// `bun -e <bundle> -- <job> <parallel>` leaves stdin available to the jobs.
try {
  const [target, parallel] = process.argv.slice(1);
  const maxParallel = Number(parallel);
  if (!Number.isSafeInteger(maxParallel) || maxParallel <= 0) {
    throw new Error("--parallel requires a positive integer");
  }
  const config = await loadJobsConfig(join(process.cwd(), "jobs.yaml"));
  const results = await executeJobGraph(config.jobs, target, maxParallel,
    (job) => runShellJob(job, config.rootDir), logJobEvent);
  process.exitCode = results.some((result) => result.status !== "succeeded") ? 1 : 0;
} catch (error) {
  console.error(`groot-cli jobs: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}

async function runShellJob(job: Job, cwd: string): Promise<number> {
  const child = Bun.spawn(["/bin/sh", "-e", "-c", job.run], {
    cwd, stdin: "inherit", stdout: "inherit", stderr: "inherit",
  });
  return await child.exited;
}

function logJobEvent(event: JobEvent): void {
  if (event.type === "start") console.log(`[jobs] start ${event.name}`);
  else if (event.type === "succeeded") console.log(`[jobs] done  ${event.name} (${event.durationMs}ms)`);
  else if (event.type === "failed") console.error(`[jobs] fail  ${event.name} (exit ${event.exitCode}, ${event.durationMs}ms)`);
  else console.error(`[jobs] skip  ${event.name} (dependency failed)`);
}
