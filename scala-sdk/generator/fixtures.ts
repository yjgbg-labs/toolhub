#!/usr/bin/env bun
import { main } from "./main";

export const fixture = {
  openapi: "3.1.2", info: { title: "SDK fixture", version: "1" },
  components: { schemas: {
    Tree: { type: "object", required: ["id", "value", "mode"], properties: {
      id: { type: "integer", format: "int64" }, value: { type: ["string", "null"] },
      child: { $ref: "#/components/schemas/Tree" },
      optional: { type: ["string", "null"] },
      mode: { $ref: "#/components/schemas/Mode" },
      labels: { type: "object", additionalProperties: { type: "number" } },
    } },
    Mode: { type: "string", enum: ["fast", "slow-mode"] },
    Event: { oneOf: [
      { type: "object", required: ["type", "value"], properties: { type: { const: "added" }, value: { type: "string" } } },
      { type: "object", required: ["type", "value"], properties: { type: { const: "removed" }, value: { type: "integer", format: "int32" } } },
    ] },
    NumberOrText: { anyOf: [{ type: "integer" }, { type: "string" }] },
  } },
  paths: {
    "/tree/{id}": { post: {
      operationId: "tree.update", parameters: [
        { name: "id", in: "path", required: true, schema: { type: "string" } },
        { name: "tag", in: "query", schema: { type: "array", items: { type: "string" } }, explode: true },
        { name: "joined", in: "query", schema: { type: "array", items: { type: "string" } }, explode: false },
        { name: "X-Token", in: "header", required: true, schema: { type: "string" } },
        { name: "session", in: "cookie", schema: { type: "string" } },
      ],
      requestBody: { required: true, content: { "application/json": { schema: { $ref: "#/components/schemas/Tree" } } } },
      responses: {
        "200": { description: "Updated", content: { "application/json": { schema: { $ref: "#/components/schemas/Tree" } } } },
        "202": { description: "Queued", content: { "text/plain": { schema: { type: "string" } } } },
        "204": { description: "No content" },
      },
    } },
  },
};

if (import.meta.main) {
  const file = new URL("../generated/fixture.json", import.meta.url);
  await Bun.write(file, JSON.stringify(fixture));
  await main(["--schema", file.pathname, "--package", "sprout.fixture", "--output", new URL("../generated/fixture", import.meta.url).pathname]);
}
