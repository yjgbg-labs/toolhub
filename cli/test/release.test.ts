import { afterAll, expect, test } from "bun:test";
import { lstat, mkdir, mkdtemp, readFile, readlink, rm, symlink, writeFile } from "node:fs/promises";
import { join } from "node:path";

const directory = await mkdtemp("/tmp/opencode/native-release-");
afterAll(() => rm(directory, { recursive: true, force: true }));
const jobs = Bun.YAML.parse(await Bun.file("jobs.yaml").text()) as { jobs: { name: string; run: string }[] };
const run = jobs.jobs.find(job => job.name === "cli-deploy")!.run;
const bytes = await Bun.file("cli/dist/groot-cli").bytes();
const sha256 = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
let corrupt = false;
const server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
  const path = new URL(request.url).pathname;
  if (path === `/native-cli/${sha256}/groot-cli.sha256`) return new Response(`${sha256}  groot-cli\n`);
  if (path === `/native-cli/${sha256}/groot-cli`) return new Response(corrupt ? "corrupt binary" : bytes);
  return new Response(null, { status: 404 });
} });
afterAll(() => server.stop(true));
async function install(root: string, bin = join(directory, "bin")) {
  const child = Bun.spawn(["sh", "-e", "-c", run], {
    env: { ...process.env, HOME: directory, INSTALL_ONLY: "1", ARTIFACT_DOWNLOAD_BASE: server.url.href.replace(/\/$/, ""), CLI_SHA256: sha256, CLI_INSTALL_ROOT: root, CLI_BIN_DIR: bin },
    stdout: "pipe", stderr: "pipe",
  });
  const [code, stdout, stderr] = await Promise.all([child.exited, new Response(child.stdout).text(), new Response(child.stderr).text()]);
  return { code, output: stdout + stderr };
}
test("Native installation uses verified bytes and preserves the active command on corrupt downloads", async () => {
  const root = join(directory, "home");
  const result = await install(root);
  expect(result.code, result.output).toBe(0);
  const binary = join(directory, "bin/groot-cli");
  const destination = await readlink(binary);
  expect(destination).toBe(join(root, "releases", sha256, "groot-cli"));
  expect(await readlink(join(directory, "bin/sprout"))).toBe(destination);
  expect(await lstat(join(directory, "bin/sprout-native")).catch(() => null)).toBeNull();
  expect(new Bun.CryptoHasher("sha256").update(await Bun.file(binary).bytes()).digest("hex")).toBe(sha256);
  expect((await install(root)).code).toBe(0);
  try {
    corrupt = true;
    expect((await install(root)).code).not.toBe(0);
    expect(await readlink(binary)).toBe(destination);
  } finally { corrupt = false; }
});

test("installation migrates managed legacy command links to the default groot-cli root", async () => {
  const legacyRoot = join(directory, ".local/share/sprout-native");
  const legacy = join(legacyRoot, "releases", "old", "sprout-native");
  const root = join(directory, ".local/share/groot-cli");
  const bin = join(directory, "migration-bin");
  await mkdir(join(legacyRoot, "releases/old"), { recursive: true });
  await writeFile(legacy, "previous release");
  await mkdir(bin);
  for (const name of ["sprout", "sprout-native", "groot-cli"]) await symlink(legacy, join(bin, name));
  const result = await install("", bin);
  expect(result.code, result.output).toBe(0);
  expect(await readlink(join(bin, "groot-cli"))).toBe(join(root, "releases", sha256, "groot-cli"));
  expect(await readlink(join(bin, "sprout"))).toBe(await readlink(join(bin, "groot-cli")));
  expect(await lstat(join(bin, "sprout-native")).catch(() => null)).toBeNull();
  expect(await readFile(legacy, "utf8")).toBe("previous release");
});

test("installation preserves unrelated aliases and supports same-hash legacy releases", async () => {
  const root = join(directory, "custom-root");
  const release = join(root, "releases", sha256);
  const bin = join(directory, "custom-bin");
  const owned = join(directory, "user-command");
  await mkdir(release, { recursive: true });
  await mkdir(bin);
  await writeFile(join(release, "sprout-native"), bytes);
  await writeFile(owned, "user-owned command");
  await symlink(owned, join(bin, "groot-cli"));
  await symlink(owned, join(bin, "sprout-native"));
  const rejected = await install(root, bin);
  expect(rejected.code).not.toBe(0);
  expect(rejected.output).toContain("Unmanaged link");
  expect(await readlink(join(bin, "groot-cli"))).toBe(owned);
  await rm(join(bin, "groot-cli"));
  const result = await install(root, bin);
  expect(result.code, result.output).toBe(0);
  expect(await readlink(join(bin, "groot-cli"))).toBe(join(release, "groot-cli"));
  expect(new Bun.CryptoHasher("sha256").update(await Bun.file(join(bin, "groot-cli")).bytes()).digest("hex")).toBe(sha256);
  expect(await readlink(join(bin, "sprout-native"))).toBe(owned);
  expect(await readFile(owned, "utf8")).toBe("user-owned command");
});
