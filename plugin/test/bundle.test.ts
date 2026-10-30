import { expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { fixture } from "./fixture";

test("the installed bundle loads outside the repository and executes dynamically discovered tools", async () => {
  const directory = await mkdtemp("/tmp/opencode/plugin-bundle-");
  const api = fixture();
  try {
    const root = new URL("../../", import.meta.url).pathname;
    const jobs = Bun.YAML.parse(await Bun.file(`${root}/jobs.yaml`).text()) as { jobs: { name: string; run: string }[] };
    const child = Bun.spawn(["sh", "-e", "-c", jobs.jobs.find(job => job.name === "pluging-deploy")!.run], {
      cwd: root, stdout: "pipe", stderr: "pipe",
      env: { ...process.env, INSTALL_ONLY: "1", CHECK_ONLY: "0", OPENCODE_PLUGIN_DIR: directory },
    });
    const [code, stdout, stderr] = await Promise.all([child.exited, new Response(child.stdout).text(), new Response(child.stderr).text()]);
    expect(code, stdout + stderr).toBe(0);
    const { default: plugin } = await import(pathToFileURL(`${directory}/sprout.ts`).href);
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
