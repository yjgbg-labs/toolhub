export type Schema = Record<string, any>;
export type Location = "path" | "query" | "header" | "cookie";
export const locations: Location[] = ["path", "query", "header", "cookie"];
const methods = new Set(["get", "post", "put", "patch", "delete", "head", "options"]);
export const operationPattern = /^[A-Za-z][A-Za-z0-9_-]*(\.[A-Za-z][A-Za-z0-9_-]*)*$/;

export interface Parameter {
  name: string;
  in: Location;
  required: boolean;
  schema: Schema;
  description?: string;
  explode: boolean;
}

export interface Operation {
  id: string;
  segments: string[];
  method: string;
  path: string;
  description: string;
  parameters: Parameter[];
  body?: { required: boolean; schema: Schema; description?: string };
  inputSchema: Schema;
  outputSchema: Schema;
}

function object(value: unknown, label: string): Schema {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`Expected ${label} object`);
  return value;
}

function pointer(document: Schema, ref: string): Schema {
  if (!ref.startsWith("#/")) throw new Error(`Only local OpenAPI references are supported: ${ref}`);
  let value: unknown = document;
  for (const part of ref.slice(2).split("/")) {
    const key = decodeURIComponent(part).replace(/~1/g, "/").replace(/~0/g, "~");
    if (!value || typeof value !== "object" || !Object.hasOwn(value, key)) throw new Error(`Unresolved reference: ${ref}`);
    value = (value as Schema)[key];
  }
  return object(value, ref);
}

function resolve(document: Schema, value: unknown, seen = new Set<string>()): Schema {
  const entry = object(value, "OpenAPI entry");
  if (!entry.$ref) return entry;
  if (seen.has(entry.$ref)) throw new Error(`Cyclic OpenAPI entry: ${entry.$ref}`);
  seen.add(entry.$ref);
  const { $ref, ...siblings } = entry;
  return { ...resolve(document, pointer(document, $ref), seen), ...siblings };
}

// Keep recursive schemas as references, collecting only the definitions reachable from this tool.
function standalone(document: Schema, schema: Schema): Schema {
  const definitions: Schema = Object.create(null);
  function rewrite(value: any): any {
    if (Array.isArray(value)) return value.map(rewrite);
    if (!value || typeof value !== "object") return value;
    return Object.fromEntries(Object.entries(value).map(([key, item]) => {
      if (key !== "$ref") return [key, rewrite(item)];
      if (typeof item !== "string" || !item.startsWith("#/components/schemas/")) {
        throw new Error(`Unsupported schema reference: ${item}`);
      }
      const name = item.slice("#/components/schemas/".length);
      if (!Object.hasOwn(definitions, name)) {
        definitions[name] = {};
        definitions[name] = rewrite(pointer(document, item));
      }
      const escaped = name.replace(/~/g, "~0").replace(/\//g, "~1");
      return [key, `#/$defs/${escaped}`];
    }));
  }
  const result = rewrite(schema);
  return Object.keys(definitions).length ? { ...result, $defs: definitions } : result;
}

function objectSchema(properties: Schema, required: string[]): Schema {
  return { type: "object", properties, required, additionalProperties: false };
}

export class Catalog {
  readonly operations: Operation[] = [];
  readonly byId = new Map<string, Operation>();
  readonly groupDescriptions = new Map<string, string>();
  readonly title: string;

  constructor(value: unknown) {
    const doc = object(value, "OpenAPI document");
    if (!/^3\.1\./.test(doc.openapi)) throw new Error("Expected an OpenAPI 3.1 document");
    this.title = doc.info?.title ?? "Sprout";
    const tags = doc.tags ?? [];
    if (!Array.isArray(tags)) throw new Error("Expected OpenAPI tags array");
    const names = new Set<string>();
    for (const raw of tags) {
      const tag = object(raw, "tag");
      if (typeof tag.name !== "string" || !tag.name || (tag.description !== undefined && typeof tag.description !== "string")) {
        throw new Error("Invalid OpenAPI tag");
      }
      if (names.has(tag.name)) throw new Error(`Duplicate OpenAPI tag: ${tag.name}`);
      names.add(tag.name);
      if (tag.description) this.groupDescriptions.set(tag.name, tag.description);
    }
    for (const [path, rawItem] of Object.entries(object(doc.paths, "paths"))) {
      const item = resolve(doc, rawItem);
      if (!path.startsWith("/") || path.includes("?") || path.includes("#")) throw new Error(`Invalid API path: ${path}`);
      for (const [method, raw] of Object.entries(item)) {
        if (!methods.has(method)) continue;
        const op = object(raw, `${method} ${path}`);
        const id = op.operationId;
        if (typeof id !== "string" || !operationPattern.test(id)) throw new Error(`Invalid or missing operationId: ${method} ${path}`);
        if (this.byId.has(id)) throw new Error(`Duplicate operationId: ${id}`);
        const parameters = new Map<string, Parameter>();
        for (const rawParam of [...(item.parameters ?? []), ...(op.parameters ?? [])]) {
          const param = resolve(doc, rawParam);
          if (!locations.includes(param.in) || typeof param.name !== "string" || !param.name) throw new Error(`Invalid parameter in ${id}`);
          const schema = resolve(doc, param.schema);
          const style = param.style ?? (["query", "cookie"].includes(param.in) ? "form" : "simple");
          if (style !== (["query", "cookie"].includes(param.in) ? "form" : "simple")) throw new Error(`Unsupported parameter style ${style}: ${id}`);
          const types = Array.isArray(schema.type) ? schema.type : [schema.type];
          if (types.some((type: string) => !["string", "integer", "number", "boolean", "array", "null"].includes(type))) {
            throw new Error(`Unsupported parameter schema: ${id} ${param.name}`);
          }
          if (types.includes("array") && (param.in !== "query" || !["string", "number", "integer", "boolean"].includes(resolve(doc, schema.items).type))) {
            throw new Error(`Only primitive query arrays are supported: ${id} ${param.name}`);
          }
          parameters.set(`${param.in}:${param.in === "header" ? param.name.toLowerCase() : param.name}`, {
            name: param.name, in: param.in, required: param.in === "path" || param.required === true,
            schema, description: param.description, explode: param.explode ?? style === "form",
          });
        }
        const params = [...parameters.values()];
        const placeholders = [...path.matchAll(/\{([^{}]+)\}/g)].map(match => match[1]!);
        if (placeholders.some(name => !params.some(param => param.in === "path" && param.name === name)) ||
            params.some(param => param.in === "path" && !placeholders.includes(param.name))) {
          throw new Error(`Path parameter mismatch: ${id}`);
        }
        let body: Operation["body"];
        if (op.requestBody) {
          const request = resolve(doc, op.requestBody);
          const json = request.content?.["application/json"];
          if (!json?.schema) throw new Error(`Expected application/json request body: ${id}`);
          if (["get", "head"].includes(method)) throw new Error(`A ${method.toUpperCase()} request cannot have a body: ${id}`);
          body = { schema: json.schema, required: request.required === true, description: request.description };
        }
        const properties: Schema = Object.create(null);
        const required: string[] = [];
        for (const location of locations) {
          const group = params.filter(param => param.in === location);
          if (!group.length) continue;
          const requiredParams = group.filter(param => param.required).map(param => param.name);
          properties[location] = objectSchema(Object.fromEntries(group.map(param => [param.name, {
            ...param.schema, ...(param.description ? { description: param.description } : {}),
          }])), requiredParams);
          if (requiredParams.length) required.push(location);
        }
        if (body) {
          properties.body = { ...body.schema, ...(body.description ? { description: body.description } : {}) };
          if (body.required) required.push("body");
        }
        const responseBodies: Schema[] = [];
        for (const [status, rawResponse] of Object.entries(object(op.responses, "responses"))) {
          if (!/^2\d\d$|^2XX$/.test(status)) continue;
          const response = resolve(doc, rawResponse);
          const content = Object.entries(response.content ?? {});
          if (!content.length || method === "head") responseBodies.push({ type: "null" });
          else for (const [media, rawMedia] of content) {
            const entry = object(rawMedia, "response media");
            responseBodies.push(media === "application/json" || media.endsWith("+json") ? entry.schema ?? {} : { type: "string" });
          }
        }
        const operation: Operation = {
          id, segments: id.split("."), method: method.toUpperCase(), path,
          description: [op.summary, op.description].filter(Boolean).join("\n") || `${method.toUpperCase()} ${path}`,
          parameters: params, body,
          inputSchema: standalone(doc, objectSchema(properties, required)),
          outputSchema: standalone(doc, objectSchema({
            status: { type: "integer" }, headers: { type: "object", additionalProperties: { type: "string" } },
            body: responseBodies.length ? { anyOf: responseBodies } : {},
          }, ["status", "headers", "body"])),
        };
        this.operations.push(operation);
        this.byId.set(id, operation);
      }
    }
    this.operations.sort((a, b) => a.id.localeCompare(b.id));
  }
}
