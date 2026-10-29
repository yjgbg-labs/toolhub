import { afterAll, expect, test } from "bun:test";
import { mkdtemp, readlink, rm } from "node:fs/promises";
import { join } from "node:path";

const directory = await mkdtemp("/tmp/opencode/native-release-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const bytes = await Bun.file("cli/dist/sprout-native").bytes();
const sha256 = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
let corrupt = false;
const server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
  if (new URL(request.url).pathname.endsWith(".sha256")) return new Response(`${sha256}  sprout-native\n`);
  return new Response(corrupt ? "corrupt binary" : bytes);
} });
afterAll(() => server.stop(true));
async function install(root: string) {
  const child = Bun.spawn(["sh", "cli/scripts/release.sh", "install"], {
    env: { ...process.env, ARTIFACT_DOWNLOAD_BASE: server.url.href.replace(/\/$/, ""), CLI_SHA256: sha256, CLI_INSTALL_ROOT: root, CLI_BIN_DIR: join(directory, "bin") },
    stdout: "pipe", stderr: "pipe",
  });
  const [code, stdout, stderr] = await Promise.all([child.exited, new Response(child.stdout).text(), new Response(child.stderr).text()]);
  return { code, output: stdout + stderr };
}
test("Native installation uses verified bytes and preserves the active command on corrupt downloads", async () => {
  const root = join(directory, "home");
  const result = await install(root);
  expect(result.code, result.output).toBe(0);
  const binary = join(directory, "bin/sprout");
  const destination = await readlink(binary);
  expect(destination).toBe(join(root, "releases", sha256, "sprout-native"));
  expect(await readlink(join(directory, "bin/sprout-native"))).toBe(destination);
  expect(new Bun.CryptoHasher("sha256").update(await Bun.file(binary).bytes()).digest("hex")).toBe(sha256);
  corrupt = true;
  expect((await install(root)).code).not.toBe(0);
  expect(await readlink(binary)).toBe(destination);
});
