---
name: local-services
description: 安装、升级或排查Groot本地控制服务时使用
---

# Groot 本地控制服务

用于 Windows、Media、USB 三个 user service。Schedule 明确不属于本 skill。

## 核心边界

```text
usb -> windows
```

- Linux 独占自己的显示器，没有 display 服务，也没有 `groot display` 命令。
- 服务通过 `$XDG_RUNTIME_DIR/groot/*.sock` 通信，不使用 HTTP、NATS 或本地 `groot` 子进程。
- unit 只使用软依赖 `Wants=`、`After=`，不得使用 `Requires=` 绑定生命周期。
- root `groot-usb-passthrough.service` 不属于 user-service 切换。
- 不并行运行新旧 Media 或 USB 服务。

## 安装

```bash
groot jobs deploy
systemd-analyze --user verify ~/.config/systemd/user/groot-{windows,media,usb}.service
```

安装只写文件并 daemon-reload，不会启停服务。

## 状态

```bash
groot local-services status
groot windows status
groot media status
groot usb service-status
```

## 日志

```bash
journalctl --user -u groot-windows.service -n 100 --no-pager
journalctl --user -u groot-media.service -n 100 --no-pager
journalctl --user -u groot-usb.service -n 100 --no-pager
```

## 切换规则

- Windows 服务可以先独立启用。
- Media 只在没有播放时切换；先检查并处理旧 DLNA drop-in。
- USB 切换会断开设备，必须明确维护窗口。
- 旧 unit 保留到新服务稳定并完成回滚验证。
- 详细步骤读取 `../../docs/local-services-migration.md`。

## 失败处理

- `already listening`：存在第二个服务实例，先定位 unit 或手动进程，禁止删除活 socket。
- socket 不存在：检查对应 service，而不是绕过服务直接操作硬件。
- unit active 但命令失败：检查 service 日志和 env 文件。
- Media/USB 端口冲突：立即停止新实例，确认旧 unit 仍在运行。
