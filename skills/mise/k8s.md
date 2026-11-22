# Kubernetes 交付参考

本文是 `mise` skill 的按需专项参考，仅在项目涉及 Kubernetes 资源管理时读取。任务编排遵循同目录 [SKILL.md](./SKILL.md)。

## 核心动作与部署铁律

项目部署必须通过 `mise.toml` 中的任务调用项目实际配置的资源跟踪工具（如 Groot 的 Kubernetes 资源同步能力，或既有项目自有的 kapp 流程），严禁绕过 inventory 直接手工向集群应用清单。

三个标准阶段：

- **渲染（render）**：递归读取 `./k8s` 下的 `.yaml` 与 `.yml` 文件，按白名单替换环境变量并注入归属跟踪元数据，纯本地计算、不访问集群。
- **差异比对（diff）**：执行服务端 dry-run 比对，同时列出 inventory 中将被清理的历史孤儿资源。返回码 `0` 表示无变化，`1` 表示存在预期差异（非报错），大于 `1` 才代表执行失败。
- **同步（sync）**：按服务端 apply 提交当前资源，安全清理归属当前项目的陈旧资源，最后原子更新集群端 inventory。

## 关键参数与默认值

- **`project`（必填）**：全局唯一且长期稳定的项目标识（如 `payments-prod`），严禁使用临时 worktree 路径或随机值。
- **`env`（可重复）**：允许执行变量替换的环境变量名白名单；名单外的 `$NAME` / `${NAME}` 保持原样，白名单内缺失的变量会在访问集群前直接报错终止。
- **`context`**：目标集群上下文名称，本机集群固定使用 `microk8s`。
- **`inventory-namespace`**：存放资源清单状态 ConfigMap 的命名空间，默认 `default`。
- **`kubectl`**：可选显式客户端路径；未指定时优先查找 `kubectl`，不存在时回退至 `microk8s` 内置客户端。
- **`allow-empty`**：布尔开关，默认关闭；空渲染结果默认报错拒绝，仅在明确下线清空整个项目且已先行完成 diff 核对删除列表后才允许开启。
- **`reveal-secrets`**：布尔开关，默认关闭；默认在 diff 中脱敏隐藏 Secret 明文。

## 清单、机密与资源跟踪规范

- **清单规范**：固定存放在项目根目录 `./k8s`，文件名保持清晰排序；所有命名空间级资源必须显式声明 `metadata.namespace`；Deployment 的 Pod 模板须绑定明确的镜像摘要或制品版本号以触发滚动更新；initContainer 拉取远程制品时必须先校验 `SHA-256` 再解压至共享 `emptyDir`；对外暴露的 Service 通过 ExternalDNS 注解声明 `*.yjgbg.lab` 域名；容器须具备探针、资源配额与最小权限安全上下文。
- **Vault 机密引用**：机密值存入本机 vault，不入 Git 也不放普通环境变量；在 YAML `stringData` 中使用不加引号的完整标量占位符 `${VAULT::<project>/<key>}`（写机密时通过标准输入交互录入，禁止在参数或日志中暴露明文，也禁止将渲染后的 Secret 清单落盘）。
- **Inventory 与安全回收顺序**：上次成功状态记录在 `ConfigMap/groot-inventory-<project-hash>`（仅存 `apiVersion`、`kind`、`namespace`、`name`，不含机密正文）。同步严格按“读取旧记录 → 提交全部新资源 → 计算差集 → 校验待删对象的 `project-id` 注解 → 仅删除归属匹配的陈旧对象 → 更新 inventory”顺序执行；提交失败绝不删旧资源或改 inventory。删除顺序保证 Custom Resource 先于 CRD、普通资源先于 Namespace，且单机同项目同步受文件锁保护。

## 常见错误排查

- **提示缺少环境变量**：补齐 `config.env` 中的导出变量，或收紧 `env` 白名单排除无需替换的容器内运行时表达式。
- **提示渲染结果为空**：核对 `./k8s` 目录与 `.yaml`/`.yml` 后缀，切勿盲目开启 `allow-empty`。
- **提示 inventory 或对象归属不匹配**：核查 `project` 标识是否与其他项目冲突，或目标资源已被人工接管，人工确认前禁止强行覆盖。
- **提示同步锁占用**：先核实是否确有并发同步进程在运行，仅在确认进程已退出后才清理残留锁。
