#!/usr/bin/env bun
import {mkdtemp, mkdir, rm, copyFile} from "node:fs/promises";
import {resolve, join} from "node:path";
import {createServer, Socket} from "node:net";
import {strict as assert} from "node:assert";

const root = process.env.WINE_RUNTIME_ROOT!;
const directory = await mkdtemp("/tmp/opencode/wine-helper-");
const wine = join(root, "usr/lib/x86_64-linux-gnu/wine/wine");
const wineserver = join(root, "usr/lib/x86_64-linux-gnu/wine/wineserver");
const display = Bun.spawn([join(root, "usr/bin/Xvfb"), "-displayfd", "1", "-screen", "0", "1280x800x24", "-nolisten", "tcp"], {stdout: "pipe", stderr: Bun.file(join(directory, "xvfb.log"))});
const reader = display.stdout.getReader();
const first = await reader.read();
if (!first.value) throw Error("Xvfb failed");
const env = {...process.env, WINEPREFIX: join(directory, "prefix"), WINEARCH: "win64", WINEDEBUG: process.env.WINE_TEST_DEBUG || "-all", WINESERVER: wineserver,
  WINEDLLOVERRIDES: "winemenubuilder.exe=d;mscoree=d", DISPLAY: ":" + Buffer.from(first.value).toString().trim(),
  LD_LIBRARY_PATH: join(root, "usr/lib/x86_64-linux-gnu")};
const windows = (path: string) => "Z:" + resolve(path).replaceAll("/", "\\");
await mkdir(env.WINEPREFIX, {recursive: true});
for (const [target, arch] of [["system32", "x86_64"], ["syswow64", "i686"]]) {
  const destination = join(env.WINEPREFIX, "drive_c/windows", target);
  await mkdir(destination, {recursive: true});
  await copyFile(join(root, `usr/${arch}-w64-mingw32/lib/zlib1.dll`), join(destination,"zlib1.dll"));
}
const children: ReturnType<typeof Bun.spawn>[] = [];
let stage = "wineboot";
async function run(args: string[]) {
  const process = Bun.spawn(args, {env, stdout: "inherit", stderr: "inherit"});
  children.push(process);
  assert.equal(await process.exited, 0);
}
async function worker(architecture = "x64", runtime = "dotnet") {
  let socket!: Socket;
  let buffered = Buffer.alloc(0);
  const frames: any[] = [];
  const waiters: {resolve: (x: any) => void, reject: (e: Error) => void}[] = [];
  const receive = () => frames.length ? Promise.resolve(frames.shift()) : new Promise<any>((resolve, reject) => waiters.push({resolve, reject}));
  const server = createServer(s => {
    socket = s;
    socket.on("data", bytes => {
      buffered = Buffer.concat([buffered, bytes]);
      while (buffered.length >= 4 && buffered.length >= 4 + buffered.readUInt32LE()) {
        const size = buffered.readUInt32LE();
        const value = JSON.parse(buffered.subarray(4, 4 + size).toString());
        buffered = buffered.subarray(4 + size);
        const waiter = waiters.shift(); if (waiter) waiter.resolve(value); else frames.push(value);
      }
    });
    socket.on("close", () => { for (const waiter of waiters.splice(0)) waiter.reject(Error("Worker connection closed")); });
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as any).port;
  const token = crypto.randomUUID();
  const args = runtime === "framework" ? [wine, windows(`wine/dist/helper/framework-${architecture}/Sprout.Wine.Helper.exe`), String(port)] :
    [wine, join(root, `dotnet-${architecture}/dotnet.exe`), windows("wine/dist/helper/Sprout.Wine.Helper.dll"), String(port)];
  const child = Bun.spawn(args, {env: {...env, WINEDLLOVERRIDES:"winemenubuilder.exe=d;mscoree=b", SPROUT_WINE_TOKEN: token}, stdout: "inherit", stderr: "inherit"});
  children.push(child);
  const hello = await receive(); assert.equal(hello.token, token); assert.equal(hello.bits, architecture === "x64" ? 64 : 32);
  return {
    async call(request: any) {
      const bytes = Buffer.from(JSON.stringify(request)); const header = Buffer.alloc(4); header.writeUInt32LE(bytes.length);
      socket.write(Buffer.concat([header, bytes]));
      const response = await receive(); if (!response.ok) throw Error(JSON.stringify(response.error)); return response.result;
    },
    async close() { socket.destroy(); server.close(); child.kill(); await child.exited; },
  };
}
const deadline = setTimeout(() => { console.error("TIMEOUT at " + stage); for (const p of children) p.kill(9); display.kill(); process.exit(1); }, 180000);
try {
  await run([wine, "wineboot.exe", "-u"]);
  stage = "native";
  const native = await worker();
  try {
    await native.call({op:"load",kind:"native",path:"kernel32.dll"});
    const result = await native.call({op:"call",member:"lstrlenW",returnType:"i32",args:[{type:"utf16",value:"hello 世界"}]});
    assert.equal(result.value, 8);
    assert((await native.call({op:"inspect"})).namedExports.includes("lstrlenW"));
    const copied = await native.call({op:"call",member:"lstrcpyW",returnType:"ptr",args:[{type:"buffer",size:32},{type:"utf16",value:"世界"}]});
    assert.equal(Buffer.from(copied.outputs[0].base64,"base64").subarray(0,4).toString("utf16le"),"世界");
    console.log("native scalar, UTF-16, output buffer passed");
  } finally { await native.close(); }
  stage = "dotnet";
  const managed = await worker();
  try {
    await managed.call({op:"load",kind:"dotnet",path:windows("wine/dist/fixture/Fixture.dll")});
    const invoke = (request: any) => managed.call({op:"call",typeName:"Fixture.Calculator",...request});
    assert.equal((await invoke({member:"Sum",args:[{type:"i32",value:20},{type:"i32",value:22}]})).value,42);
    assert.equal((await invoke({member:"Large",args:[{type:"i64",value:"9223372036854775807"}]})).value,"9223372036854775807");
    const object = (await invoke({operation:"create",args:[{type:"i32",value:10}]})).value.handle;
    assert.equal((await invoke({target:object,member:"Add",args:[{type:"i32",value:5}]})).value,15);
    await assert.rejects(invoke({member:"Fail",args:[]}),/fixture failure/);
    assert.equal((await invoke({member:"Echo",args:[{type:"string",value:"中文"}]})).value,"中文");
    console.log("dotnet static/instance calls, handles, int64, exception recovery passed");
  } finally { await managed.close(); }
  stage = "com";
  const com = await worker();
  try {
    await com.call({op:"load",kind:"com",classId:"Scripting.Dictionary"});
    await com.call({op:"call",member:"Add",args:[{type:"string",value:"answer"},{type:"i32",value:42}]});
    assert.equal((await com.call({op:"call",operation:"get",member:"Count",args:[]})).value,1);
    assert.equal((await com.call({op:"call",operation:"get",member:"Item",args:[{type:"string",value:"answer"}]})).value,42);
    console.log("COM IDispatch method/property calls passed");
  } finally { await com.close(); }
  stage = "exe";
  const exe = await worker();
  try { assert.equal((await exe.call({op:"exec",exe:"cmd.exe",arguments:["/d","/c","exit 7"],cwd:"C:\\",stdinBase64:""})).exitCode,7); }
  finally { await exe.close(); }
  console.log("EXE exit code passed");
  stage = "x86";
  const x86 = await worker("x86");
  try {
    await x86.call({op:"load",kind:"native",path:"kernel32.dll"});
    assert.equal((await x86.call({op:"call",member:"lstrlenW",returnType:"i32",args:[{type:"utf16",value:"x86"}]})).value,3);
  } finally { await x86.close(); }
  console.log("32-bit native helper under WoW64 passed");
  stage = "framework-install";
  await run([wine,"msiexec.exe","/i",windows(join(root,"wine-mono-9.4.0-x86.msi")),"/qn","/norestart"]);
  for (const architecture of ["x64", "x86"]) {
    stage = "framework-" + architecture;
    const framework = await worker(architecture,"framework");
    try {
      await framework.call({op:"load",kind:"dotnet",path:windows("wine/dist/fixture-framework/Fixture.exe")});
      assert.equal((await framework.call({op:"call",typeName:"Fixture.Calculator",member:"Sum",args:[{type:"i32",value:20},{type:"i32",value:22}]})).value,42);
    } finally { await framework.close(); }
    console.log(`.NET Framework 4.8 ${architecture} assembly under Wine Mono passed`);
  }
} finally {
  clearTimeout(deadline);
  await run([wineserver,"-k"]);
  await run([wineserver,"-w"]);
  display.kill(); await display.exited;
  await rm(directory, {recursive:true,force:true});
}
