# Wine：一次性执行 EXE 与调用库函数

两个入口：`wine.exec` 执行程序，`wine.call` 调用原生 DLL、.NET 类库或 COM。
请求同步返回；默认 Wine prefix 自动准备、持续复用。每次执行使用独立进程，完成后清理。
同一套声明生成 HTTP、CLI、OpenCode Code Mode 和 GraalJS SDK。

## 执行程序：wine.exec

```js
const result = await tools.wine.exec({
  exe: "/data/tool.exe",
  args: ["--input", "Z:\\data\\input.bin"],
  cwd: "/data",
  env: {MODE: "batch"},
  timeoutMs: 30000
});
// result.body: {exitCode, stdout, stderr, durationMs, stdoutTruncated, stderrTruncated}
```

- `exe`：Linux 绝对路径、Windows 路径或 Wine 系统程序名（例如 `reg.exe`）。
- `args=[]`：字面参数数组，不经 shell。参数中的文件路径需要使用 Windows 路径。
- `cwd`：可选的、存在的 Linux 绝对目录；默认 prefix 的 `drive_c`。
- `env={}`：程序环境变量。Wine 环境由服务端管理，其控制变量优先于用户变量。
- `stdinBase64=""`：原始输入，提供完毕后关闭 stdin。
- `runtime=wine`：直接 `wine exe args...`；`dotnet` 使用随附的 Windows `dotnet.exe` 执行有入口的程序集；`framework` 先按需准备 Wine Mono，再直接运行 EXE。
- `architecture=x64`：可选 `x86`，用于选择现代 .NET 运行时或 Framework 安装检查。原生 EXE 自身的 PE 位数由 Wine WoW64 识别。
- 返回的 `exitCode` 是 Wine/Linux 进程退出码（通常为 Windows 退出码的低 8 位）。非零退出码作为正常执行结果返回。

执行现代 .NET 应用 DLL：

```js
await tools.wine.exec({
  exe: "/data/App.dll", runtime: "dotnet", args: ["--version"]
});
```

## 调用库函数：wine.call

### 原生 DLL

```js
const result = await tools.wine.call({
  kind: "native",
  path: "kernel32.dll",
  member: "lstrlenW",
  architecture: "x64",
  callingConvention: "winapi",
  returnType: "i32",
  args: [{type: "utf16", value: "hello 世界"}]
});
// result.body.value === 8
```

原生调用需要准确的导出名、参数和返回类型，支持 `winapi/cdecl/stdcall`。
类型为 `i8/u8/i16/u16/i32/u32/i64/u64/f32/f64/ptr`；返回类型还支持 `void`。
Windows BOOL 使用 `i32`。64 位整数使用十进制字符串，避免 JavaScript 精度损失。

- `utf8/utf16` 参数自动补 NUL，内存仅在该步骤内有效。
- `buffer` 参数以 Base64 提供 `value`，可指定容量 `size`；结果 `outputs` 包含 `{index,base64}`。
- `ptr` 只能使用同一请求内的 `ref` 或 null，原始地址不会作为可跨请求使用的句柄返回。
- 返回本次临时参数内存中的指针时，结果描述为 `{argument,offset,ephemeral:true}`，不能 `saveAs`。
- C++ 类、回调、变参及复杂按值结构体使用匹配 ABI 的薄包装 DLL。

### .NET 类库

```js
await tools.wine.call({
  kind: "dotnet", path: "/data/Calculator.dll",
  typeName: "Demo.Calculator", member: "Add",
  args: [{type: "i32", value: 1}, {type: "i32", value: 2}]
});
```

`runtime=dotnet` 使用 Windows .NET 10 调用器。`runtime=framework` 使用 net48 调用器，并按需安装 Wine Mono；已有对应位数原生 CLR 时尊重其配置。
Wine Mono 是兼容实现，测试通过的 net48 路径不代表所有 Microsoft CLR 专有行为都兼容。
两种调用器都支持 `architecture=x64/x86`。

`operation=invoke/create/get/set` 分别执行方法、构造函数、属性读取和属性设置。
参数支持数值、`bool/string/bytes/object` 及 CLR 完整类型名；bytes 使用 Base64，重载按显式参数类型匹配。
方法返回 Task 时会等待完成。复杂对象通过批量步骤读取所需字段/属性，最终返回可序列化结果。

### COM

```js
await tools.wine.call({
  kind: "com", classId: "Scripting.Dictionary",
  steps: [
    {member: "Add", args: [{type: "string", value: "answer"}, {type: "i32", value: 42}]},
    {operation: "get", member: "Item", args: [{type: "string", value: "answer"}]}
  ]
});
// body.value === 42
```

classId 为 ProgID 或 CLSID，组件需已在默认 prefix 注册，位数必须匹配。
调用器在 STA 线程中调用 IDispatch 方法及属性；自定义 vtable 接口需包装组件。

## 多步操作和请求内引用

单步参数与 `steps` 二选一。最多 64 步，每步最多 32 个参数。
所有步骤在同一个调用器进程和同一个库中依次执行；失败即停止后续步骤。

```js
await tools.wine.call({
  kind: "dotnet", path: "/data/Calculator.dll",
  steps: [
    {operation: "create", typeName: "Demo.Calculator", args: [{type: "i32", value: 10}], saveAs: "counter"},
    {target: "counter", member: "Add", args: [{type: "i32", value: 5}]},
    {target: "counter", operation: "get", member: "Total"}
  ]
});
```

- `saveAs` 保存当前步骤的原始返回值，名称唯一；`target` 为此前保存的对象。
- 参数 `{type: "object", ref: "counter"}` 或 `{type: "ptr", ref: "memory"}` 引用此前的结果；`ref` 和 `value` 互斥。
- 保存的复杂对象/指针在该步骤的 JSON `value` 中表示为 null，名称只在本次请求内有效。
- `operation=release, target=...` 显式释放托管/COM 对象，剩余对象在退出时释放。原生内存须调用库对应的 free/close 函数，release 不猜测释放器。
- 原生 `operation=read, target=..., size=..., returnType=buffer/utf8/utf16` 从有效指针复制指定字节数，最大 512 KiB；调用方负责保证内存范围有效。
- `operation=inspect` 返回原生命名导出（最多 2000）或 .NET 公共方法/构造函数签名，不会推断原生 ABI。

调用结果包含末步的 `value/outputs`、各步的 `steps:[{value,outputs}]`、`stdout/stderr`、`durationMs` 以及输出截断标志。单步调用的 `steps=[]`。

## 执行与错误语义

- 两个入口都默认 `timeoutMs=30000`，范围 1 ms–24 h；包含排队、环境准备和执行时间，清理可能额外占用少量时间。
- 首次创建 prefix、首次安装 Mono 比后续调用慢，可显式提高超时。
- 最多并行 8 次请求，默认 prefix 中的文件和注册表共享。
- 每次执行属于独立的 systemd user service/cgroup；正常结束、请求取消或超时时清理全部子孙进程，其他请求和共享 wineserver 继续运行。
- stdout/stderr 分别保留前 64 KiB，超限继续排空并设置截断标志，按 UTF-8 解码。
- 输入和结构化结果上限各 1 MiB；超限结果以结构化错误报告。
- 400 表示参数错误，502 表示库调用失败/调用器崩溃，503 表示环境不可用，504 表示超时。错误包含 `code/message/stdout/stderr/durationMs`，批量错误附带从 0 开始的 `step`。
- 程序请求取消时终止执行；这两个接口不创建后台任务或跨请求对象。

## 实现及部署

EXE 由 Scala 直接启动 Wine；库函数由一次性 C# 调用器执行：

```text
wine dotnet.exe Sprout.Wine.Helper.dll --request request.json --result result.json
```

net48 版本直接运行 `Sprout.Wine.Helper.exe`。请求与结果使用独立文件，程序 stdout/stderr 不参与协议。
服务端自动管理一个私有 Xvfb 显示和 `$WINE_HOME/prefix`，按需初始化并复用，退出服务时清理 Wine 进程。
运行依赖 Linux user systemd（部署服务本身也由 user systemd 管理），无需 sudo。

```sh
groot-cli jobs --job wine-install
groot-cli jobs --job wine-build
groot-cli jobs --job wine-test
groot-cli jobs --job wine-helper-deploy
groot-cli jobs --job wine-deploy
```

`config.env` 配置 `WINE_HOME`、`WINE_RUNTIME_ROOT`、`WINE_HELPER_ROOT`。Wine 安装面向本机 Ubuntu amd64，解包 apt 软件包至用户目录；.NET/Mono 下载校验官方摘要。
调用器以内容寻址不可变版本安装，通过符号链接原子切换。net48 x64/x86 使用独立的构建中间目录。
