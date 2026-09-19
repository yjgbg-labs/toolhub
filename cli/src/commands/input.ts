import type { Operation, OperationInput, Parameter, Schema } from "../api";

const reserved = new Set(["body", "input", "help", "url", "schema", "timeout"]);
export async function jsonArgument(raw: string): Promise<unknown> {
  const text = raw === "-" ? await Bun.stdin.text() : raw.startsWith("@") ? await Bun.file(raw.slice(1)).text() : raw;
  return JSON.parse(text);
}

function scalar(raw: string, schema: Schema): unknown {
  const type = Array.isArray(schema.type) ? schema.type.find(type => type !== "null") : schema.type;
  if (type === "string") return raw;
  if (type === "boolean") {
    if (raw === "true") return true;
    if (raw === "false") return false;
    throw new Error(`Expected true or false, got ${raw}`);
  }
  if (type === "integer" || type === "number") {
    if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(raw)) throw new Error(`Expected ${type}, got ${raw}`);
    const value = Number(raw);
    if (!Number.isFinite(value) || (type === "integer" && !Number.isSafeInteger(value))) throw new Error(`Number outside supported range: ${raw}`);
    return value;
  }
  throw new Error(`Unsupported flag type: ${type}`);
}

export function takeOption(args: string[], index: number): [string, string, number] {
  const arg = args[index]!;
  if (!arg.startsWith("--")) throw new Error(`Expected an option, got ${arg}`);
  const equals = arg.indexOf("=");
  const name = arg.slice(2, equals < 0 ? undefined : equals);
  const value = equals < 0 ? args[index + 1] : arg.slice(equals + 1);
  if (value === undefined || (equals < 0 && value.startsWith("--"))) throw new Error(`Missing value for --${name}`);
  return [name, value, equals < 0 ? 2 : 1];
}

export async function operationInput(operation: Operation, args: string[]): Promise<OperationInput> {
  const flags = new Map<string, Parameter>();
  for (const param of operation.parameters) {
    flags.set(`${param.in}.${param.name}`, param);
    if (!reserved.has(param.name) && operation.parameters.filter(other => other.name === param.name).length === 1) flags.set(param.name, param);
  }
  let input: OperationInput = Object.create(null);
  let hasInput = false;
  const positional = operation.parameters.filter(param => param.in === "path");
  let position = 0;
  for (let i = 0; i < args.length;) {
    if (!args[i]!.startsWith("--")) {
      const param = positional[position++];
      if (!param) throw new Error(`Unexpected argument: ${args[i]}`);
      const group = input.path ??= Object.create(null);
      if (Object.hasOwn(group, param.name)) throw new Error(`Duplicate path parameter ${param.name}`);
      group[param.name] = scalar(args[i++]!, param.schema);
      continue;
    }
    const [name, raw, consumed] = takeOption(args, i);
    i += consumed;
    if (name === "input") {
      if (hasInput || Object.keys(input).length || i !== args.length) throw new Error("--input must be used alone");
      const value = await jsonArgument(raw);
      if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("--input must be a JSON object");
      input = value;
      hasInput = true;
    } else if (name === "body") {
      if (!operation.body) throw new Error(`${operation.id} has no body`);
      if (Object.hasOwn(input, "body")) throw new Error("Duplicate --body");
      input.body = await jsonArgument(raw);
    } else {
      const param = flags.get(name);
      if (!param) throw new Error(`Unknown option --${name} for ${operation.id}`);
      const group = input[param.in] ??= Object.create(null);
      if (param.schema.type === "array") {
        const values = raw.startsWith("[") ? JSON.parse(raw) : [scalar(raw, param.schema.items)];
        if (!Array.isArray(values)) throw new Error(`Expected array for --${name}`);
        group[param.name] = [...(group[param.name] as unknown[] ?? []), ...values];
      } else {
        if (Object.hasOwn(group, param.name)) throw new Error(`Duplicate --${name}`);
        group[param.name] = scalar(raw, param.schema);
      }
    }
  }
  return input;
}
