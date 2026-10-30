---
name: jobs
description: 创建、修改、构建或部署项目及使用groot k8s时使用
---

# 项目 Job 编排与交付

创建、修改、构建、打包或部署应用项目时，使用本 skill。一次性项目流程由 `jobs.yaml` 编排；周期任务使用 `schedule` skill。

## 强制约定

- 项目根目录必须提供 `jobs.yaml`，正常操作使用 `groot-cli jobs --job <job>`；旧入口为 `groot jobs <job>`。
- Kubernetes YAML 固定放在项目根目录的 `./k8s`。
- Kubernetes 部署必须由 job 调用 `groot k8s sync ./k8s`，禁止用 `kubectl apply` 绕过资源跟踪。
- 本机部署直接安装本机构建产物到目标目录，无需先上传再下载。
- CLI 可执行文件安装到 `~/.local/bin`；服务端和插件安装到各自配置的目标目录。
- `config.env` 集中定义 JDK、安装路径、服务名和其他实际使用的部署参数。

推荐目录：

```text
.
├── jobs.yaml
├── config.env
├── k8s/
├── scripts/
└── artifacts/
```

## 本机安装

CLI 构建和测试通过后，直接安装可执行文件，例如：

```sh
mkdir -p "$HOME/.local/bin"
chmod 755 cli/dist/groot-cli
mv -Tf cli/dist/groot-cli "$HOME/.local/bin/groot-cli"
```

服务端直接安装 JAR 到服务配置的目标路径，更新 user unit 后重启并检查健康状态。
插件直接安装 bundle 到配置的插件目标路径。

跨机器或 Kubernetes 部署按项目实际需要选择制品分发方式；对象存储不是统一前置条件。
远程制品使用明确版本，下载后校验 SHA-256；具体地址由项目配置定义。

## 任务图

按项目需要采用以下稳定 job 名称：

```yaml
version: 1

jobs:
  - name: install
    run: bun install --frozen-lockfile

  - name: test
    needs: [install]
    run: bun test

  - name: build
    needs: [test]
    run: bun run build

  - name: deploy
    needs: [build]
    run: ./scripts/deploy.sh

  - name: status
    run: ./scripts/status.sh
```

示例中的脚本由项目提供，也可以直接内联到 `run`。已有项目保留实际任务名称和目录结构。
依赖必须表达真实产物关系；本机部署依赖构建即可，不强制添加 package 或 upload 阶段。
可并行任务不要添加虚假依赖；只在确实需要时增加打包、上传或 Kubernetes render/diff 任务。

每个 job 只有一个 `run` shell 脚本，`needs` 引用同一文件中的任务名。
`groot-cli jobs` 只支持单个 `version: 1` 的 `jobs.yaml`，不支持 imports 或 namespace。
CLI 隐式传递 cwd，服务端读取该目录下的配置并返回 Shell；任务在 CLI 当前目录由 `/bin/sh -e -c` 执行。

## Kubernetes

任务涉及以下任一内容时，必须先读取 [k8s.md](./k8s.md)：

- 创建或修改 `./k8s` 中的资源；
- 执行 Kubernetes 的 `render`、`diff` 或 `deploy`；
- 使用 `groot k8s`；
- 处理 Vault Secret、inventory 或资源删除；
- 排查 Kubernetes 部署问题。

不涉及 Kubernetes 时不要读取该文件。

## 工作流程

1. 读取 `jobs.yaml`、`config.env` 和相关脚本，确认现有任务图及安装目标。
2. 按实际需要设计 test、build、deploy 和 status，本机交付直接安装构建产物。
3. 执行用户目标对应的最小 `groot-cli jobs --job <job>`；不要为检查而运行有副作用的 job。
4. Kubernetes 部署前运行 diff 任务；部署后验证命令入口、服务状态或 HTTP 健康端点。
5. 只有排障时才直接执行 job 内部命令，修复后仍需通过对应 job 验证。

## 排障

- `cannot read .../jobs.yaml`：确认命令在项目根目录执行。
- 缺失依赖或循环依赖：检查同一 `jobs.yaml` 中的任务名称和依赖关系。
- 构建或安装失败：从首个失败 job 排查，下游跳过是预期行为。
- 本机安装失败：检查构建产物、目标目录、可执行权限和命令入口。
- 远程制品下载失败：检查项目配置的分发地址、版本和校验文件。
- Kubernetes 同步问题：按 [k8s.md](./k8s.md) 检查 render、inventory 和资源归属。

不要把 token、密码、私钥或 Secret 明文写入 `jobs.yaml`、`config.env` 或制品。
