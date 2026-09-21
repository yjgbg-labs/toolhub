import type { Catalog, Operation } from "../api";

export class CommandNode {
  readonly children = new Map<string, CommandNode>();
  operation?: Operation;
  description?: string;
}

export function commandTree(catalog: Catalog): CommandNode {
  const root = new CommandNode();
  for (const operation of catalog.operations) {
    let node = root;
    const path: string[] = [];
    for (const part of operation.segments) {
      path.push(part);
      if (!node.children.has(part)) node.children.set(part, new CommandNode());
      node = node.children.get(part)!;
      node.description = catalog.groupDescriptions.get(path.join("."));
    }
    node.operation = operation;
  }
  return root;
}

export function help(node: CommandNode, path: string[]): string {
  const positional = node.operation?.parameters.filter(param => param.in === "path").map(param => `<${param.name}>`) ?? [];
  const lines = [`Usage: sprout ${[...path, ...(node.children.size ? ["<command>"] : positional)].join(" ")} [options]`];
  if (node.description) lines.push("", node.description);
  if (node.operation) {
    const op = node.operation;
    lines.push("", op.description, `${op.method} ${op.path}`, "", "Parameters (location-qualified flags are always available):");
    for (const param of op.parameters) {
      const ambiguous = op.parameters.filter(other => other.name === param.name).length > 1 || ["body", "input", "help"].includes(param.name);
      const name = ambiguous ? `${param.in}.${param.name}` : param.name;
      const type = Array.isArray(param.schema.type) ? param.schema.type.join("|") : param.schema.type;
      const fallback = Object.hasOwn(param.schema, "default") ? `; default ${JSON.stringify(param.schema.default)}` : "";
      lines.push(`  --${name} <${type}>  ${param.in}; ${param.required ? "required" : "optional"}${fallback}${param.description ? `; ${param.description}` : ""}`);
    }
    if (op.body) lines.push(`  --body <json|@file|->  JSON body; ${op.body.required ? "required" : "optional"}`);
    lines.push("  --input <json|@file|->  Complete {path, query, header, cookie, body} input");
  }
  if (node.children.size) {
    lines.push("", "Commands:");
    for (const [name, child] of node.children) lines.push(`  ${name}  ${(child.description ?? child.operation?.description)?.split("\n")[0] ?? ""}`.trimEnd());
  }
  lines.push("", "Environment: SPROUT_BASE_URL (default: local Unix socket)", "  --help, -h  Show help");
  return lines.join("\n");
}
