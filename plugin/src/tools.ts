import type { Info } from "@opencode/plugin/promise/tool";
import type { ApiClient } from "@sprout/cli/api";

export function operationTools(client: ApiClient, prefix = "sprout"): Info[] {
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(prefix)) throw new Error("namespace must contain only letters, digits and underscores");
  const names = new Set<string>();
  return client.catalog.operations.map(operation => {
    const namespace = [prefix, ...operation.segments.slice(0, -1)].join("_").replace(/[^A-Za-z0-9_]/g, "_");
    const name = operation.segments.at(-1)!;
    const effective = `${namespace}_${name}`;
    if (names.has(effective)) throw new Error(`Code Mode name collision: ${effective}`);
    names.add(effective);
    return {
      name,
      description: `${operation.id}: ${operation.description}`,
      input: operation.inputSchema,
      output: operation.outputSchema,
      options: { namespace, codemode: true },
      async execute(input) {
        const result = await client.call(operation.id, input);
        return { output: result, content: JSON.stringify(result), metadata: { operationId: operation.id, status: result.status } };
      },
    };
  });
}
