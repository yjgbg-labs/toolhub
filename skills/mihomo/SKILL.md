---
name: mihomo
description: 管理OpenWrt上的mihomo服务或配置时使用
---

# Mihomo

当用户要求查看、启动、停止、重启、编辑、回滚或排查路由器上的 mihomo 时，使用本 skill。

## 核心约定

- 统一使用 `groot mihomo`，不要直接调用本机 mihomo。
- 默认 SSH 目标为 `root@10.0.0.1`。
- 默认配置为 `/etc/mihomo/config.yaml`，配置目录为 `/etc/mihomo`。
- 修改前先读取状态、日志或配置；修改后再次验证。
- 不在回复中泄露密码、订阅 URL、token、secret、uuid 或私钥。
- 简单 DNS hosts 解析使用 `dns` skill。
- 节点切换、延迟测试、查看活动连接使用 `proxy` skill（`groot mihomo proxy`）。

## 常用命令

```bash
groot mihomo --help
groot mihomo status
groot mihomo logs --lines 100
groot mihomo logs -f
groot mihomo read
groot mihomo start
groot mihomo stop
groot mihomo restart
groot mihomo rollback
groot mihomo proxy --help
```

指定其他路由器：

```bash
groot mihomo --host root@10.0.0.2 status
groot mihomo --host root@10.0.0.2 read
```

使用 `--help` 查看帮助，不要使用无效的 `groot mihomo help`。`logs -f` 会持续运行，只在用户明确要求实时日志时使用。

## 修改配置

优先读取现有配置：

```bash
groot mihomo read
```

交互编辑：

```bash
groot mihomo edit
```

从 stdin 写入完整配置：

```bash
groot mihomo write < ./config.yaml
```

`edit` 和 `write` 会执行以下流程：

1. 拉取或读取完整配置。
2. 对比内容，没有变化时不重启。
3. 上传到远端临时路径。
4. 执行 `mihomo -t` 校验。
5. 备份原配置并覆盖正式文件。
6. 重启 mihomo。

不要绕过该流程直接 SSH 覆盖远端配置。

## 排障顺序

1. 运行 `groot mihomo status`。
2. 运行 `groot mihomo logs --lines 100`。
3. 必要时运行 `groot mihomo read`，输出敏感字段时要打码。
4. 根据结果决定是否编辑、重启或回滚。
5. 操作后重新检查状态和日志。

## 回滚与影响

```bash
groot mihomo rollback
```

回滚会恢复最新的 `/etc/mihomo/config.yaml.bak.*`，校验后重启服务。只有用户明确要求，或刚修改的配置确认有问题时才执行。

`stop`、`restart`、`write`、`edit` 和 `rollback` 会影响路由器代理服务，执行前说明影响。命令失败时先报告原始错误要点，再决定是否继续操作。
