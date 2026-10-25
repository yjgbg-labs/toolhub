#!/usr/bin/env bun
import assert from "node:assert/strict";
import { join } from "node:path";

const organization = process.env.SDK_ORGANIZATION!;
const repository = process.env.SDK_MAVEN_REPOSITORY!;
const client = process.env.SDK_CLIENT_MODULE!;
const modules = ["sprout-sdk-core", "sprout-sdk-generator", "sprout-sdk-cats", "sprout-sdk-zio", "sprout-sdk-sttp"];
if (process.argv[2] !== "protocol") modules.push(client);
let count = 0;
for (const [platform, suffix, extension] of [["jvm", "_3", ".class"], ["js", "_sjs1_3", ".sjsir"], ["native", "_native0.5_3", ".nir"]] as const) {
  for (const module of [...modules, ...(platform === "native" ? ["sprout-sdk-native-unix"] : [])]) {
    const version = module === client ? process.env.SDK_CLIENT_VERSION! : process.env.SDK_VERSION!;
    const artifact = module + suffix;
    const base = join(repository, organization.replaceAll(".", "/"), artifact, version, `${artifact}-${version}`);
    for (const ending of [".jar", "-sources.jar", ".pom"]) {
      const bytes = await Bun.file(base + ending).bytes();
      const expected = (await Bun.file(base + ending + ".sha256").text()).trim();
      assert.equal(new Bun.CryptoHasher("sha256").update(bytes).digest("hex"), expected, base + ending);
    }
    const pom = await Bun.file(base + ".pom").text();
    assert.ok(pom.includes(`<artifactId>${artifact}</artifactId>`), artifact);
    if (module !== "sprout-sdk-core") assert.ok(pom.includes(`<artifactId>sprout-sdk-core${suffix}</artifactId>`), `${artifact}: missing runtime dependency`);
    if (module === "sprout-sdk-generator") assert.ok(!pom.includes(`<artifactId>${client}${suffix}</artifactId>`), "Generator must not depend on a business SDK");
    if (module === "sprout-sdk-native-unix") assert.ok(pom.includes(`<artifactId>sprout-sdk-sttp${suffix}</artifactId>`));
    const child = Bun.spawn(["unzip", "-Z1", base + ".jar"], { stdout: "pipe", stderr: "pipe" });
    const [listing, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
    assert.equal(code, 0, stderr);
    const entries = listing.split("\n");
    assert.ok(entries.some(name => name.endsWith(extension)), `${artifact}: missing ${extension} files`);
    if (module === "sprout-sdk-core") assert.ok(entries.some(name => name === `sprout/sdk/Catalog${extension}`), `${artifact}: missing dynamic metadata layer`);
    assert.ok(!entries.some(name => name.startsWith("sprout/sdk/cli/")), `${artifact}: accidentally bundles CLI`);
    if (module !== "sprout-sdk-core") assert.ok(!entries.includes(`sprout/sdk/HttpRequest${extension}`), `${artifact}: accidentally bundles core`);
    assert.ok(!entries.some(name => name.includes("/tests/") || name.includes("/fixture/") || name.includes("/examples/")), `${artifact}: test/example code was packaged`);
    count++;
  }
}
console.log(`Verified ${count} Maven artifacts: platform code, dependency POMs, source jars and SHA-256`);
