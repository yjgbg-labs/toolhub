---
name: java
description: 编译、运行或排查Java与JVM项目时使用
---

# Java 环境

处理 Java、JVM、Gradle 或 Maven 项目时使用本 skill。所有需要 JDK 的命令都必须通过 `groot use-jdk` 指定主版本，不依赖系统 Java，也不全局修改 `JAVA_HOME` 或 `PATH`。

## 核心规则

- 命令格式固定为 `groot use-jdk <major-version> -- <command> [args...]`。
- 禁止直接运行 `java`、`javac`、`jar`、`javadoc`、`jshell` 等 JDK 工具。
- Gradle、Maven 及其他 JVM 构建工具也要放在 `groot use-jdk` 后执行。
- 优先使用仓库自带的 `./gradlew`、`./mvnw`，不要擅自改用全局 `gradle` 或 `mvn`。
- `groot use-jdk` 只为子进程设置环境；不要额外执行 `export JAVA_HOME=...`。
- `--` 之后的参数原样属于目标命令，不能省略分隔符。

## 选择版本

按以下顺序确定 JDK 主版本：

1. 用户明确指定的版本。
2. 项目配置，例如 `.java-version`、`.tool-versions`、`mise.toml`、Gradle Java Toolchain、Maven `maven.compiler.release` 或 CI 配置。
3. 项目文档中的构建要求。

不同来源冲突时，优先遵循项目构建配置，并向用户说明冲突。仍无法确定时先询问用户，不猜测默认版本。

## 常用命令

```bash
groot use-jdk 21 -- java --version
groot use-jdk 21 -- javac Main.java
groot use-jdk 21 -- java Main
groot use-jdk 17 -- ./gradlew test
groot use-jdk 21 -- ./mvnw test
```

需要管道、重定向或多个 shell 操作时，把 shell 本身作为子命令：

```bash
groot use-jdk 21 -- sh -lc 'java --version 2>&1 | tee java-version.log'
```

## 验证

执行构建或测试前，先确认实际版本：

```bash
groot use-jdk <major-version> -- java --version
```

首次使用某个版本时会从 Eclipse Adoptium 下载最新 Temurin GA JDK，并缓存到 `~/.config/groot/jdks/temurin-<major-version>`；后续调用直接复用缓存。

## 失败处理

- 下载失败：保留原始错误，检查网络、Adoptium 可用性和版本是否存在后重试。
- `unsupported operating system` 或 `unsupported CPU architecture`：不要绕过限制安装不匹配的二进制包。
- 构建报告 Java 版本错误：再次通过 `java --version` 验证，并检查构建工具自身的 Toolchain 配置。
- 命令找不到：确认命令位于 `--` 之后，并检查项目 wrapper 是否存在且可执行。
