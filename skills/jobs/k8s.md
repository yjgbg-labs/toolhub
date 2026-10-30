# Kubernetes 交付

本文是 `jobs` skill 的按需参考，只在项目涉及 Kubernetes 时读取。完整构建、打包和 S3 制品流以 [SKILL.md](./SKILL.md) 为准。

## 命令

```bash
groot k8s render ./k8s --project <id> [options]
groot k8s diff ./k8s --project <id> [options]
groot k8s sync ./k8s --project <id> [options]
```

- `render`：递归读取 `.yaml` 和 `.yml`，执行 `envsubst`，注入跟踪元数据，不访问集群。
- `diff`：执行 server-side `kubectl diff`，并列出 inventory 中将删除的旧资源。
- `sync`：server-side apply 当前资源，安全删除旧资源，再更新 inventory。

项目正常部署必须通过 `jobs.yaml` 调用这些命令，不直接执行 `kubectl apply`。`kubectl diff` 返回 `1` 表示存在差异，不是错误；大于 `1` 才是执行失败。

## 选项

```text
--project <id>                稳定且唯一的项目标识，必填
--env <name>                  只替换指定环境变量，可重复
--context <name>              diff 和 sync 使用的 Kubernetes context
--inventory-namespace <name>  inventory ConfigMap namespace，默认 default
--kubectl <path>              显式指定 kubectl 或 microk8s
--allow-empty                 允许空渲染并删除全部历史资源
--reveal-secrets              解析 vault 引用或显示 Secret 详细 diff
```

本地集群使用 `--context microk8s`。未传 `--kubectl` 时优先使用 `kubectl`，找不到时使用 `microk8s kubectl`。

## Manifest 约定

- 资源文件固定放在 `./k8s`，按名称排序后应具备可读的创建顺序。
- namespaced 资源必须显式写 `metadata.namespace`。
- Deployment 使用制品 URL、SHA-256 URL 或版本作为 Pod template 参数，确保制品变化触发滚动更新。
- initContainer 从 RustFS 集群内地址下载制品、校验 SHA-256，并解压到共享 `emptyDir`。
- 对外 Service 使用 ExternalDNS annotation 声明 `*.yjgbg.lab` 域名。
- 容器应配置 readiness、liveness、资源限制和最小权限 security context。

## 环境变量

默认扫描模板中的 `$NAME` 和 `${NAME}`，缺少变量会在访问集群前失败。通过重复的 `--env` 限制替换白名单：

```bash
. ./config.env
groot k8s render ./k8s \
  --project example-prod \
  --env ARTIFACT_URL \
  --env ARTIFACT_SHA256_URL
```

白名单之外的变量表达式保持原样。不要把渲染后的 Secret YAML 写入日志或文件。

## Vault Secret

Secret 使用本地 vault 引用，不放进 Git 或环境变量：

```yaml
stringData:
  token: ${VAULT::example-prod/api-token}
```

占位符必须是完整且不加引号的 YAML scalar。使用 `groot vault set example-prod/api-token` 设置值，不要把值作为命令行参数传入。`render` 默认保留引用；`diff` 和 `sync` 会解析，且 diff 默认隐藏明文。

## 资源跟踪

命令为资源注入 managed-by、project hash label 和 project-id annotation。`--project` 必须长期稳定，例如 `payments-prod`，不能使用 worktree 路径或随机值。

上次成功资源集合保存在 `ConfigMap/groot-inventory-<project-hash>`。inventory 仅保存 apiVersion、kind、namespace 和 name，不保存 Secret 内容。

`sync` 顺序如下：

1. 读取旧 inventory。
2. apply 全部当前资源。
3. 计算旧资源与当前资源的差集。
4. 读取 stale 资源并核对 project-id annotation。
5. 只删除归属当前 project 的 stale 资源。
6. 删除成功后更新 inventory。

apply 失败时不会删除旧资源或更新 inventory。资源 annotation 缺失或不匹配时拒绝删除，避免误删被其他项目接管的对象。

## 首次部署与删除

目标 Namespace 不存在时，`diff` 会跳过其中资源并成功退出；随后用 `sync` 创建 Namespace，后续 `diff` 才能完整比较。

空目录默认报错。只有明确清空项目全部资源时才能使用 `--allow-empty`，并且必须先执行 diff 确认删除列表：

```bash
groot k8s diff ./empty --project example-prod --context microk8s --allow-empty
groot k8s sync ./empty --project example-prod --context microk8s --allow-empty
```

Custom Resource 先于 CRD 删除，Namespace 在普通资源之后删除。同一 project 的本机 sync 有锁保护；遗留锁只能在确认没有同步进程后清理。

## 排障

- `missing environment variables`：补充变量，或用 `--env` 排除不应替换的表达式。
- `rendered no Kubernetes resources`：检查目录和扩展名，不要直接添加 `--allow-empty`。
- `inventory ... is not owned`：检查 project ID 冲突，不要覆盖 inventory。
- `project annotation does not match`：stale 资源已失去当前项目归属，人工确认后处理。
- `another sync ... appears to be running`：检查同步进程和本地锁。
- Pod 制品校验失败：比较 S3 对象、SHA-256 文件和 Deployment 中的两个 URL。
