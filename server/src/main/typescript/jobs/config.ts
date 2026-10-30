import { readFile } from "node:fs/promises";
import { dirname, isAbsolute, resolve } from "node:path";

export interface Job {
  name: string;
  needs: string[];
  run: string;
}

export interface JobsConfig {
  jobs: Job[];
  rootDir: string;
}

interface RawJobsFile {
  version?: unknown;
  imports?: unknown;
  jobs?: unknown;
}

interface JobsImport {
  path: string;
  namespace: string;
}

export async function loadJobsConfig(entryPath: string): Promise<JobsConfig> {
  const absoluteEntryPath = resolve(entryPath);
  const jobs: Job[] = [];
  const jobSources = new Map<string, string>();
  const loaded = new Set<string>();

  await loadJobsFile(absoluteEntryPath, [], [], loaded, jobs, jobSources);
  validateDependencies(jobs);

  return { jobs, rootDir: dirname(absoluteEntryPath) };
}

async function loadJobsFile(
  path: string,
  namespace: string[],
  pathStack: string[],
  loaded: Set<string>,
  jobs: Job[],
  jobSources: Map<string, string>,
): Promise<void> {
  if (pathStack.includes(path)) {
    const cycle = [...pathStack.slice(pathStack.indexOf(path)), path].join(" -> ");
    throw new Error(`circular import: ${cycle}`);
  }
  const instanceKey = `${path}\0${namespace.join("\0")}`;
  if (loaded.has(instanceKey)) return;

  let content: string;
  try {
    content = await readFile(path, "utf8");
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`cannot read ${path}: ${message}`);
  }

  let raw: unknown;
  try {
    raw = Bun.YAML.parse(content);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`invalid YAML in ${path}: ${message}`);
  }

  const file = validateJobsFile(raw, path);
  const nextPathStack = [...pathStack, path];
  for (const imported of file.imports) {
    await loadJobsFile(
      resolve(dirname(path), imported.path),
      [...namespace, imported.namespace],
      nextPathStack,
      loaded,
      jobs,
      jobSources,
    );
  }

  for (const job of file.jobs) {
    const qualifiedJob = {
      name: qualifyName(job.name, namespace),
      needs: job.needs.map((dependency) => qualifyReference(dependency, namespace)),
      run: job.run,
    };
    const previousSource = jobSources.get(qualifiedJob.name);
    if (previousSource) {
      throw new Error(`duplicate job "${qualifiedJob.name}" in ${path}; already defined in ${previousSource}`);
    }
    jobs.push(qualifiedJob);
    jobSources.set(qualifiedJob.name, path);
  }
  loaded.add(instanceKey);
}

function validateJobsFile(value: unknown, path: string): { imports: JobsImport[]; jobs: Job[] } {
  if (!isPlainObject(value)) throw new Error(`${path}: expected an object`);
  const file = value as RawJobsFile;
  if (file.version !== 1) throw new Error(`${path}: version must be 1`);

  const imports = file.imports ?? [];
  if (!Array.isArray(imports)) throw new Error(`${path}: imports must be an array`);
  const validatedImports = imports.map((item, index) => validateImport(item, path, index));

  if (!Array.isArray(file.jobs)) throw new Error(`${path}: jobs must be an array`);
  const jobs = file.jobs.map((job, index) => validateJob(job, path, index));
  return { imports: validatedImports, jobs };
}

function validateImport(value: unknown, path: string, index: number): JobsImport {
  const label = `${path}: imports[${index}]`;
  if (!isPlainObject(value)) throw new Error(`${label} must be an object`);

  const importedPath = value.path;
  if (typeof importedPath !== "string" || importedPath.length === 0) {
    throw new Error(`${label}.path must be a non-empty string`);
  }
  if (isAbsolute(importedPath)) throw new Error(`${label}.path must be relative`);

  const namespace = value.namespace;
  if (typeof namespace !== "string" || !isNamespace(namespace)) {
    throw new Error(`${label}.namespace must contain only letters, numbers, underscores, or hyphens`);
  }

  return { path: importedPath, namespace };
}

function validateJob(value: unknown, path: string, index: number): Job {
  const label = `${path}: jobs[${index}]`;
  if (!isPlainObject(value)) throw new Error(`${label} must be an object`);

  const name = value.name;
  if (typeof name !== "string" || name.trim().length === 0) {
    throw new Error(`${label}.name must be a non-empty string`);
  }
  if (name.includes("@")) throw new Error(`${label}.name cannot contain "@"`);

  const run = value.run;
  if (typeof run !== "string" || run.trim().length === 0) {
    throw new Error(`${label}.run must be a non-empty string`);
  }

  const needs = value.needs ?? [];
  if (!Array.isArray(needs) || !needs.every(isJobReference)) {
    throw new Error(`${label}.needs must contain valid job or job@namespace references`);
  }
  if (new Set(needs).size !== needs.length) throw new Error(`${label}.needs contains duplicates`);
  if (needs.includes(name)) throw new Error(`job "${name}" cannot depend on itself`);

  return { name, needs, run };
}

function qualifyName(name: string, namespace: string[]): string {
  return namespace.length === 0 ? name : `${name}@${namespace.join(".")}`;
}

function qualifyReference(reference: string, namespace: string[]): string {
  const atIndex = reference.indexOf("@");
  if (atIndex === -1) return qualifyName(reference, namespace);

  const name = reference.slice(0, atIndex);
  const childNamespace = reference.slice(atIndex + 1).split(".");
  return qualifyName(name, [...namespace, ...childNamespace]);
}

function isJobReference(value: unknown): value is string {
  if (typeof value !== "string" || value.length === 0) return false;
  const parts = value.split("@");
  if (parts.length > 2 || parts[0].length === 0) return false;
  return parts.length === 1 || parts[1].split(".").every(isNamespace);
}

function isNamespace(value: string): boolean {
  return /^[A-Za-z0-9_-]+$/.test(value);
}

function validateDependencies(jobs: Job[]): void {
  const byName = new Map(jobs.map((job) => [job.name, job]));
  for (const job of jobs) {
    for (const dependency of job.needs) {
      if (!byName.has(dependency)) {
        throw new Error(`job "${job.name}" depends on missing job "${dependency}"`);
      }
    }
  }

  const state = new Map<string, "visiting" | "visited">();
  const stack: string[] = [];
  const visit = (name: string) => {
    if (state.get(name) === "visited") return;
    if (state.get(name) === "visiting") {
      const cycle = [...stack.slice(stack.indexOf(name)), name].join(" -> ");
      throw new Error(`circular job dependency: ${cycle}`);
    }

    state.set(name, "visiting");
    stack.push(name);
    for (const dependency of byName.get(name)!.needs) visit(dependency);
    stack.pop();
    state.set(name, "visited");
  };

  for (const job of jobs) visit(job.name);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
