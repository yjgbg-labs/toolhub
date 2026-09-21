#!/usr/bin/env bun
import { ApiClient, defaultBaseUrl } from "./api";
import { commandTree, help, usage } from "./commands/tree";
import { operationInput, takeOption } from "./commands/input";

export async function run(args: string[], env: Record<string, string | undefined> = process.env): Promise<string> {
  const rest: string[] = [];
  let showHelp = false;
  for (let i = 0; i < args.length;) {
    const arg = args[i]!;
    if (arg === "--help" || arg === "-h") { showHelp = true; i++; continue; }
    if (arg.startsWith("--")) {
      const [, , consumed] = takeOption(args, i);
      rest.push(...args.slice(i, i + consumed));
      i += consumed;
    } else { rest.push(arg); i++; }
  }
  const client = await ApiClient.connect({ baseUrl: env.SPROUT_BASE_URL || defaultBaseUrl(env) });
  let node = commandTree(client.catalog);
  const path: string[] = [];
  let offset = 0;
  while (offset < rest.length && !rest[offset]!.startsWith("--")) {
    if (node.operation && !node.children.has(rest[offset]!)) break;
    const segment = rest[offset++]!;
    const next = node.children.get(segment);
    if (!next) throw new Error(`Unknown command: ${[...path, segment].join(" ")}\n${help(node, path)}`);
    node = next;
    path.push(segment);
  }
  if (showHelp || !node.operation) {
    if (rest.length > offset && !showHelp) throw new Error("Choose an operation before passing parameters");
    return help(node, path);
  }
  const input = await operationInput(node.operation, rest.slice(offset)).catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`${message}\n${usage(node, path)}\nRun 'sprout ${path.join(" ")} --help' for details.`);
  });
  const result = await client.call(node.operation.id, input);
  return typeof result.body === "string" ? result.body : result.body === null ? "" : JSON.stringify(result.body, null, 2);
}

if (import.meta.main) {
  try {
    const output = await run(process.argv.slice(2));
    if (output) console.log(output);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
