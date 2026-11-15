---
name: mise
description: 创建、修改、构建、打包或部署项目及管理工具版本时使用
---

# 项目任务与工具版本

一次性项目流程由 `mise.toml` 编排；周期任务使用 `schedule` skill，JDK 细节使用 `java` skill。

## 核心约定

- 项目根目录提供 `mise.toml`，正常构建、测试和部署统一执行 `mise run <task>`。
- 任务在配置所在项目根目录执行，`depends` 声明真实依赖；保留已有任务名称和安装目标。
- 每个 `run` 是一个 shell 脚本；Unix 默认失败即停止，不为并行任务添加虚假依赖。
- 普通依赖失败时下游不执行；`mise run --continue-on-error <task>` 可继续独立分支。
- `config.env` 可继续保存实际使用的部署参数；含 shell 展开或默认值的文件由脚本 `. ./config.env` 加载。
- 需要向子进程导出配置时使用 `set -a; . ./config.env; set +a`。
- 项目可用 `[tools]` 固定工具版本；已有 Bun、uv 环境继续遵守各自的包管理约定。
- JDK 通过 `mise exec java@temurin-<version> -- <command>` 选择；已有 Java 配置时可用 `mise exec -- <command>`。
- CLI 可执行文件安装到 `~/.local/bin`；本机部署直接安装本机构建产物到目标目录。
- 跨机器分发沿用项目实际机制，使用明确版本并校验 SHA-256。
- Kubernetes YAML 固定放在 `./k8s`，部署保留项目已有资源跟踪，禁止用 `kubectl apply` 绕过它。

## 任务定义

```toml
[tasks.install]
run = "bun install --frozen-lockfile"

[tasks.test]
depends = ["install"]
run = "bun test"

[tasks.build]
depends = ["test"]
run = "bun run build"

[tasks.deploy]
depends = ["build"]
run = "./scripts/deploy.sh"

[tasks.status]
run = "./scripts/status.sh"
```

多行 shell 使用 TOML 字面量字符串，保留引号、反斜杠和 heredoc：

```toml
[tasks.package]
depends = ["build"]
run = '''
set -eu
. ./config.env
bun scripts/package.ts "$ARTIFACT_VERSION"
'''
```

`depends` 中的任务可并行执行，共享依赖只执行一次。需要严格顺序时建立依赖链。
`mise run -j 2 build` 限制并发；mise 参数放在任务名前，任务名后的参数会传给任务。
任务默认从项目根目录运行，只有确实需要调用者目录时才设置 `dir = "{{cwd}}"`。

## 工作流程

1. 读取 `mise.toml`、`config.env` 和相关脚本，确认任务图及安装目标。
2. 先检查 `command -v mise` 和 `mise --version`。
3. 阅读配置后按需执行 `mise trust ./mise.toml`，不盲目全局信任。
4. 执行 `mise tasks validate` 校验配置，`mise tasks ls` 列出入口。
5. 使用 `mise run --dry-run <task>` 检查依赖图，再执行用户目标对应的最小任务。
6. 部署后验证命令入口、服务状态或 HTTP 健康端点。

只有排障时才直接执行任务内部命令；修复后仍需通过对应 mise 任务验证。
检查任务图时使用 dry-run，不运行会上传制品、重启服务或改动集群的部署任务。

## 安装与服务

mise 尚未安装时按 <https://mise.jdx.dev/installing-mise.html> 安装独立二进制到
`~/.local/bin/mise`；用 `mise self-update` 更新。

JDK 安装示例：`mise install java@temurin-25`。
systemd 使用 mise 的绝对路径和显式工具版本，部署时预装并验证 JDK，
服务设置 `Environment=MISE_EXEC_AUTO_INSTALL=false`，不在启动时下载工具。

## Kubernetes

涉及 Kubernetes 资源、render、diff、deploy 或 inventory 时，先读取 [k8s.md](./k8s.md)。
在 `mise.toml` 中调用项目实际使用的部署工具，部署前执行 diff，保持资源归属和删除语义。

## 排障

- 找不到任务：确认项目根目录、配置文件名、任务名称和信任状态。
- 缺失依赖或循环：运行 `mise tasks validate`，修正 `depends`。
- 构建或安装失败：从首个失败任务排查，检查产物、目标目录和权限。
- shell 行为异常：检查 TOML 字符串转义、heredoc 和 `config.env` 的加载方式。
- Java 版本错误：用 `mise exec java@<version> -- java --version` 验证，并检查项目 Toolchain。
- 远程制品校验失败：核对项目配置中的版本、下载地址和校验文件。

不要把 token、密码、私钥或 Secret 明文写入配置、脚本或制品。
