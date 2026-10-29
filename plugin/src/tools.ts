import type { Info } from "@opencode/plugin/promise/tool";
import type { ApiClient } from "./api";

export function operationTools(client: ApiClient): Info[] {
  const names = new Set<string>();
  return client.catalog.operations.map(operation => {
    const namespace = operation.segments.slice(0, -1).join(".") || undefined;
    const name = operation.segments.at(-1)!;
    // OpenCode's registry flattens namespace dots even though Code Mode preserves them.
    const effective = namespace === undefined ? name : `${namespace.replaceAll(".", "_")}_${name}`;
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
