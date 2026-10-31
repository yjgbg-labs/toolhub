# Groot CLI

项目只有三个组件，分别通过 HTTP/OpenAPI 协作：

```text
server/                 Scala/JVM HTTP 服务端
  src/                  业务与 HTTP 框架、Scala 测试
  test/                 服务部署检查
cli/                    Scala Native 命令行程序
  src/main/scala/       动态 OpenAPI 命令、同步 HTTP
  test/                 命令行与安装检查
plugin/                 TypeScript/Bun OpenCode V2 插件
  src/api/              插件自己的 OpenAPI、校验和传输实现
  test/                 API、插件、独立 bundle 与部署检查
AGENTS.md               项目开发与交付约定
skills/                 OpenCode skills，全局目录链接至此
config.env              JDK、安装路径及服务参数
jobs.yaml               四个任务，内联构建、测试和交付流程
```

CLI 与插件各自拥有实现与依赖，没有共享代码或 workspace 包引用。
CLI 的动态协议实现直接编入 Native 可执行文件；插件打成独立 JS bundle。
服务端使用 Scala 3.9.0 LTS 和 JDK 25，编译器及依赖版本集中在 `server/project.scala`。

## 四个任务

在项目根目录执行：

| 命令 | 操作 |
| --- | --- |
| `groot-cli jobs --job server-test` | Scala 服务端测试和服务部署检查 |
| `groot-cli jobs --job server-deploy` | 服务端测试、JAR 构建、user service 安装及健康检查 |
| `groot-cli jobs --job cli-deploy` | Native 编译、CLI/安装检查及安装 `groot-cli` |
| `groot-cli jobs --job pluging-deploy` | 插件依赖安装、类型检查、bundle 构建、测试及安装 OpenCode 插件 |

`pluging-deploy` 是任务的实际名称。每个组件独立部署，只处理自身制品。
需要只构建和验证时，可以执行 `CHECK_ONLY=1 groot-cli jobs --job <部署任务>`。
使用已有本地构建产物时，执行 `INSTALL_ONLY=1 groot-cli jobs --job <部署任务>`；
它跳过组件构建，`server-deploy` 的 `server-test` 依赖仍会执行。
两个开关同时设置时，只检查本地产物存在且非空，不安装；服务端依赖测试仍会执行。

服务端的负向测试会故意触发 HTTP 500，并捕获、断言预期的请求错误日志。
测试失败和非预期错误仍会正常输出；部署结果以测试汇总、`[jobs] fail` 和退出码为准。
JDK 25 下仍可能出现 `scala.runtime.LazyVals` 的 `Unsafe` 弃用警告：MUnit、Circe 等依赖的旧式 lazy val 字节码仍调用兼容实现，该警告不表示测试或部署失败。

## 本机交付约定

构建和安装均在本机完成，交付方式为构建、测试后直接安装到目标目录：

- CLI：将 `cli/dist/groot-cli` 移动到 `~/.local/bin/groot-cli`。
- 服务端：将 `server/dist/groot.jar` 安装到 `$SERVER_INSTALL_ROOT/server/groot.jar`，然后重启 user service 并检查健康状态。
- 插件：将 `plugin/dist/index.js` 直接安装为 `$OPENCODE_PLUGIN_DIR/sprout.ts`。

`config.env` 集中配置 JDK 和安装目标；服务端、CLI、插件分别部署。
无需为本机交付设置对象存储上传、下载或远程制品版本参数。

默认安装位置可通过同名环境变量覆盖：

- `SERVER_INSTALL_ROOT`：`~/.local/share/sprout-http`；`SERVER_SERVICE`：`groot-sprout-http.service`
- `CLI_BIN_DIR`：`~/.local/bin`，命令入口为其中的 `groot-cli` 普通可执行文件。
- `OPENCODE_PLUGIN_DIR`：`~/.config/opencode/plugins`，插件入口为其中的 `sprout.ts` 独立 bundle。

CLI 安装会移走 `cli/dist/groot-cli`，再次安装前需重新构建；服务端和插件保留 `dist` 产物。

开发环境需要 Bun、groot（提供 `use-jdk`）、JDK 25、Scala CLI 和 curl；
Native 编译还需要 Clang 和 libcurl 等开发库，
`cli-deploy` 会在需要时准备用户级工具链缓存。所有流程直接定义在 `jobs.yaml` 中。

## 使用

```sh
groot-cli --help
groot-cli health
groot-cli schedule new --name hello --body '{"command":["echo","hi"],"every":"1h"}'
groot-cli jobs --job build --parallel 2
```

服务端固定监听 `0.0.0.0:18080`，不读取端口环境变量；CLI 和插件默认连接
`http://127.0.0.1:18080`。客户端可用 `SPROUT_PORT` 覆盖默认端口，或通过
`SPROUT_BASE_URL` 指定完整的 HTTP(S) 地址。CLI 每次从服务端加载 `/openapi.json`，帮助中的程序名
取调用文件/软链接的原始名称。

CLI 对服务端声明的字符串参数 `cwd` 自动传入本机当前目录的绝对路径，并隐藏对应的命令行参数。
该机制适用于任意命令；用户无需提供 `cwd`，也不能通过参数或 `--input` 覆盖它。

请求体按 OpenAPI 选择格式：`application/json` 使用 JSON，`text/plain` 使用 UTF-8 原文。
`--body` 直接传对应内容，也支持 `@文件` 或 `-` 从本机文件/标准输入读取。
`--input` 始终是完整参数的 JSON 对象，纯文本请求体放在字符串 `body` 字段中。
CLI 与插件在同时声明两种格式时优先选择 JSON；纯文本请求体要求 string schema。

CLI 遇到成功响应的 `Content-Type: text/event-stream` 时，会像 `tail -f` 一样
持续打印每个 SSE 事件的 `data` 内容，并立即刷新控制台，支持管道与重定向。
JSON 数据保持原文，多行 `data:` 合并为多行文本；心跳注释和 `event`、`id`、
`retry` 字段不打印。流结束时退出，按 `Ctrl+C` 可中断；连接断开后不自动重连。
命令请求不设置总传输时限，以支持长时间、间歇性事件流；OpenAPI 发现仍限时 30 秒。

服务端可返回 `Shell("...")`（`application/x-sh`）。`groot-cli` 完整接收成功响应后，
在本机用 `/bin/sh -c` 执行脚本，继承当前目录、环境及标准输入/输出/错误，
并使用脚本的退出码退出。示例见 [服务端 Shell 返回类型](server/README.md#client-side-shell-scripts)。

OpenCode 插件在 setup 时发现命令并注册 Code Mode 工具及 namespace，返回清理函数。
支持通过插件 options 配置 `baseUrl`、`schemaUrl`、`headers`、`tokenEnv`、`timeoutMs`。
安装后的入口由 OpenCode 自动发现，文件变更自动加载。

### 客户端暴露范围

`framework.rpcDsl.*` 提供 `exposeOn(cli, codemode, graaljs)`，可在 `commands` 或
`command` 中声明，也可在根 `httpApp` 中设置。默认三端可用，分组与子命令的限制取交集：

```scala
commands("reports") {
  exposeOn(cli, codemode, graaljs)
  command[String]("internal") {
    exposeOn(graaljs)
    handle { IO.pure("GraalJS only") }
  }
}
```

OpenAPI 的 `x-expose-on` 标注每个操作最终的客户端集合；CLI/Code Mode 据此过滤命令及空分组，
GraalJS 直接按服务端声明构建工具树。仅暴露给 GraalJS 的命令不开放外部 HTTP 路由。
继承规则与协议说明见 [客户端暴露范围](server/README.md#client-exposure)。

### GraalJS 脚本任务

```sh
groot-cli graaljs start --body 'console.log("hello")' --cpu 2s --timeout 30s
groot-cli graaljs list
groot-cli graaljs status --id gjs_xxx
groot-cli graaljs logs --id gjs_xxx
groot-cli graaljs logs-follow --id gjs_xxx
groot-cli graaljs cancel --id gjs_xxx
groot-cli graaljs notify-on-finish --id gjs_xxx
```

`start` 必须指定 `cpu` 和 `timeout`，支持正整数加 `ms/s/m/h/d`。`cpu` 是 GraalVM
累计 guest CPU 时间限额；`timeout` 是开始执行后的墙钟超时，包含 Context 初始化、不包含排队。
`--body` 直接传入 JavaScript 源码，通过 `POST /graaljs/start?cpu=2s&timeout=30s` 的
`text/plain` UTF-8 请求体发送，提交后保留源码快照。返回 JSON 包含任务 ID、状态、时间限制和日志路径。
源码必须非空白，换行、引号和 Unicode 原样保留；错误定位使用 `<任务 ID>.graal.js`。

- 服务全局共享一个 Engine 和 Native Image isolate，`engine.MaxIsolateMemory=64MB`（64 MiB）。
- FIFO 队列每次原子预留 4 MiB 调度额度，额度不足则等待；最多同时运行 16 个任务。
  这是预算记账，不是对实际空闲堆的检测或物理内存划拨。
- 每次执行新建 Context，并设置 `sandbox.MaxHeapMemory=4MB`。它限制 JS 保留对象，是可能
  短暂超出的软限额；运行时、GC/JIT 等仍占用共享 isolate 的堆。调度额度不保证每个任务都能达到其限额。
- 使用 `SandboxPolicy.ISOLATED`；Context 隔离脚本状态。宿主文件、网络、Java 类、线程和进程访问受限。
  支持 JavaScript、顶层 `await`、`console.log/info/warn/error` 和 `print`，执行结束后释放 Context。
- 队列最多等待 1024 个任务，满时返回 429；脚本大小上限 1 MiB。任务输出上限 8 MiB，超限记为
  `output_limit`；错误和最终摘要另外保留。

任务状态为 `queued → running → succeeded/failed`，运行时取消先进入 `cancelling`，Context
关闭后才变为 `cancelled` 并归还额度；排队中的任务直接取消。`cancel` 幂等。
`list` 只返回 `queued/running/cancelling`，结束任务仍能按 ID 查询。
CPU 超限、超时、内存超限和脚本异常分别记录明确的错误码。

日志写入 `$SPROUT_HOME/graaljs/logs/<id>.log`；`SPROUT_HOME` 默认是 `~/.config/sprout`。
`logs` 返回当前快照字符串；`logs-follow` 按行通过 SSE 回放并追踪文件，结束后输出摘要并关闭流。
UTF-8 字符跨写入块也能正确处理；断开日志连接只停止订阅，任务继续运行。

```text
你好
[graaljs] state=succeeded cpu=18.420ms elapsed=73.810ms
```

`cpuTimeMs` 是专用执行线程从启动到 Context 清理完成的 CPU 时间，包含该线程上的初始化和宿主回调，
不包含后台 GC/JIT 线程；它与 GraalVM 的 guest CPU 限额计量范围有所不同。
`elapsedTimeMs` 是提交入队以来的总耗时，`runTimeMs` 是实际运行阶段耗时，正常运行中用单调时钟计时。
未执行或异常重启后无法恢复的 CPU 数据为 `null`。

任务状态、限制、时间、错误和源码快照保存到 `$SPROUT_HOME/graaljs/tasks.sqlite3`，SQLite 使用 WAL。
退出时终止活动任务；启动时将遗留的未完成任务标记为 `interrupted`，不自动重跑。
日志和历史记录保留在本地；同一数据目录只允许一个服务实例持有。
`notify-on-finish` 已注册，当前返回 `501 not_implemented`。

#### 命令树 SDK

每个任务的 `tools` 对象自动包含当前 Scala server 暴露给 `graaljs` 的命令树，与 Code Mode 的命名、
输入分组和响应结构一致。命令声明增删后，SDK 随服务启动生成，无需维护独立的方法列表。
工具树由 Scala `ProxyObject` / `ProxyExecutable` 直接注入每个 Context；Promise、JSON 对象和错误
使用该 Context 的原生构造器创建，不加载额外的 JavaScript SDK 包装代码。
例如 `schedule.list` 映射为 `tools.schedule.list()`，带连字符的方法使用方括号。
参数定义沿用 `/openapi.json`；GraalJS 内不提供 `search()`。

```sh
groot-cli graaljs start --cpu 2s --timeout 30s --body '
  const [health, jobs] = await Promise.all([
    tools.health(),
    tools.schedule.list()
  ]);
  console.log(health.body);
  console.log(JSON.stringify(jobs.body));
  // 带参数：await tools.schedule.log({query: {name: "example", lines: 20}})
  // 原始文本请求体：await tools.graaljs.start({query: {cpu: "1s", timeout: "5s"}, body: "console.log(42)"})
'
```

- 所有方法返回 `Promise<{status, headers, body}>`。参数按 `query`、`body` 等位置分组，
  无参数命令可省略输入。JSON 响应解码为对象；文本、Shell 和 SSE 响应作为字符串返回；空响应为 `null`。
  Shell 内容只返回文本，不自动执行；SSE 会等到流结束后返回完整文本。
- 非 2xx 响应拒绝 Promise，错误为 `ApiError`，包含 `operationId` 和 `response`（同样的响应结构）。
  调用经过本进程内的 HTTP 分发、参数校验和中间件，不建立额外的网络连接。
- 源码作为 async 函数体执行，支持顶层 `await` / `return` 和 `Promise.all` 并行调用。
  顶层 Promise 完成后结束任务；返回值不写入日志，需要输出时使用 `console.log`。
  任务结束、超时或取消时会取消尚未完成的调用，并等待它们的清理完成后释放任务额度。
- 每个任务最多 64 个待完成 SDK 调用；每次请求 JSON、响应正文及序列化后的响应均不超过 1 MiB。
  等待命令不占用 guest CPU，但仍计入墙钟超时。被调用命令的 JVM/外部工作不计入 JS 的 4 MiB 堆限制。
- `tools` 授予脚本与这些服务端命令相同的能力，包括修改定时任务、提交或取消其他 GraalJS 任务。
  它不是只读接口。独立提交的子任务具有自己的生命周期；父任务结束不会自动取消已提交的子任务。
  `fetch`、文件系统、Java 反射等通用宿主接口仍未开放。

当前打包目标为 Linux AMD64，使用 GraalVM `25.4.4.1.1` Community isolate 和 SQLite JDBC
`3.54.1.0`，原生资源随 assembly JAR 交付；JDK 25 启动参数启用 `--enable-native-access=ALL-UNNAMED`。

### SQLite 环境与 SQL

`sqlite` 整组声明 `exposeOn(cli, codemode, graaljs)`，三端共享服务端的数据库与环境注册表。

```sh
groot-cli sqlite env list
groot-cli sqlite env add --name scratch
groot-cli sqlite env import --name existing --path /absolute/server/path/data.sqlite3
groot-cli sqlite execute --env scratch --sql "CREATE TABLE notes(id INTEGER PRIMARY KEY, text TEXT)"
groot-cli sqlite execute --env scratch --sql "INSERT INTO notes(text) VALUES ('hello'); SELECT * FROM notes"
groot-cli sqlite env delete --name scratch
```

- `add` 创建空数据库，返回 `{name, path, managed: true}`；同名注册返回 409，不覆盖已有环境。
- `import` 原地引用服务端已有的绝对文件路径，返回 `managed: false`。注册时只读校验，之后的
  查询能看到外部程序的更新，包括尚未 checkpoint 的 WAL 数据；SQL 写入也作用于原文件。
- `delete` 只删除名称绑定，保留数据库文件。正在执行的调用可以完成；后续按旧名称访问返回 404。
  可以使用返回过的 `path` 重新导入数据库；再次 `add` 同名环境会创建一个新的空文件。
- 环境名允许 Unicode，最多 128 个字符。托管文件使用随机文件名，不将环境名解释为路径。
- 注册表为 `$SPROUT_HOME/sqlite/environments.sqlite3`，托管数据库位于
  `$SPROUT_HOME/sqlite/databases/`。注册表持久化，同一目录只允许一个服务实例持有。
  已注册的数据库文件如果消失，执行报错，不会自动创建替代文件。

Code Mode 和 GraalJS 使用相同的调用：

```js
await tools.sqlite.env.add({query: {name: "scratch"}});
const result = await tools.sqlite.execute({query: {
  env: "scratch",
  sql: "SELECT 42 AS answer, 'hello' AS text"
}});
console.log(result.body.results[0]);
// {columns: ["answer", "text"], rows: [[42, "hello"]], changes: 0}
```

一次 `execute` 可包含多条 SQL，在同一个独立连接上按顺序执行，返回
`{results: [{columns, rows, changes}, ...]}`。支持注释、字符串中的分号、触发器与 `RETURNING`。
`columns` 保存列顺序及重复列名，`rows` 中每行按相同顺序排列；非查询语句的列与行为空。
`changes` 为该语句造成的行变更数，包含触发器，不是整个事务提交后的净变更数。

SQLite 默认逐语句自动提交。需要原子执行时，在同一次调用中使用 `BEGIN; ...; COMMIT;`；
首次错误会停止后续语句，已自动提交的数据保留，未提交事务在连接关闭时回滚。
临时表、连接级 PRAGMA 和事务状态只在本次调用内有效。

结果中的 NULL、文本、普通整数和浮点数使用对应 JSON 值。超出 JavaScript 安全整数范围的
整数用十进制字符串返回；BLOB 用 `{base64: "..."}` 返回，无穷浮点值用字符串返回。
SQL 上限为 1 MiB，超额返回 400；整次调用结果最多 10000 行、512 KiB，结果超额返回 413。可用 `LIMIT`
和选择所需列控制结果大小。取消调用会中断原生 SQLite 查询，并等待工作线程和连接清理完成。
数据库锁等待最多 5 秒，busy/locked 返回 503，约束冲突返回 409，普通 SQL 错误返回 400。

### 本地 jobs

在项目目录执行 `groot-cli jobs --job <任务>`；服务端根据隐式传入的 cwd 读取 `jobs.yaml`，`--parallel` 默认 `5`。
`groot-cli jobs --help` 显示参数说明。

服务端用 Scala 读取单个 YAML 文件、解析并校验完整依赖图，
再将目标及其传递依赖编译为 `Shell` 响应。客户端执行生成的 `/bin/sh` 脚本，使用 Linux
常用工具 `mktemp`、`mkfifo`、`date` 和 `rm`；无需 Bun 或客户端 YAML 解析器。
配置路径是服务端可访问的 `<cwd>/jobs.yaml`，jobs 不使用请求体。

```yaml
version: 1
jobs:
  - name: install
    run: echo preparing
  - name: test
    needs: [install]
    run: echo testing
  - name: build
    needs: [install, test]
    run: |
      echo building
```

- 仅支持一个 `version: 1` 文件；`imports` 和 namespace 不受支持。
- 执行目标及其传递依赖，共享依赖只执行一次；就绪任务按并发上限运行。
- 每个 `run` 用 `/bin/sh -e -c` 执行，继承 CLI 的当前目录、环境和标准输入输出。
- Shell 通过后台进程和 FIFO 完成通知调度；任意任务结束即可释放并发名额。
- 保留 `[jobs] start/done/fail/skip` 日志。失败跳过所有下游任务，独立分支继续完成；整体成功退出 `0`，失败退出 `1`。
- 重复名称、无效配置、缺失依赖和循环由服务端返回 HTTP 400，客户端不执行任何任务。
