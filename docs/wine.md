# Wine：Windows EXE 与 DLL 会话

在 Linux 服务端运行 Windows 程序。每个命名实例使用独立 `WINEPREFIX`，保存注册表、运行库和 C: 文件。
命令由 Scala 声明统一生成 HTTP、CLI、Code Mode 与 GraalJS SDK。

## 实例

```js
await tools.wine.instance.start({instanceName: "legacy"});
await tools.wine.instance.list();
await tools.wine.instance.stop({instanceName: "legacy"});
await tools.wine.instance.purge({instanceName: "legacy"});
```

- `start` 默认 `arch=win64, headless=true`。headless 使用私有 Xvfb，不占用实际屏幕。
- win64 prefix 使用 Wine WoW64 执行 x64/x86 程序，DLL worker 用 `architecture` 明确选择位数。
- 同名、同配置启动幂等。prefix 架构与显示方式在创建时固定；`win32` 是否可用取决于 Wine 构建，本项目默认的 WoW64 构建使用 win64。
- `stop` 结束该实例 EXE 任务、DLL worker、wineserver 和 Xvfb，保留 prefix；`purge` 删除实例资料与 DLL 日志。
- EXE 任务记录和日志独立留存，可在实例停止/删除后读取。
- 服务退出时清理运行中实例。异常重启按 prefix 清理残留运行时，将未完成任务标记为 `interrupted`。
- 名称为 1–64 个 ASCII 字母、数字、点、下划线或连字符，首字符为字母或数字。

## EXE 任务

```js
const task = (await tools.wine.process.start({
  instanceName: "legacy",
  body: {
    exe: "/data/tool/tool.exe",
    args: ["--input", "Z:\\data\\input file.bin"],
    cwd: "/data/tool",
    env: { MODE: "batch" },
    timeoutMs: 300000
  }
})).body;
const finished = (await tools.wine.process.wait({id: task.id})).body;
console.log(finished.state, finished.exitCode);
console.log((await tools.wine.process.logs({id: task.id, stream: "stdout"})).body);
```

`start` 启动 worker 后返回任务 ID，执行独立于 HTTP 请求生命周期。

| 命令 | 用途 |
| --- | --- |
| `process.start(instanceName, body)` | EXE、`args=[]`、`cwd?`、`env={}`、`stdinBase64=""`、`timeoutMs=300000`、`architecture=x64` |
| `process.list(instanceName?)` | 最近 200 个任务，含已完成任务 |
| `process.status(id)` | 状态、时间、退出码、日志路径、错误 |
| `process.wait(id, timeoutMs=30000)` | 等待完成；等待超时或断开连接不取消任务 |
| `process.stop(id)` | 取消单个任务，清理它的 Windows Job 中的子进程 |
| `process.logs(id, stream=stdout)` | 最后 64 KiB 的 UTF-8 文本 |
| `process.logs-follow(id, stream=stdout)` | SSE 回放并跟随直到任务完成 |

执行超时范围 1 ms–24 h。状态为 `running/succeeded/failed/timed_out/cancelled/interrupted`。
Windows 退出码按无符号 32 位数返回；非零退出码保留在任务中。
stdout/stderr 分别保留最多 16 MiB，达到上限后继续排空管道并丢弃额外输出。
日志文件保留原始字节，可通过返回路径读取二进制输出或按程序实际代码页解码。

路径参数 `exe/cwd/path` 支持服务器绝对路径，转换为 Wine 默认 Z: 映射；也支持 Windows 路径或系统 EXE/DLL 名称。
`args` 内容保持字面值，涉及文件的参数应明确传 Windows 路径。传参采用 Windows CRT 转义规则；自行解析命令行的程序可能需要其专用参数约定。
`stdinBase64` 支持有限输入，写完即关闭 stdin。

## DLL 会话

`dll.load` 创建独立进程并保持加载状态。不同会话并行，同一会话的调用串行。
每实例最多 32 个 DLL 会话，全局最多 64 个运行中的 EXE 任务。

### 原生 C/C++

```js
await tools.wine.dll.load({instanceName: "legacy", dllName: "native", body: {
  kind: "native", path: "kernel32.dll", architecture: "x64"
}});
const result = await tools.wine.dll.call({instanceName: "legacy", dllName: "native", body: {
  member: "lstrlenW", callingConvention: "winapi", returnType: "i32",
  args: [{type: "utf16", value: "hello 世界"}]
}});
// result.body.value === 8
```

原生调用需要准确函数声明。支持 C ABI 导出（包括 C++ 中的 extern "C" 导出），调用约定为 `winapi/cdecl/stdcall`。
类型：`i8/u8/i16/u16/i32/u32/i64/u64/f32/f64/ptr`，返回类型还支持 `void`。
Windows BOOL 使用 `i32`；C/C++ bool 应按实际接口声明匹配大小。

- `utf8/utf16` 参数自动补 NUL，存储仅在本次调用内有效。
- `buffer` 参数接收 Base64 的 `value` 和可选容量 `size`；返回 `outputs:[{index,base64}]`。
- `ptr` 使用本会话返回的 `{handle: "h_..."}`，null 为 NULL；不会跨进程暴露可复用的地址数值。
- 返回指向本次临时参数内存的指针时，结果为 `{argument,offset,ephemeral:true}`，不能当作持久句柄。
- 原生库返回的其他指针由库决定生命周期。调用它对应的 free/close 函数后，再 `dll.release` 移除记录；release 本身不猜测内存释放器。
- 64 位整数使用十进制字符串传输，避免 JavaScript 精度损失。
- `dll.inspect` 列出最多 2000 个命名导出；导出名不能推断函数签名。

C++ 类对象、结构体按值、回调、可变参数等接口使用匹配编译器的 C ABI 包装 DLL/EXE。

### .NET

```js
await tools.wine.dll.load({instanceName: "legacy", dllName: "managed", body: {
  kind: "dotnet", path: "/data/MyLibrary.dll", runtime: "dotnet"
}});
await tools.wine.dll.inspect({instanceName: "legacy", dllName: "managed", typeName: "Example.Calculator"});
await tools.wine.dll.call({instanceName: "legacy", dllName: "managed", body: {
  typeName: "Example.Calculator", member: "Add",
  args: [{type: "i32", value: 1}, {type: "i32", value: 2}]
}});
```

- `runtime=dotnet` 使用随附的 Windows .NET 10 runtime，适用于现代 .NET 与兼容程序集。
- `runtime=framework` 使用 net48 helper，首次调用按需为 prefix 安装 Wine Mono。它是 .NET Framework 兼容实现；涉及特定 Microsoft CLR 行为的库需在该 prefix 安装相应原生 .NET Framework，并单独验证。
- `inspect` 返回公共类、构造函数和方法签名。参数类型除简写外可使用 CLR 完整类型名，重载按显式参数类型匹配。
- `operation=create` 调用构造函数，返回对象 handle；后续传 `target: handle` 调用实例方法。
- `operation=invoke/get/set` 调用方法或访问属性。Task 返回值会等待完成。
- 支持数值、`bool/string/bytes/object`；bytes 为 Base64。复杂结果返回会话内对象 handle，可显式 release。
- 方法异常作为调用错误返回，worker 保持可用；进程级崩溃则使整个会话失效。

### COM Automation

```js
await tools.wine.dll.load({instanceName: "legacy", dllName: "com", body: {
  kind: "com", classId: "Scripting.Dictionary"
}});
await tools.wine.dll.call({instanceName: "legacy", dllName: "com", body: {
  member: "Add", args: [{type: "string", value: "key"}, {type: "i32", value: 42}]
}});
await tools.wine.dll.call({instanceName: "legacy", dllName: "com", body: {
  operation: "get", member: "Item", args: [{type: "string", value: "key"}]
}});
```

classId 接受 ProgID 或 CLSID。组件必须在该 prefix 中正确安装/注册；位数必须匹配。
调用在 STA 线程执行，支持 IDispatch 方法及属性，返回的 COM 对象通过 handle 继续访问。
自定义 IUnknown/vtable 接口、事件订阅等使用专用包装组件。

### 释放与故障

`dll.list` 查看会话；`dll.release(instanceName,dllName,handle)` 释放对象引用；`dll.unload` 关闭整个 worker。
调用默认超时 30 s，超时销毁对应 worker，所有旧 handle 失效，需 unload 后重新 load。
其他会话保持运行。控制消息通过独立的本机 socket 传输，使用随机握手 token 和长度帧，不与程序 stdout/stderr 混合。
DLL 日志保存在 load 返回的 `logPath` 目录下。

## 安装、测试、部署

```sh
groot-cli jobs --job wine-install       # 用户目录中的 Wine/Xvfb/.NET，无需 sudo
groot-cli jobs --job wine-build         # 构建 modern 与 net48/x86/x64 helpers
groot-cli jobs --job wine-test          # 真实 Wine helper 与服务端集成测试
groot-cli jobs --job wine-helper-deploy # 测试并原子安装 helpers
groot-cli jobs --job wine-deploy        # 安装 helpers，完整测试并部署服务端
```

`config.env`：`WINE_HOME`（实例、任务）、`WINE_RUNTIME_ROOT`（Wine/.NET 工具链）、`WINE_HELPER_ROOT`（helper）。
Windows .NET 运行时/SDK 版本固定，下载后对照微软发布清单校验 SHA-512；Wine Mono MSI 对照 Wine 源码公布的 SHA-256。
当前 Wine 安装 job 面向本机 Ubuntu amd64，使用 apt 元数据解析依赖并解包到用户目录。

实现使用独立 Windows helper：原生函数由动态生成的 P/Invoke delegate 调用，托管程序集使用反射，COM 使用 CLR 的 IDispatch 互操作。
原生库不会加载进服务端 JVM。每个 worker 建立 Windows Job，进程退出时关闭它拥有的子进程。
