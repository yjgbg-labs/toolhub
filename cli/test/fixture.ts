export function document() {
  return {
    openapi: "3.1.2", info: { title: "Client contract tests", version: "1" },
    tags: [
      { name: "admin", description: "HTTP classification, independent of RPC groups" },
      { name: "unused", description: "A tag without any operations" },
    ],
    "x-rpc": {
      commands: [
        { name: "admin", description: "Administration\nAdministrative operations" },
        { name: "admin.users", description: "Administer users" },
        { name: "users", description: "Manage users" },
      ],
    },
    paths: {
      "/admin/users/get/{id}": { post: {
        operationId: "admin.users.get", summary: "Get a user",
        parameters: [
          { name: "id", in: "path", required: true, schema: { type: "string" } },
          { name: "id", in: "query", schema: { type: "integer" } },
          { name: "tag", in: "query", explode: true, schema: { type: "array", items: { type: "string" } } },
          { name: "limit", in: "query", schema: { type: "integer", minimum: 1, maximum: 100, default: 20 } },
          { name: "active", in: "query", schema: { type: "boolean" } },
          { name: "X-Trace", in: "header", required: true, schema: { type: "string" } },
          { name: "session", in: "cookie", required: true, schema: { type: "string" } },
        ],
        responses: { "200": { description: "echo", content: { "application/json": { schema: { type: "object" } } } } },
      } },
      "/users/create": { post: {
        operationId: "users.create",
        requestBody: { required: true, content: { "application/json": { schema: { $ref: "#/components/schemas/Tree" } } } },
        responses: { "201": { description: "created", content: { "application/json": { schema: { $ref: "#/components/schemas/Tree" } } } } },
      } },
      "/health": { get: { operationId: "health", responses: { "200": { description: "ok", content: { "text/plain": { schema: { type: "string" } } } } } } },
      "/empty": { delete: { operationId: "users.clear", responses: { "204": { description: "empty" } } } },
      "/fail": { post: { operationId: "users.fail", responses: { "400": { description: "bad" } } } },
    },
    components: { schemas: { Tree: {
      type: "object", required: ["name"], properties: {
        name: { type: "string", minLength: 1 },
        children: { type: "array", items: { $ref: "#/components/schemas/Tree" }, default: [] },
      },
    } } },
  };
}

export function fixture() {
  const doc = document();
  const requests: Request[] = [];
  const server = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    async fetch(request) {
      const url = new URL(request.url);
      if (url.pathname === "/api/openapi.json") return Response.json(doc);
      requests.push(request);
      if (url.pathname === "/api/health") return new Response("ok");
      if (url.pathname === "/api/empty") return new Response(null, { status: 204 });
      if (url.pathname === "/api/fail") return Response.json({ message: "rejected" }, { status: 400 });
      if (url.pathname === "/api/users/create") return Response.json(await request.json(), { status: 201 });
      return Response.json({
        path: url.pathname, query: [...url.searchParams], method: request.method,
        trace: request.headers.get("X-Trace"), cookie: request.headers.get("Cookie"), auth: request.headers.get("Authorization"),
      });
    },
  });
  return { baseUrl: `${server.url.toString().replace(/\/$/, "")}/api`, server, requests, doc };
}
