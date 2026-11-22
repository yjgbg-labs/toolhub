---
name: java
description: 编译、运行或排查Java与JVM项目时使用
---

# Java 环境

处理 Java、Scala 等 JVM 项目的编译、测试、运行与排障时使用本 skill。所有需要 JDK 的操作统一通过本机 `mise` 按发行商与版本隔离注入，不依赖系统全局 Java，也不持久修改全局 `JAVA_HOME` 或 `PATH`。

## 核心规则

- **显式选择发行商与版本**：通过 `mise` 指定具体发行商与主版本（格式如 `java@temurin-25`、`java@temurin-21`、`java@temurin-17`）为子进程注入环境；当项目目录已有受信任的 `mise.toml` 声明了 Java 工具版本时，直接沿用项目声明而不重复覆盖。
- **禁止裸调全局工具**：禁止直接裸跑系统路径下的编译器、运行器、归档器或构建工具；Gradle、Maven、Scala CLI 等构建入口同样在 `mise` 注入的 JDK 环境下运行，并优先使用项目自带包装器（如仓库根目录的 Gradle/Maven Wrapper）。
- **子进程隔离**：只通过 `mise` 为单次任务子进程提供 `JAVA_HOME` 与 `PATH`，不执行全局环境变量导出，也无需激活交互式 shell；构建编排与交付统一遵循 `mise` skill。

## 版本确定优先级

按以下顺序确定目标 JDK 版本，来源冲突时以项目构建配置为准并向用户说明，无法确定时先向用户确认而不猜测：

1. 用户在当前请求中明确指定的版本。
2. 项目配置文件（如 `config.env` 中的 `JDK_VERSION`、`mise.toml`、`.java-version`、`.tool-versions`、Gradle Toolchain、Maven `maven.compiler.release` 或 CI 配置）。
3. 项目文档注明的构建要求。

## 关键路径与服务运行参数

- **`mise` 默认路径**：`~/.local/bin/mise`。
- **JDK 默认安装根目录**：`~/.local/share/mise/installs/java`。
- **常驻服务约束**：systemd 服务单元须使用 `mise` 绝对路径与显式 `java@<发行商-版本>`，在部署阶段提前安装并校验目标 JDK；服务运行期必须设置环境变量 `MISE_EXEC_AUTO_INSTALL=false`，禁止服务启动阶段联网下载工具。

## 失败处理

- **找不到 `mise`**：检查 `~/.local/bin` 是否可用或改用绝对路径。
- **配置未受信任**：先人工审阅目标项目的 `mise.toml` 内容确认安全后，仅对该配置文件路径授权信任，禁止盲目全局信任。
- **JDK 缺失或下载失败**：保留原始报错信息，核对网络连通性、发行商名称与版本号，在部署或准备阶段完成安装。
- **构建报字节码或版本不匹配**：核对实际注入的 JDK 版本与构建工具自身的 Toolchain 设置是否一致。
