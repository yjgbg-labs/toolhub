# CRITICAL INSTRUCTIONS — MUST FOLLOW

## Python 环境管理（MANDATORY）

所有 Python 操作**必须使用 `uv`**，禁止使用 `pip`、`pip3`、`python -m pip` 等。

- 安装依赖：`uv pip install <package>` 或 `uv add <package>`
- 创建虚拟环境：`uv venv`
- 运行脚本：`uv run <script.py>`
- 管理项目：`uv init`、`uv sync`

**不要**使用 `pip install`、`pip3 install`、`python -m venv` 等传统命令。

## Node.js / Bun（MANDATORY）

优先使用 **bun** 作为运行时和包管理器。

- 安装依赖：`bun install`
- 运行脚本：`bun run <script>`
- 执行临时包：`bunx <package>`
- shebang：新文件用 `#!/usr/bin/env bun`

**不要**使用 `npm install`、`npx`、`node` 来运行代码。现有 `#!/usr/bin/env node` 的文件保持兼容，新文件一律用 bun。

## 项目交付工作流（MANDATORY）

创建、修改、构建、打包或部署应用项目时，必须加载 `jobs` skill，并遵循其中的 `jobs.yaml` 编排、S3 制品流和 `groot k8s` 部署约定。

- 正常构建和部署操作统一通过 `groot jobs <job>` 执行。
- Kubernetes YAML 固定放在项目根目录的 `./k8s`。
- 程序制品统一通过 `s3.yjgbg.lab` 上传和下载。
- 禁止使用 `kubectl apply` 绕过 `groot k8s` 的资源跟踪。

## 代码修改工作流（MANDATORY）

**绝对禁止**在原目录的原分支上直接修改代码。所有代码改动必须严格遵守以下流程：

### 1. 更新本地代码

修改任何代码前，必须先拉取最新版本：

```bash
git -C <repo_path> pull --rebase
```

### 2. 创建新分支 + worktree

必须使用 git worktree，将新分支检出到 `~/workspace/worktrees/` 下，**不得**在原仓库目录内工作：

```bash
# 在原仓库中创建分支并同时建 worktree
git -C <repo_path> worktree add ~/workspace/worktrees/<repo_name>/<branch_name> -b <branch_name>
```

- `<branch_name>` 应简洁描述改动内容，例如 `fix/login-timeout`、`feat/export-csv`
- worktree 路径统一为 `~/workspace/worktrees/<repo_name>/<branch_name>`

### 3. 在 worktree 内进行所有修改

```bash
# 所有编辑、测试、提交均在 worktree 目录内完成
cd ~/workspace/worktrees/<repo_name>/<branch_name>
```

### 4. 完成后清理 worktree（可选）

PR 合并或改动废弃后，删除 worktree：

```bash
git -C <repo_path> worktree remove ~/workspace/worktrees/<repo_name>/<branch_name>
```

**违反以上流程（直接在原目录改代码）是不可接受的行为。**
