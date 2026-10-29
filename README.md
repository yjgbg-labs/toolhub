# Sprout

项目只有三个组件，分别通过 HTTP/OpenAPI 协作：

```text
server/                 Scala/JVM HTTP 服务端
  src/                  业务与 HTTP 框架、Scala 测试
  scripts/              编译与服务部署
  test/                 服务部署检查
cli/                    Scala Native 命令行程序
  src/main/scala/       动态 OpenAPI 命令、同步 HTTP、Unix Socket
  scripts/              Native 编译、制品上传与安装
  test/                 命令行与安装检查
plugin/                 TypeScript/Bun OpenCode V2 插件
  src/api/              插件自己的 OpenAPI、校验和传输实现
  scripts/              bundle 构建与插件安装
  test/                 API、插件、独立 bundle 与部署检查
config.env              JDK、S3 地址及安装路径
jobs.yaml               四个任务入口
```

CLI 与插件各自拥有实现与依赖，没有共享代码或 workspace 包引用。
CLI 的动态协议实现直接编入 Native 可执行文件；插件打成独立 JS bundle。

## 四个任务

在项目根目录执行：

| 命令 | 操作 |
| --- | --- |
| `groot jobs server-test` | Scala 服务端测试和服务部署检查 |
| `groot jobs server-deploy` | 服务端测试 → JAR 构建 → S3 上传 → 下载校验 → user service 安装及健康检查 |
| `groot jobs cli-deploy` | Native 编译 → CLI/安装检查 → S3 上传 → 下载校验 → 安装 `sprout` 和 `sprout-native` |
| `groot jobs pluging-deploy` | 插件依赖安装 → 类型检查 → bundle 构建 → 测试 → S3 上传 → 下载校验 → 安装 OpenCode 插件 |

`pluging-deploy` 是任务的实际名称。每个组件独立部署，只处理自身制品。
需要只构建和验证时，可以执行 `CHECK_ONLY=1 groot jobs <部署任务>`。

## 制品和配置

三个部署任务都使用内容 SHA-256 作为版本，在 S3 上分别存放：

```text
server/<sha256>/groot.jar
native-cli/<sha256>/sprout-native
plugin/<sha256>/index.js
```

每个制品都有同名 `.sha256` 文件；安装时从 S3 下载并校验内容，再激活该版本。
发布版本记录在各自 `dist/release.env`。`config.env` 中的默认位置：

- S3 下载：`http://s3.yjgbg.lab:9000/public/sprout-http`
- 服务端：`~/.local/share/sprout-http`；user unit：`groot-sprout-http.service`
- CLI：`~/.local/share/sprout-native`；命令入口：`~/.local/bin/sprout`
- 插件：`~/.local/share/sprout-plugin`；入口：`~/.config/opencode/plugins/sprout.ts`

可通过同名环境变量覆盖 S3 地址和安装路径。开发环境需要 Bun、groot、JDK 25、
Scala CLI；Native 编译还需要 Clang 和 libcurl 等开发库，`cli/scripts/native.sh`
会在需要时准备用户级工具链缓存。

## 使用

```sh
sprout --help
sprout health
sprout schedule new --name hello --body '{"command":["echo","hi"],"every":"1h"}'
```

CLI 和插件默认连接 `unix://$XDG_RUNTIME_DIR/sprout-http/http.sock`，未设置
`XDG_RUNTIME_DIR` 时使用 `$HOME/.local/run/sprout-http/http.sock`。
`SPROUT_BASE_URL` 可指定 HTTP(S) 或 Unix Socket 地址。CLI 每次从服务端加载
`/openapi.json`，帮助中的程序名取调用文件/软链接的原始名称。

OpenCode 插件在 setup 时发现命令并注册 Code Mode 工具及 namespace，返回清理函数。
支持通过插件 options 配置 `baseUrl`、`schemaUrl`、`headers`、`tokenEnv`、`timeoutMs`。
安装后的入口由 OpenCode 自动发现，文件变更自动加载。
