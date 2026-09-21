import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";
import { pathToFileURL } from "node:url";

export type Fetch = (input: string | URL | Request, init?: RequestInit & { unix?: string }) => Promise<Response>;

export function socketUrl(path: string): string {
  if (!isAbsolute(path) || path.includes("\0")) throw new Error("Socket path must be absolute");
  return pathToFileURL(path).href.replace(/^file:/, "unix:");
}

export function defaultBaseUrl(env: Record<string, string | undefined> = process.env): string {
  const runtime = env.XDG_RUNTIME_DIR || join(env.HOME || homedir(), ".local/run");
  return socketUrl(join(runtime, "sprout-http/http.sock"));
}

export function transport(value: string, request: Fetch = globalThis.fetch): { baseUrl: string; fetch: Fetch } {
  const url = new URL(value);
  if (url.username || url.password || url.search || url.hash) throw new Error("baseUrl must not contain credentials, query or fragment");
  if (url.protocol === "unix:") {
    const unix = decodeURIComponent(url.pathname);
    if (url.host || !isAbsolute(unix) || unix === "/" || unix.includes("\0")) throw new Error("Expected unix:///absolute/socket/path");
    return { baseUrl: "http://localhost", fetch: (input, init) => request(input, { ...init, unix }) };
  }
  if (!["http:", "https:"].includes(url.protocol)) throw new Error("baseUrl must use HTTP(S) or unix://");
  return { baseUrl: url.href.replace(/\/$/, ""), fetch: request };
}
