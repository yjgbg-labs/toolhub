# SDK 分层与客户端实现取舍

## 发布边界

```text
协议版本 SDK_VERSION
  sdk-core (JVM / Scala.js / Native)
    ├── OpenAPI 加载、Catalog、输入校验、工具输入/输出 JSON Schema
    ├── 动态 Call / DynamicClient
    └── HTTP / JSON / Executor；按需搭配 transport 和 effect 适配器
        ↑                     ↑
  sdk-generator            cli (Native)
  (JVM / JS / Native)     运行时发现业务命令
        │
        └── 生成 Models / Calls / Client 源码
                   │
业务版本 SDK_CLIENT_VERSION
             typesafe-sdk → sdk-core
```

- core、generator、CLI 都不携带业务端点定义；`openapi.json` 快照只用于生成业务
  SDK和测试。业务服务新增符合现有协议的端点后，CLI 下一次调用即可发现。
- 发布协议库使用 `sdk-publish-core`。非 SNAPSHOT 版本不可覆盖。
- 发布业务 SDK 使用 `sdk-publish-client`；这条任务图没有协议库发布或
  CLI 构建任务，业务 SDK 的 module、package、版本和生成目录可独立指定。
- Native CLI 单独上传到 S3，以内容 SHA-256 固定版本。`cli-deploy` 只下载并校验
  固定制品。server/business SDK 发布不决定 CLI 版本。
- 生成器实现是可跨平台发布的 Scala 库。Bun 代码仅承担文件/HTTP I/O 和调用
  Scala.js 导出函数；JVM/Native 有独立文件入口，生成规则只有一份。

“只发布一次”针对业务演进：兼容协议内新增业务不要求重新发布通用客户端。
如果引入新的传输、认证交互、参数序列化或 schema 语义，仍需要升级协议工具链。

## CLI：Scala Native 与当前 TypeScript/Bun

| 维度 | Scala Native | 现有 TypeScript/Bun |
| --- | --- | --- |
| 业务解耦 | 动态 core 读取 OpenAPI，新增业务无需编译 CLI | 已经动态读取 OpenAPI，也无需编译 CLI |
| 安装 | 原生可执行文件；需要对应系统的 libcurl 等动态库 | JS bundle + Bun 运行时 |
| 操作系统/架构 | 每个目标要构建、链接、验证制品；不是跨平台通用二进制 | JS bundle 可复用，安装适配平台的 Bun |
| 冷启动/内存 | 无 JVM/JS 引擎启动，当前实测见下方基准 | 要启动 Bun，并为 catalog 编译 AJV 校验器 |
| JSON 数字 | Circe Jawn + Long/BigInt/BigDecimal 保持精度 | JSON.parse 使用 JS Number；超出安全整数范围存在限制 |
| schema 校验 | 当前 core 实现协议所需的常用约束，不是完整 JSON Schema 校验器 | AJV 2020 + formats 覆盖更广、错误详情更丰富 |
| HTTP | sttp/libcurl，支持 TCP/TLS/Unix Socket；系统依赖较多 | Bun fetch 原生支持 Unix Socket，接入简单 |
| 构建调试 | Scala 编译、Native 链接、C 工具链，构建时间较长 | Bun/TS 构建快，JS 生态与调试工具直接可用 |
| 代码复用 | 和 JVM/JS 客户端、生成器共用协议实现 | 和现有 TS 插件直接共享动态 API 客户端 |
| 类型安全 | 动态命令的业务输入仍在运行时校验 | 同样在运行时校验 |

重要：把动态 CLI 换成 Scala 并不会自动获得“业务调用的编译期类型安全”。
这个能力属于生成后的 typesafe SDK；Native CLI 的主要价值是部署、启动开销和
统一协议实现。

### 可复现基准

运行 `groot jobs cli-benchmark`，输出在
`scala-sdk/dist/cli-benchmark.json`。比较同一 OpenAPI 文档、同一 Unix Socket fixture
下的 `--help`，每次启动新进程，5 次预热后交错采样 20 次，报告耗时中位数与
`/usr/bin/time` 的进程峰值 RSS 中位数。Native 当前是 debug 链接模式。

这是包含 schema 加载、解析和帮助渲染的 CLI 启动基准，不是网络吞吐量或所有
业务操作的性能结论。JS bundle 大小必须和 Bun 运行时分开看；Native 文件大小
也不包含系统 libcurl 等共享库。

2026-10-25，本机 Linux x86-64、20,245 字节 OpenAPI fixture 的一次结果：

| 指标 | Scala Native（debug） | TypeScript / Bun 1.3.14 |
| --- | ---: | ---: |
| 新进程 `--help` 耗时中位数 | 12.54 ms | 80.14 ms |
| 峰值 RSS 中位数 | 21.7 MiB | 70.3 MiB |
| 程序制品 | 9.79 MiB | JS 301 KiB，另需 Bun 88.5 MiB |

这是当前实现的测量，包含两端不同的校验实现成本；不能据此把差异全部归因于
语言或运行时。更大的 schema、网络延迟、release 优化和宿主配置会改变结果。

## Code Mode 插件：可以用 Scala.js 写吗？

**可以。** OpenCode V2 加载的是 JavaScript 插件模块，不要求源代码必须是
TypeScript。Scala.js 可以输出 ESM，导出符合插件接口的对象，并在编译期通过
Maven 依赖 `sprout-sdk-core_sjs1_3`。最终交付的是链接后的 JS/npm 包，不是把
Maven JAR 直接交给 OpenCode。

典型接入路径：

1. `@JSImport` / facade 接入 `@opencode/plugin`，或保留很薄的 JS/TS `Plugin.define`。
2. setup 阶段通过 JS Fetch 适配器执行 `Catalog.discover()`。
3. 使用 `Catalog.operations`、`inputSchema`、`outputSchema` 和 group descriptions
   注册工具与 namespace。
4. tool execute 通过 core 构造动态调用，将 Future/IO 转成 `js.Promise`，返回
   OpenCode 要求的 `{content, output, metadata}`。
5. 保存并 dispose 注册；将执行上下文的 `AbortSignal` 传入实际 fetch。
6. Bun Unix Socket 是非标准 fetch 扩展，需要显式 JS facade/适配器。浏览器的
   fetch 和普通 Scala.js sttp Fetch backend不会自动获得 Unix Socket 支持。

当前插件的 `plugin/src/index.ts` 与 `tools.ts` 合计约 64 行，主要是选项处理、
加载 catalog、注册和清理。大部分可复用的协议逻辑在它引用的 TS API 客户端中。
本次已经提供 Scala.js core 的动态调用和独立 JSON Schema 导出；完整的 OpenCode
Scala.js 插件尚未实现、未替换当前运行中的插件。

| 维度 | 基于 sdk-core 的 Scala.js 插件 | 当前 TypeScript 插件 |
| --- | --- | --- |
| 协议复用 | 与 Native CLI/JVM/生成器共用 Catalog、参数序列化与 schema 逻辑 | 与 TS CLI 共享 API 实现，与 Scala core有重复逻辑 |
| 宿主接入 | 需要 facade、Promise/Effect 与 JS 对象转换 | 直接使用 OpenCode 的 TS API、Bun fetch 和类型定义 |
| 体积与启动 | 链接 Scala/Circe 所需部分，通常有额外体积；可 fullOpt/DCE | 现有 bundle 较小，插件本身很薄 |
| 调试维护 | 跨 Scala/JS 边界和 source map，API facade需要跟随宿主升级 | 和宿主文档、示例、生态一致，定位问题更直接 |
| 并发与取消 | 可以用 Future/Cats/ZIO，但必须正确桥接 AbortSignal | Promise/AbortSignal 可直接传递；现有 execute 尚未传 signal |
| 数值精度 | core 内部保持精度；转成普通 JS JSON 对象时仍可能丢失 | JS Number 的安全整数限制 |
| 性能收益 | 仍运行于同一个 JS 引擎；不会获得 Native 二进制的启动收益 | 长驻宿主中的插件，通常 HTTP/工具执行耗时更重要 |
| 发布 | 编译时 Maven，运行时部署 ESM/npm 制品 | 直接构建 JS/npm 制品 |

**建议**：CLI 用 Native；插件优先采用“薄 TypeScript 宿主适配层 + Scala.js
sdk-core”，将协议逻辑统一起来。若目标是所有业务与集成源码统一 Scala，完整
Scala.js 插件也可行，但它主要换来语言统一，不应预期天然更快。先复用 core再
决定是否迁移约 64 行宿主胶水代码，投入与收益更匹配。

依据：OpenCode V2 [插件文档](https://opencode.ai/v2/docs/build/plugins)，尤其是
插件 setup/cleanup、tool transform、Code Mode 选项和 AbortSignal 约定。
