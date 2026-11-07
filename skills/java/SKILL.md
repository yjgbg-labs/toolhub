---
name: java
description: 编译、运行或排查Java与JVM项目时使用
---

# Java 环境

所有需要 JDK 的命令通过 mise 选择发行商和版本，不依赖系统 Java，也不全局修改 `JAVA_HOME` 或 `PATH`。

## 核心规则

- 命令格式为 `mise exec java@temurin-<major-version> -- <command> [args...]`。
- 禁止直接运行 `java`、`javac`、`jar`、`javadoc`、`jshell` 等 JDK 工具。
- Gradle、Maven、Scala CLI 及其他 JVM 构建工具也要通过 `mise exec` 执行。
- 优先使用仓库自带的 `./gradlew`、`./mvnw`，不要擅自改用全局 `gradle` 或 `mvn`。
- mise 只为子进程设置环境；不要额外执行 `export JAVA_HOME=...`，不需要激活交互式 shell。
- 普通命令在 `--` 后直接传参；需要管道或多个命令时使用 `-- sh -c '…'`。
- shell 字符串外层用单引号，将 `$JAVA_HOME` 留给选定 JDK 后的子进程展开。
- JDK 管理和启动由本机 mise 完成，不依赖 Groot HTTP 服务。
- 项目构建和部署仍遵循 `jobs` skill，通过 `groot-cli jobs --job <job>` 编排。

## 选择版本

按以下顺序确定 JDK 主版本：

1. 用户明确指定的版本。
2. 项目配置，例如 `config.env` 的 `JDK_VERSION`、`mise.toml`、`.java-version`、`.tool-versions`、Gradle Java Toolchain、Maven `maven.compiler.release` 或 CI 配置。
3. 项目文档中的构建要求。

不同来源冲突时，优先遵循项目构建配置，并向用户说明冲突。仍无法确定时先询问用户，不猜测默认版本。

显式写发行商，例如 `temurin-25`；项目固定具体版本时沿用该版本。
已有可信的 `mise.toml` 声明 Java 时可使用 `mise exec -- <command>`，不要重复覆盖项目版本。

## 安装

先用 `command -v mise` 和 `mise --version` 检查。尚未安装时按
<https://mise.jdx.dev/installing-mise.html> 安装到 `~/.local/bin/mise`。
`mise exec` 自动安装缺失的 JDK，也可先执行 `mise install java@temurin-25`。
默认安装目录为 `~/.local/share/mise/installs/java`，可通过 `mise where java@temurin-25` 查询。

## 常用命令

```bash
mise exec java@temurin-21 -- java --version
mise exec java@temurin-21 -- javac Main.java
mise exec java@temurin-21 -- java Main
mise exec java@temurin-17 -- ./gradlew test
mise exec java@temurin-21 -- ./mvnw test
```

需要管道、重定向或多个 shell 操作时：

```bash
mise exec java@temurin-21 -- sh -c 'java --version 2>&1 | tee java-version.log'
mise exec java@temurin-21 -- sh -c 'javac Main.java && java Main'
mise exec java@temurin-25 -- sh -c 'printf "%s\n" "$JAVA_HOME"; java --version; javac --version'
```

## 验证

执行构建或测试前，先确认实际版本：

```bash
mise exec java@temurin-<major-version> -- java --version
```

systemd 使用 mise 的绝对路径和显式版本，例如
`ExecStart=/home/user/.local/bin/mise exec java@temurin-25 -- java -jar /path/app.jar`。
部署时先准备并验证 JDK，再重启；服务中设置 `Environment=MISE_EXEC_AUTO_INSTALL=false`。

## 失败处理

- mise 找不到：确认 `~/.local/bin` 在 PATH 中，或使用安装后的绝对路径。
- 下载失败：保留原始错误，检查网络、发行商和版本；可用 `mise ls-remote java temurin-25` 查询。
- 项目配置未受信任：先阅读实际配置，再使用 `mise trust <config-path>`，不要盲目全局信任。
- JDK 缺失：在部署阶段运行 `mise install java@<version>`，不要让 systemd 启动阶段下载。
- 构建报告 Java 版本错误：通过 mise 再次验证版本，并检查构建工具自身的 Toolchain 配置。
- 命令找不到：确认 `--` 后传入命令和参数，并检查项目 wrapper 是否存在且可执行。
