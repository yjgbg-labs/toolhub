#!/usr/bin/env bun
import {mkdtemp, mkdir, rm, copyFile} from "node:fs/promises";
import {resolve, join} from "node:path";
import {strict as assert} from "node:assert";

const root = process.env.WINE_RUNTIME_ROOT!;
const directory = await mkdtemp("/tmp/opencode/wine-invoker-");
const wine = join(root,"usr/lib/x86_64-linux-gnu/wine/wine");
const server = join(root,"usr/lib/x86_64-linux-gnu/wine/wineserver");
const windows = (path: string) => "Z:" + resolve(path).replaceAll("/","\\");
const display = Bun.spawn([join(root,"usr/bin/Xvfb"),"-displayfd","1","-screen","0","1280x800x24","-nolisten","tcp"],
  {stdout:"pipe",stderr:Bun.file(join(directory,"xvfb.log"))});
const first = await display.stdout.getReader().read();
assert(first.value,"Xvfb did not start");
const env = {...process.env,WINEPREFIX:join(directory,"prefix"),WINEARCH:"win64",WINEDEBUG:"-all",WINESERVER:server,
  WINEDLLOVERRIDES:"winemenubuilder.exe=d;mscoree=d",DISPLAY:":"+Buffer.from(first.value).toString().trim(),
  LD_LIBRARY_PATH:join(root,"usr/lib/x86_64-linux-gnu")};
async function run(args: string[], cleanup = false) {
  const child = Bun.spawn(args,{env,stdout:"inherit",stderr:"inherit"});
  const timer = setTimeout(()=>child.kill(9),120000);
  try { const code=await child.exited; if(!cleanup)assert.equal(code,0,`${args.join(" ")} exited ${code}`); } finally { clearTimeout(timer); }
}
async function invoke(request: any) {
  const current = await mkdtemp(join(directory,"call-"));
  const input=join(current,"request.json"),output=join(current,"result.json");
  await Bun.write(input,JSON.stringify(request));
  const architecture=request.architecture ?? "x64";
  const args=request.runtime === "framework" ? [wine,windows(`wine/dist/helper/framework-${architecture}/Sprout.Wine.Helper.exe`)] :
    [wine,join(root,`dotnet-${architecture}/dotnet.exe`),windows("wine/dist/helper/Sprout.Wine.Helper.dll")];
  const child=Bun.spawn([...args,"--request",windows(input),"--result",windows(output)],
    {env:{...env,WINEDLLOVERRIDES:"winemenubuilder.exe=d;mscoree=b"},stdout:Bun.file(join(current,"stdout")),stderr:Bun.file(join(current,"stderr"))});
  const timer=setTimeout(()=>child.kill(9),30000);
  try {
    const code=await child.exited;
    const response=await Bun.file(output).json().catch(async error=>{throw Error(`Invoker exit ${code}: ${await Bun.file(join(current,"stderr")).text()} ${error}`);});
    if(!response.ok) throw Error(JSON.stringify(response.error));
    assert.equal(code,0);
    return {...response.result,stdout:await Bun.file(join(current,"stdout")).text()};
  } finally {clearTimeout(timer);}
}
const i32=(value:number)=>({type:"i32",value});
try {
  for(const [target,arch] of [["system32","x86_64"],["syswow64","i686"]]) {
    const destination=join(env.WINEPREFIX,"drive_c/windows",target);
    await mkdir(destination,{recursive:true});
    await copyFile(join(root,`usr/${arch}-w64-mingw32/lib/zlib1.dll`),join(destination,"zlib1.dll"));
  }
  await run([server,"-p"]);
  await run([wine,"wineboot.exe","-u"]);
  for(const architecture of ["x64","x86"]) {
    assert.equal((await invoke({kind:"native",path:"kernel32.dll",architecture,member:"lstrlenW",returnType:"i32",args:[{type:"utf16",value:"hello 世界"}]})).value,8);
    const copied=await invoke({kind:"native",path:"kernel32.dll",architecture,member:"lstrcpyW",returnType:"ptr",args:[{type:"buffer",size:32},{type:"utf16",value:"世界"}]});
    assert.equal(Buffer.from(copied.outputs[0].base64,"base64").subarray(0,4).toString("utf16le"),"世界");
    assert((await invoke({kind:"native",path:"kernel32.dll",architecture,operation:"inspect"})).value.namedExports.includes("lstrlenW"));
  }
  assert.equal((await invoke({kind:"native",path:"msvcrt.dll",architecture:"x86",member:"abs",returnType:"i32",callingConvention:"cdecl",args:[i32(-42)]})).value,42);
  const memory=await invoke({kind:"native",path:"kernel32.dll",steps:[
    {member:"GlobalAlloc",returnType:"ptr",args:[{type:"u32",value:64},{type:"u64",value:"16"}],saveAs:"memory"},
    {operation:"read",target:"memory",returnType:"buffer",size:16},
    {member:"GlobalFree",returnType:"ptr",args:[{type:"ptr",ref:"memory"}]}
  ]});
  assert.equal(memory.steps[1].value,Buffer.alloc(16).toString("base64"));
  console.log("one-shot native x64/x86, cdecl, buffers, inspect and request-local pointers passed");
  const managed={kind:"dotnet",path:windows("wine/dist/fixture/Fixture.dll"),typeName:"Fixture.Calculator"};
  assert.equal((await invoke({...managed,member:"Sum",args:[i32(20),i32(22)]})).value,42);
  assert.equal((await invoke({...managed,member:"Large",args:[{type:"i64",value:"9223372036854775807"}]})).value,"9223372036854775807");
  const batch=await invoke({kind:managed.kind,path:managed.path,steps:[
    {operation:"create",typeName:managed.typeName,args:[i32(10)],saveAs:"counter"},
    {target:"counter",member:"Add",args:[i32(5)]},
    {operation:"get",target:"counter",member:"Total"}
  ]});
  assert.equal(batch.value,15); assert.equal(batch.steps[0].value,null);
  await assert.rejects(invoke({...managed,member:"Fail"}),/fixture failure/);
  const echo=await invoke({...managed,member:"Echo",args:[{type:"string",value:'{"ok":false} 中文'}]});
  assert.equal(echo.value,'{"ok":false} 中文'); assert.match(echo.stdout,/fixture log/);
  await assert.rejects(invoke({...managed,target:"counter",member:"Add",args:[i32(1)]}),/not present|key/i);
  console.log("managed batches, int64, error results, isolated references and stdout separation passed");
  const com=await invoke({kind:"com",classId:"Scripting.Dictionary",steps:[
    {member:"Add",args:[{type:"string",value:"answer"},i32(42)]},
    {operation:"get",member:"Item",args:[{type:"string",value:"answer"}]}
  ]});
  assert.equal(com.value,42);
  console.log("one-shot COM Automation passed");
  await run([wine,"msiexec.exe","/i",windows(join(root,"wine-mono-9.4.0-x86.msi")),"/qn","/norestart"]);
  for(const architecture of ["x64","x86"]) {
    assert.equal((await invoke({...managed,path:windows("wine/dist/fixture-framework/Fixture.exe"),runtime:"framework",architecture,member:"Sum",args:[i32(20),i32(22)]})).value,42);
  }
  console.log("one-shot net48 x64/x86 under Wine Mono passed");
} finally {
  await run([server,"-k"],true); await run([server,"-w"],true);
  display.kill(); await display.exited;
  await rm(directory,{recursive:true,force:true});
}
