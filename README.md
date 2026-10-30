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
