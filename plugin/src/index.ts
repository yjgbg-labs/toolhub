import { Plugin } from "@opencode/plugin";
import { ApiClient } from "@sprout/cli/api";
import { operationTools } from "./tools";

export default Plugin.define({
  id: "sprout",
  async setup(ctx) {
    function stringOption(key: string, fallback?: string): string | undefined {
      const value = ctx.options[key] ?? fallback;
      if (value !== undefined && typeof value !== "string") throw new Error(`Sprout option ${key} must be a string`);
      return value;
    }
    const tokenEnv = stringOption("tokenEnv", "SPROUT_TOKEN")!;
    const token = process.env[tokenEnv];
    const rawHeaders = ctx.options.headers ?? {};
    if (!rawHeaders || typeof rawHeaders !== "object" || Array.isArray(rawHeaders) || Object.values(rawHeaders).some(value => typeof value !== "string")) {
      throw new Error("Sprout headers must be a string map");
    }
    const timeoutMs = ctx.options.timeoutMs ?? 30_000;
    if (typeof timeoutMs !== "number" || !Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) throw new Error("Sprout timeoutMs must be a positive integer");
    const client = await ApiClient.connect({
      baseUrl: stringOption("baseUrl", process.env.SPROUT_URL ?? "http://127.0.0.1:8080")!,
      schemaUrl: stringOption("schemaUrl", process.env.SPROUT_OPENAPI_URL),
      headers: { ...rawHeaders as Record<string, string>, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      timeoutMs,
    });
    const tools = operationTools(client, stringOption("namespace", "sprout"));
    const registration = await ctx.tool.transform(editor => {
      const namespaces = new Set(tools.map(tool => tool.options!.namespace!));
      for (const name of namespaces) editor.namespace({ name, description: `${client.catalog.title} — ${name}` });
      for (const tool of tools) editor.add(tool);
    });
    return () => registration.dispose();
  },
});
