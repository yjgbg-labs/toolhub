---
name: jobs
description: 创建、修改、构建或部署项目及使用groot k8s时使用，遵循jobs.yaml和S3流程
---

# 项目 Job 编排与交付

创建、修改、构建、打包或部署应用项目时，使用本 skill。一次性项目流程由 `groot jobs` 编排；周期任务使用 `schedule` skill。

## 强制约定

- 项目根目录必须提供 `jobs.yaml`，正常操作统一执行 `groot jobs <job>`。
- Kubernetes YAML 固定放在项目根目录的 `./k8s`。
- Kubernetes 部署必须由 job 调用 `groot k8s sync ./k8s`，禁止用 `kubectl apply` 绕过资源跟踪。
- 所有程序制品通过 `s3.yjgbg.lab` 上传和下载，不依赖开发机目录或临时文件复制。
- 制品必须使用稳定版本标识并提供 SHA-256；Pod 下载后先校验再启动。
- `config.env` 集中定义制品版本、上传地址、集群内下载地址、运行时镜像和部署参数。

推荐目录：

```text
.
├── jobs.yaml
├── config.env
├── k8s/
├── scripts/
└── artifacts/
```

## 制品流

上传使用开发环境可访问的地址：

```text
http://s3.yjgbg.lab:9000/public/<project>/
```

Pod 优先使用集群内地址下载同一对象：

```text
http://rustfs.rustfs.svc.cluster.local:9000/public/<project>/
```

版本优先使用 Git commit 或内容哈希。不要覆盖同一版本的制品，否则 Kubernetes 无法可靠触发滚动更新。

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

  - name: package
    needs: [build]
    run: ./scripts/package.sh

  - name: upload
    needs: [package]
    run: ./scripts/upload.sh

  - name: render
    run: |
      . ./config.env
      groot k8s render ./k8s --project example-prod --env ARTIFACT_URL

  - name: diff
    run: |
      . ./config.env
      groot k8s diff ./k8s --project example-prod --context microk8s --env ARTIFACT_URL

  - name: deploy
    needs: [upload]
    run: |
      . ./config.env
      groot k8s sync ./k8s --project example-prod --context microk8s --env ARTIFACT_URL

  - name: status
    run: microk8s kubectl --namespace example get deployment,pod,service
```

依赖必须表达真实产物关系：`package` 依赖 `build`，`upload` 依赖 `package`，`deploy` 依赖 `upload`。可并行任务不要添加虚假依赖。

每个 job 只有一个 `run` shell 脚本。`needs` 可以引用根 job 或 `job@namespace`；导入文件使用同样的 `version: 1` 结构。脚本由 `/bin/sh -e -c` 执行，工作目录始终是根 `jobs.yaml` 所在目录。

## Kubernetes

任务涉及以下任一内容时，必须先读取 [k8s.md](./k8s.md)：

- 创建或修改 `./k8s` 中的资源；
- 执行 `render`、`diff` 或 `deploy`；
- 使用 `groot k8s`；
- 处理 Vault Secret、inventory 或资源删除；
- 排查 Kubernetes 部署问题。

不涉及 Kubernetes 时不要读取该文件。

## 工作流程

1. 读取 `jobs.yaml`、递归 imports、`config.env` 和相关脚本，确认现有任务图。
2. 新项目先设计 build、test、package、upload、deploy 和 status，再实现各步骤。
3. 执行用户目标对应的最小 `groot jobs <job>`；不要为检查而运行有副作用的 job。
4. 部署前运行 `groot jobs diff`；部署后运行 status，并验证 DNS 和 HTTP 端点。
5. 只有排障时才直接执行 job 内部命令，修复后仍需通过对应 job 验证。

## 排障

- `cannot read .../jobs.yaml`：确认命令在项目根目录执行。
- `depends on missing job` 或循环依赖：读取完整 imports 后修正任务图。
- package、upload 或 deploy 失败：从首个失败 job 排查，下游跳过是预期行为。
- 制品下载失败：分别验证外部上传地址、集群内下载地址和 SHA-256 文件。
- Kubernetes 同步问题：按 [k8s.md](./k8s.md) 检查 render、inventory 和资源归属。

不要把 token、密码、私钥或 Secret 明文写入 `jobs.yaml`、`config.env` 或制品。
