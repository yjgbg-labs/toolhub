import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import type { ValidateFunction } from "ajv";
import { Catalog, type Location } from "./catalog";

export type Fetch = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;
export interface ClientOptions {
  baseUrl: string;
  schemaUrl?: string;
  headers?: Record<string, string>;
  timeoutMs?: number;
  fetch?: Fetch;
}
export type OperationInput = Partial<Record<Location, Record<string, unknown>>> & { body?: unknown };
export interface ApiResult { status: number; headers: Record<string, string>; body: any }

function connectionSettings(options: ClientOptions) {
  const url = new URL(options.baseUrl);
  if (!["http:", "https:"].includes(url.protocol) || url.search || url.hash || url.username || url.password) {
    throw new Error("baseUrl must be an HTTP(S) URL without credentials, query or fragment");
  }
  const timeoutMs = options.timeoutMs ?? 30_000;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) throw new Error("timeoutMs must be a positive integer");
  return { baseUrl: url.href.replace(/\/$/, ""), timeoutMs };
}

export class ApiError extends Error {
  constructor(readonly operationId: string, readonly response: ApiResult) {
    super(`${operationId}: HTTP ${response.status}\n${typeof response.body === "string" ? response.body : JSON.stringify(response.body)}`);
    this.name = "ApiError";
  }
}

export class ApiClient {
  private readonly validate = new Map<string, ValidateFunction>();
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private readonly fetch: Fetch;
  constructor(readonly catalog: Catalog, private readonly options: ClientOptions) {
    const settings = connectionSettings(options);
    this.baseUrl = settings.baseUrl;
    this.timeoutMs = settings.timeoutMs;
    this.fetch = options.fetch ?? globalThis.fetch;
    const ajv = new Ajv2020({ strict: false, allErrors: true, validateFormats: true });
    addFormats(ajv);
    ajv.addFormat("int32", { type: "number", validate: value => Number.isInteger(value) && value >= -2147483648 && value <= 2147483647 });
    ajv.addFormat("int64", { type: "number", validate: Number.isSafeInteger });
    ajv.addFormat("float", { type: "number", validate: Number.isFinite });
    ajv.addFormat("double", { type: "number", validate: Number.isFinite });
    for (const op of catalog.operations) this.validate.set(op.id, ajv.compile(op.inputSchema));
  }

  static async connect(options: ClientOptions): Promise<ApiClient> {
    const { baseUrl, timeoutMs } = connectionSettings(options);
    const schemaUrl = options.schemaUrl ?? `${baseUrl}/openapi.json`;
    const response = await (options.fetch ?? globalThis.fetch)(schemaUrl, {
      headers: { ...options.headers, Accept: "application/json" },
      signal: AbortSignal.timeout(timeoutMs), redirect: "error",
    });
    if (!response.ok) throw new Error(`OpenAPI fetch failed: HTTP ${response.status} (${schemaUrl})`);
    return new ApiClient(new Catalog(await response.json()), options);
  }

  async call(id: string, input: unknown = {}, signal?: AbortSignal): Promise<ApiResult> {
    const operation = this.catalog.byId.get(id);
    if (!operation) throw new Error(`Unknown operation: ${id}`);
    const validate = this.validate.get(id)!;
    if (!validate(input)) {
      throw new Error(`Invalid input for ${id}: ${validate.errors?.map(error => `${error.instancePath || "/"} ${error.message}`).join("; ")}`);
    }
    const args = input as OperationInput;
    const headers = new Headers(this.options.headers);
    headers.set("Accept", "application/json, text/plain, */*");
    const path = operation.path.replace(/\{([^{}]+)\}/g, (_, name: string) => {
      const value = String(args.path![name]);
      if (!value || value === "." || value === "..") throw new Error(`Invalid path segment: ${name}`);
      return encodeURIComponent(value);
    });
    const url = new URL(this.baseUrl + path);
    const cookies: string[] = [];
    for (const parameter of operation.parameters) {
      if (parameter.in === "path") continue;
      const value = args[parameter.in]?.[parameter.name];
      if (value === undefined) continue;
      // Optional parameters are omitted rather than encoding a literal "null".
      if (value === null) continue;
      switch (parameter.in) {
        case "query":
          if (Array.isArray(value)) {
            if (parameter.explode) for (const entry of value) url.searchParams.append(parameter.name, String(entry));
            else url.searchParams.append(parameter.name, value.map(String).join(","));
          } else url.searchParams.append(parameter.name, String(value));
          break;
        case "header": headers.set(parameter.name, String(value)); break;
        case "cookie": {
          if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(parameter.name) || !/^[\x21\x23-\x2B\x2D-\x3A\x3C-\x5B\x5D-\x7E]*$/.test(String(value))) {
            throw new Error(`Invalid cookie value: ${parameter.name}`);
          }
          cookies.push(`${parameter.name}=${value}`);
        }
      }
    }
    if (cookies.length) headers.set("Cookie", [headers.get("Cookie"), ...cookies].filter(Boolean).join("; "));
    let body: string | undefined;
    if (Object.hasOwn(args, "body")) {
      body = JSON.stringify(args.body);
      headers.set("Content-Type", "application/json");
    }
    const timeout = AbortSignal.timeout(this.timeoutMs);
    const response = await this.fetch(url, {
      method: operation.method, headers, body, redirect: "error",
      signal: signal ? AbortSignal.any([timeout, signal]) : timeout,
    });
    const text = await response.text();
    const media = response.headers.get("Content-Type")?.split(";")[0]?.trim();
    const result: ApiResult = {
      status: response.status, headers: Object.fromEntries(response.headers),
      body: !text ? null : media === "application/json" || media?.endsWith("+json") ? JSON.parse(text) : text,
    };
    if (!response.ok) throw new ApiError(id, result);
    return result;
  }
}
