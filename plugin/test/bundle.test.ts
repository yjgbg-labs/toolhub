import { expect, test } from "bun:test";
import { cp, mkdtemp, rm } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { fixture } from "./fixture";

test("the bundled plugin loads outside the repository and executes dynamically discovered tools", async () => {
  const directory = await mkdtemp("/tmp/opencode/plugin-bundle-");
  const api = fixture();
  try {
    await cp(new URL("../dist/index.js", import.meta.url), `${directory}/index.js`);
    const { default: plugin } = await import(pathToFileURL(`${directory}/index.js`).href);
    const tools: any[] = [];
    let disposed = false;
    const cleanup = await plugin.setup({ options: { baseUrl: api.baseUrl }, tool: {
      transform(callback: (editor: any) => void) {
        callback({ namespace() {}, add(tool: any) { tools.push(tool); } });
        return { dispose() { disposed = true; } };
      },
    } });
    expect(tools).toHaveLength(5);
    expect((await tools.find(tool => tool.name === "health").execute({}, {})).output.body).toBe("ok");
    expect((await tools.find(tool => tool.name === "create").execute({ body: { name: "Ada" } }, {})).output.body).toEqual({ name: "Ada" });
    await cleanup();
    expect(disposed).toBe(true);
  } finally { await api.server.stop(true); await rm(directory, { recursive: true, force: true }); }
});
