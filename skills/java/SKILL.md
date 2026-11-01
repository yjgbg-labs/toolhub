---
name: java
description: 编译、运行或排查Java与JVM项目时使用
---

# Java 环境

处理 Java、JVM、Gradle 或 Maven 项目时使用本 skill。所有需要 JDK 的命令都必须通过 `groot-cli use-jdk` 指定主版本，不依赖系统 Java，也不全局修改 `JAVA_HOME` 或 `PATH`。

## 核心规则

- 命令格式固定为 `groot-cli use-jdk --version <major-version> --command '<command> [args...]'`。
- 禁止直接运行 `java`、`javac`、`jar`、`javadoc`、`jshell` 等 JDK 工具。
- Gradle、Maven 及其他 JVM 构建工具也要通过 `groot-cli use-jdk` 的 `--command` 执行。
- 优先使用仓库自带的 `./gradlew`、`./mvnw`，不要擅自改用全局 `gradle` 或 `mvn`。
- `groot-cli use-jdk` 只为子进程设置环境；不要额外执行 `export JAVA_HOME=...`。
- `--version` 是正整数主版本；`--command` 是一个完整的 `/bin/sh -c` 命令字符串，命令和参数一起放入引号中。
- 外层优先用单引号，将 `$JAVA_HOME` 等变量留给选定 JDK 后的子进程展开；带空格的路径在命令字符串内另加双引号。
- 需要可访问 Sprout HTTP 服务；此命令仅向 CLI 暴露，服务端生成脚本，由调用方本机选择或安装 JDK 并执行。

## 选择版本

按以下顺序确定 JDK 主版本：

1. 用户明确指定的版本。
2. 项目配置，例如 `.java-version`、`.tool-versions`、`mise.toml`、Gradle Java Toolchain、Maven `maven.compiler.release` 或 CI 配置。
3. 项目文档中的构建要求。

不同来源冲突时，优先遵循项目构建配置，并向用户说明冲突。仍无法确定时先询问用户，不猜测默认版本。

## 常用命令

```bash
groot-cli use-jdk --version 21 --command 'java --version'
groot-cli use-jdk --version 21 --command 'javac Main.java'
groot-cli use-jdk --version 21 --command 'java Main'
groot-cli use-jdk --version 17 --command './gradlew test'
groot-cli use-jdk --version 21 --command './mvnw test'
```

需要管道、重定向或多个 shell 操作时，直接写在同一个 `--command` 字符串中：

```bash
groot-cli use-jdk --version 21 --command 'java --version 2>&1 | tee java-version.log'
groot-cli use-jdk --version 21 --command 'javac Main.java && java Main'
groot-cli use-jdk --version 25 --command 'printf "%s\n" "$JAVA_HOME"; java --version; javac --version'
```

## 验证

执行构建或测试前，先确认实际版本：

```bash
groot-cli use-jdk --version <major-version> --command 'java --version'
```

首次使用某个版本时会从 Eclipse Adoptium 下载最新 Temurin GA JDK，校验 SHA-256 后缓存到 `$GROOT_HOME/jdks/temurin-<major-version>`；`GROOT_HOME` 默认 `~/.config/groot`，后续调用直接复用缓存。
支持 Linux 和 macOS（含 `Contents/Home` 布局）；首次安装需要 `curl`、`jq`、`tar` 和 `sha256sum` 或 `shasum`。
命令继承调用方当前目录、环境及标准输入/输出/错误，并返回子命令退出码。

## 失败处理

- 服务连接失败：用 `groot-cli health` 检查服务，并核对 `SPROUT_BASE_URL` 或 `SPROUT_PORT`。
- 下载失败：保留原始错误，检查网络、Adoptium 可用性和版本是否存在后重试。
- `unsupported operating system` 或 `unsupported CPU architecture`：不要绕过限制安装不匹配的二进制包。
- 缓存不完整或版本不符：检查报错目录中的 `release`、`bin/java` 和 `bin/javac`。
- 构建报告 Java 版本错误：再次通过 `groot-cli use-jdk --version <major-version> --command 'java --version'` 验证，并检查构建工具自身的 Toolchain 配置。
- 命令找不到：确认完整命令是 `--command` 的单个字符串参数，并检查项目 wrapper 是否存在且可执行。
