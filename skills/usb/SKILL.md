---
name: usb
description: 管理或排查Windows USB/IP键鼠与音频时使用
---

# Groot USB/IP

`groot usb serve` 持有 TCP 3240、虚拟音频、passthrough proxy 和 Windows 同步。

## 状态

```bash
groot usb list
groot usb service-status
systemctl --user status groot-usb.service --no-pager --full
journalctl --user -u groot-usb.service -n 100 --no-pager
```

## 核心规则

- Windows PowerShell 统一通过 `windows.sock`，不直接创建 SSH 调用。
- Windows 上线事件立即触发同步，30 秒检查只是安全兜底。
- 服务启动不得自动执行 `detach --all` 或清空 stash。
- `migrate-windows` 是显式、破坏性的单次迁移。
- 未插入的 passthrough 鼠标或键盘显示 `waiting` 是正常状态。
- root `groot-usb-passthrough.service` 不随 user service 重启。
- 不运行两个 USB serve/agent 实例。

## 配置操作

```bash
groot usb enable <name>
groot usb disable <name>
groot usb reconcile
groot usb show <name>
```

## Windows 迁移

先确认维护窗口，再按 `../../docs/local-services-migration.md` 操作。不要在现有 agent 持有设备时随意运行：

```bash
groot usb migrate-windows --force-global
```

## 验证

- headset、speaker 必须 exported 且 Windows attached。
- 已插入的 passthrough 设备必须 ready。
- stash 为空且 `ReattachMaxAttempts=0`。
- TCP 3240 连接数应与实际 attached 设备一致。
