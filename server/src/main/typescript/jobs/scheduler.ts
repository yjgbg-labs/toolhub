import type { Job } from "./config";

export type JobStatus = "succeeded" | "failed" | "skipped";

export interface JobResult {
  name: string;
  status: JobStatus;
  exitCode?: number;
}

export interface JobEvent {
  name: string;
  type: "start" | "succeeded" | "failed" | "skipped";
  exitCode?: number;
  durationMs?: number;
}

export async function executeJobGraph(
  jobs: Job[],
  target: string,
  maxParallel: number,
  runJob: (job: Job) => Promise<number>,
  onEvent: (event: JobEvent) => void = () => {},
): Promise<JobResult[]> {
  if (!Number.isInteger(maxParallel) || maxParallel <= 0) {
    throw new Error("maxParallel must be a positive integer");
  }

  const allJobs = new Map(jobs.map((job) => [job.name, job]));
  if (!allJobs.has(target)) throw new Error(`no job named "${target}"`);

  const selected = selectDependencies(allJobs, target);
  const dependents = new Map<string, string[]>();
  const remainingDependencies = new Map<string, number>();
  for (const job of selected.values()) {
    remainingDependencies.set(job.name, job.needs.length);
    for (const dependency of job.needs) {
      const names = dependents.get(dependency) ?? [];
      names.push(job.name);
      dependents.set(dependency, names);
    }
  }

  const ready = [...selected.values()].filter((job) => job.needs.length === 0).map((job) => job.name);
  const running = new Map<string, Promise<{ name: string; exitCode: number; durationMs: number }>>();
  const results = new Map<string, JobResult>();

  const skipDescendants = (name: string) => {
    for (const dependent of dependents.get(name) ?? []) {
      if (results.has(dependent) || running.has(dependent)) continue;
      const readyIndex = ready.indexOf(dependent);
      if (readyIndex !== -1) ready.splice(readyIndex, 1);
      results.set(dependent, { name: dependent, status: "skipped" });
      onEvent({ name: dependent, type: "skipped" });
      skipDescendants(dependent);
    }
  };

  while (ready.length > 0 || running.size > 0) {
    while (ready.length > 0 && running.size < maxParallel) {
      const name = ready.shift()!;
      const job = selected.get(name)!;
      const startedAt = Date.now();
      onEvent({ name, type: "start" });
      const promise = Promise.resolve()
        .then(() => runJob(job))
        .catch(() => 1)
        .then((exitCode) => ({ name, exitCode, durationMs: Date.now() - startedAt }));
      running.set(name, promise);
    }

    if (running.size === 0) break;
    const outcome = await Promise.race(running.values());
    running.delete(outcome.name);

    if (outcome.exitCode !== 0) {
      results.set(outcome.name, { name: outcome.name, status: "failed", exitCode: outcome.exitCode });
      onEvent({ name: outcome.name, type: "failed", exitCode: outcome.exitCode, durationMs: outcome.durationMs });
      skipDescendants(outcome.name);
      continue;
    }

    results.set(outcome.name, { name: outcome.name, status: "succeeded", exitCode: 0 });
    onEvent({ name: outcome.name, type: "succeeded", exitCode: 0, durationMs: outcome.durationMs });
    for (const dependent of dependents.get(outcome.name) ?? []) {
      if (results.has(dependent)) continue;
      const remaining = remainingDependencies.get(dependent)! - 1;
      remainingDependencies.set(dependent, remaining);
      if (remaining === 0) ready.push(dependent);
    }
  }

  return [...selected.keys()].map((name) => results.get(name)!).filter(Boolean);
}

function selectDependencies(allJobs: Map<string, Job>, target: string): Map<string, Job> {
  const selected = new Map<string, Job>();
  const visit = (name: string) => {
    if (selected.has(name)) return;
    const job = allJobs.get(name)!;
    for (const dependency of job.needs) visit(dependency);
    selected.set(name, job);
  };
  visit(target);
  return selected;
}
