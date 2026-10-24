#!/usr/bin/env bun
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { generate, generatedHeader } from "./generate";

export async function main(args: string[]): Promise<void> {
  const options = new Map<string, string>();
  for (let i = 0; i < args.length; i += 2) {
    if (!["--schema", "--package", "--output", "--unix-socket"].includes(args[i]!) || !args[i + 1] || options.has(args[i]!)) throw new Error("Usage: bun scala-sdk/generator/main.ts --schema <file|URL> --package <name> --output <directory> [--unix-socket <path>]");
    options.set(args[i]!, args[i + 1]!);
  }
  for (const required of ["--schema", "--package", "--output"]) if (!options.has(required)) throw new Error(`Missing ${required}`);
  const source = options.get("--schema")!;
  const text = /^https?:\/\//.test(source) ? await (async () => {
    const response = await fetch(source, { unix: options.get("--unix-socket"), redirect: "error", signal: AbortSignal.timeout(30_000) });
    if (!response.ok) throw new Error(`OpenAPI fetch: HTTP ${response.status}`);
    return response.text();
  })() : await readFile(source, "utf8");
  const files = generate(JSON.parse(text), options.get("--package")!);
  const output = options.get("--output")!;
  // Validate ownership of every destination before touching any generated file.
  for (const name of Object.keys(files)) {
    const previous = await readFile(join(output, name), "utf8").catch(error => { if (error.code === "ENOENT") return undefined; throw error; });
    if (previous !== undefined && !previous.startsWith(generatedHeader)) throw new Error(`Refusing to overwrite handwritten file: ${join(output, name)}`);
  }
  await mkdir(output, { recursive: true });
  for (const [name, contents] of Object.entries(files)) {
    const file = join(output, name);
    const temporary = `${file}.${process.pid}.tmp`;
    await writeFile(temporary, contents);
    await rename(temporary, file);
  }
  console.log(`Generated ${Object.keys(files).length} Scala files in ${output}`);
}

if (import.meta.main) {
  try { await main(process.argv.slice(2)); }
  catch (error) { console.error(error instanceof Error ? error.message : String(error)); process.exitCode = 1; }
}
