import * as Plugin from "@opencode/plugin/promise/plugin";
import { ApiClient, defaultBaseUrl } from "./api";
import { operationTools } from "./tools";
import { startNotifications } from "./notifications";

export default Plugin.define({
  id: "groot",
  async setup(ctx) {
    function stringOption(key: string, fallback?: string): string | undefined {
      const value = ctx.options[key] ?? fallback;
      if (value !== undefined && typeof value !== "string") throw new Error(`Groot option ${key} must be a string`);
      return value;
    }
    const tokenEnv = stringOption("tokenEnv", "GROOT_TOKEN")!;
    const token = process.env[tokenEnv];
    const rawHeaders = ctx.options.headers ?? {};
    if (!rawHeaders || typeof rawHeaders !== "object" || Array.isArray(rawHeaders) || Object.values(rawHeaders).some(value => typeof value !== "string")) {
      throw new Error("Groot headers must be a string map");
    }
    const timeoutMs = ctx.options.timeoutMs ?? 30_000;
    if (typeof timeoutMs !== "number" || !Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) throw new Error("Groot timeoutMs must be a positive integer");
    const client = await ApiClient.connect({
      baseUrl: stringOption("baseUrl", process.env.GROOT_BASE_URL || defaultBaseUrl())!,
      schemaUrl: stringOption("schemaUrl", process.env.GROOT_OPENAPI_URL),
      headers: { ...rawHeaders as Record<string, string>, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      timeoutMs,
    });
    const tools = operationTools(client);
    const registration = await ctx.tool.transform(editor => {
      const namespaces = new Map(client.catalog.groupDescriptions);
      for (const tool of tools) {
        const name = tool.options?.namespace;
        if (name && !namespaces.has(name)) namespaces.set(name, `${client.catalog.title} — ${name}`);
      }
      for (const [name, description] of namespaces) editor.namespace({ name, description });
      for (const tool of tools) editor.add(tool);
    });
    try {
      const stopNotifications = await startNotifications(ctx, client);
      return async () => {
        try { await stopNotifications(); }
        finally { await registration.dispose(); }
      };
    } catch (error) {
      await registration.dispose();
      throw error;
    }
  },
});
