# Jobs

在项目根目录运行 `groot jobs <name>`。当前共 **29 个 job**；`--parallel N`
控制独立 job 的并发数，合并后的三平台检查和发布在单个 job 内顺序执行。

## 常用操作

```sh
groot jobs run                  # 本地运行服务端
groot jobs test                 # 整个项目的测试与集成检查
groot jobs build                # 服务端、TS 客户端/插件、Native CLI
groot jobs sdk-generate         # 从 OpenAPI 生成业务 SDK
groot jobs sdk-test             # SDK / generator / Native CLI 全部检查
SDK_CLIENT_VERSION=0.3.0 groot jobs sdk-publish-client
groot jobs cli-deploy           # 下载并安装固定版本的 Native CLI
```

## 全部入口

| 组 | Job | 作用 |
| --- | --- | --- |
| 开发 | `run` | 本地运行服务端 |
| 开发 | `test` | 服务端、TS、部署脚本、SDK 三平台、生成器、CLI 和集成测试 |
| 开发 | `build` | 构建服务端制品、TS bundle 和 Native CLI |
| 服务端 | `server-test` | Scala 服务端测试 |
| 服务端 | `server-build` | 测试后生成服务端 JAR 与框架 Maven 制品至 `server/dist` |
| TypeScript | `ts-test` | 一次类型检查，运行 CLI、插件、部署及迁移脚本测试 |
| TypeScript | `ts-build` | 测试后构建 CLI、API 和插件 bundle |
| 集成 | `integration-test` | 临时服务端与 TS CLI/插件集成测试 |
| 交付 | `package` | 集成测试后打包应用，生成内容哈希及 SHA-256 |
| 交付 | `deploy` | 打包、上传 S3 并部署服务端和插件，安装固定版本 Native CLI |
| 交付 | `server-deploy` | 下载并部署配置中固定版本的服务端 |
| 交付 | `plugin-deploy` | 下载并部署配置中固定版本的插件 |
| Native CLI | `cli-build` | 构建 Native CLI |
| Native CLI | `cli-test` | 动态命令、HTTP、Unix Socket 等 CLI 检查 |
| Native CLI | `cli-release` | 构建、测试、上传 S3，再下载校验并安装本次制品 |
| Native CLI | `cli-deploy` | 安装 `SDK_CLI_SHA256` 固定制品 |
| Native CLI | `cli-benchmark` | Native 与 TS/Bun 的启动基准 |
| 生成器 | `generator-build` | 构建业务生成使用的 Scala.js 入口 |
| 生成器 | `generator-test` | 生成器检查、夹具生成、JVM/JS/Native 输出一致性 |
| SDK | `sdk-schema` | 从服务端刷新 OpenAPI 快照 |
| SDK | `sdk-generate` | 按配置生成业务 Models、Calls、Client |
| SDK | `sdk-test` | 三平台 SDK 检查，加生成器、HTTP 和 CLI 检查 |
| SDK | `sdk-http-test` | JVM/JS HTTP 与 Native CLI 的 TCP/Unix Socket 集成 |
| SDK 发布 | `sdk-publish-core` | 三平台协议库、适配器及生成器发布至 Maven Local 并校验 |
| SDK 发布 | `sdk-publish-client` | 生成业务 SDK 并单独发布三个平台至 Maven Local |
| SDK 发布 | `sdk-check-published` | 校验已有 Maven 制品，并运行独立消费者 |
| 准备 | `install` | 安装根目录和 Scala SDK 的 Bun 开发依赖 |
| 准备 | `native-tools` | 共享的 Native 工具链准备，供依赖图自动调用 |
| 维护 | `schedule-migrate` | 执行旧调度数据迁移，会修改本机调度配置及服务 |

## 发布关系

- 业务变更：`sdk-schema` → `sdk-publish-client`。后者依赖生成器构建和业务源码
  生成，不触发协议库发布或 Native CLI 构建。
- 协议工具链发布：`sdk-publish-core`。保持非 SNAPSHOT 版本不可覆盖的校验。
- 首次 Maven Local 初始化：依次执行 `sdk-publish-core`、`sdk-publish-client`、
  `sdk-check-published`。最后一步只消费已有制品。
- Native CLI：`cli-release` 安装本次新制品；`cli-deploy` 安装配置中固定制品。
  发布后的 SHA-256 位于 `scala-sdk/dist/native-release.env`，可用于更新固定版本。
- `test` 不上传、部署或发布 SDK。应用的服务端/插件打包与 Native CLI 的 S3
  制品保留各自的发布周期。

## 原 49 个 job 的合并与更名

| 原入口 | 当前入口 |
| --- | --- |
| `install`、`scala-sdk-install` | `install` |
| `client-test`、`deployment-test` | `ts-test` |
| `client-build` | `ts-build` |
| `server-build`、`server-package` | `server-build` |
| `scala-sdk-native-tools` | `native-tools` |
| `scala-sdk-schema`、`scala-sdk-generate` | `sdk-schema`、`sdk-generate` |
| `scala-sdk-generator-js` | `generator-build` |
| `scala-sdk-generator-test`、`scala-sdk-generator-platform-test`、`scala-sdk-generator-jvm`、`scala-sdk-generator-native`、`scala-sdk-fixtures` | `generator-test` |
| `scala-sdk-test`、`scala-sdk-test-jvm`、`scala-sdk-test-js`、`scala-sdk-test-native` | `sdk-test` |
| `scala-sdk-http-jvm`、`scala-sdk-http-js`、`scala-sdk-integration-test` | `sdk-http-test` |
| `scala-sdk-native-cli`、`scala-sdk-native-cli-test` | `cli-build`、`cli-test` |
| `scala-sdk-native-cli-upload`、`scala-sdk-native-cli-install` | `cli-release`（上传并安装） |
| `scala-sdk-cli-benchmark` | `cli-benchmark` |
| `scala-sdk-publish-local` 及其 `-jvm` / `-js` / `-native` | `sdk-publish-core` |
| `scala-sdk-publish-client-local` 及其 `-jvm` / `-js` / `-native` | `sdk-publish-client` |
| `scala-sdk-test-installed` | `sdk-check-published` |
| `scala-sdk-published-test` | 显式依次执行两个发布 job 与 `sdk-check-published` |

其余应用运行、集成、打包、部署及迁移入口沿用原名。原平台级 job 的执行步骤
已合并到对应 job 或 `scala-sdk/scripts/test-platforms.sh`；不再维护旧名称别名。
