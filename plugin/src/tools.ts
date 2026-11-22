import type { Info } from "@opencode/plugin/promise/tool";
import type { ApiClient, Operation, Schema } from "./api";

const implicitNames = new Set(["sessionId", "cwd"]);

function effectiveInput(operation: Operation): { schema: Schema; hasImplicit: boolean; unwrappedBody: boolean; needsCwd: boolean } {
  const hasImplicit = operation.parameters.some(p => implicitNames.has(p.name));
  if (!hasImplicit) {
    return { schema: operation.inputSchema, hasImplicit: false, unwrappedBody: false, needsCwd: false };
  }
  const explicitParams = operation.parameters.filter(p => !implicitNames.has(p.name));
  const needsCwd = operation.parameters.some(p => p.name === "cwd" && p.required);
  const defs = operation.inputSchema.$defs ? { $defs: operation.inputSchema.$defs } : {};
  if (operation.body && explicitParams.length === 0) {
    const bodyProp = operation.inputSchema.properties?.body ?? operation.body.schema;
    const { description: _d, ...restBody } = bodyProp;
    return { schema: { ...restBody, ...defs }, hasImplicit: true, unwrappedBody: true, needsCwd };
  }
  const properties = Object.fromEntries(
    Object.entries(operation.inputSchema.properties ?? {}).filter(([k]) => !implicitNames.has(k))
  );
  const required = (operation.inputSchema.required ?? []).filter((k: string) => !implicitNames.has(k));
  return {
    schema: { type: "object", properties, required, additionalProperties: false, ...defs },
    hasImplicit: true,
    unwrappedBody: false,
    needsCwd,
  };
}

export function operationTools(client: ApiClient): Info[] {
  const names = new Set<string>();
  return client.catalog.operations.map(operation => {
    const namespace = operation.segments.slice(0, -1).join(".") || undefined;
    const name = operation.segments.at(-1)!;
    // OpenCode's registry flattens namespace dots even though Code Mode preserves them.
    const effective = namespace === undefined ? name : `${namespace.replaceAll(".", "_")}_${name}`;
    if (names.has(effective)) throw new Error(`Code Mode name collision: ${effective}`);
    names.add(effective);
    const { schema, hasImplicit, unwrappedBody, needsCwd } = effectiveInput(operation);
    return {
      name,
      description: `${operation.id}: ${operation.description}`,
      input: schema,
      output: operation.outputSchema,
      options: { namespace, codemode: true },
      async execute(input, context) {
        let callInput: unknown = input;
        if (hasImplicit) {
          const injected: Record<string, unknown> = {};
          if (operation.parameters.some(p => p.name === "sessionId") && context?.sessionID) {
            injected.sessionId = context.sessionID;
          }
          if (needsCwd) {
            injected.cwd = process.cwd();
          }
          callInput = unwrappedBody
            ? { ...injected, ...(operation.body ? { body: input } : {}) }
            : { ...((input ?? {}) as Record<string, unknown>), ...injected };
        }
        const result = await client.call(operation.id, callInput);
        return { output: result, content: JSON.stringify(result), metadata: { operationId: operation.id, status: result.status } };
      },
    };
  });
}
