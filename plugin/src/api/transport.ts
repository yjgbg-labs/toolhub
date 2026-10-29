export type Fetch = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

export function defaultBaseUrl(env: Record<string, string | undefined> = process.env): string {
  return `http://127.0.0.1:${env.SPROUT_PORT || "8080"}`;
}

export function transport(value: string, request: Fetch = globalThis.fetch): { baseUrl: string; fetch: Fetch } {
  const url = new URL(value);
  if (url.username || url.password || url.search || url.hash) throw new Error("baseUrl must not contain credentials, query or fragment");
  if (!["http:", "https:"].includes(url.protocol)) throw new Error("baseUrl must use HTTP(S)");
  return { baseUrl: url.href.replace(/\/$/, ""), fetch: request };
}
