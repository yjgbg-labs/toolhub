#!/usr/bin/env bun
const url = process.env.SPROUT_OPENAPI_URL || "http://localhost/openapi.json";
const socket = process.env.SPROUT_OPENAPI_URL ? process.env.SPROUT_SOCKET : process.env.SPROUT_SOCKET || `${process.env.XDG_RUNTIME_DIR}/sprout-http/http.sock`;
const response = await fetch(url, { unix: socket, redirect: "error", signal: AbortSignal.timeout(30_000) });
if (!response.ok) throw new Error(`OpenAPI fetch: HTTP ${response.status}`);
const schema = await response.json();
if (!/^3\.1\./.test(schema.openapi)) throw new Error("Expected OpenAPI 3.1");
await Bun.write(new URL("../openapi.json", import.meta.url), JSON.stringify(schema, null, 2) + "\n");
