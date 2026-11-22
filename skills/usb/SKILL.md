---
name: usb
description: 管理或排查Windows USB/IP键鼠与音频时使用
---

# Groot USB/IP

管理、检查或排查向 Windows 主机导出的 USB/IP 虚拟音频与物理键鼠直通设备时使用本 skill。

## 架构与核心规则

- **单实例常驻**：用户级服务 `groot-usb.service` 独占监听 TCP 端口 `3240`，统一负责虚拟音频导出、物理直通代理与 Windows 侧挂载同步；严禁同时运行两个 USB 服务或旧版 agent 实例。
- **特权后端独立**：系统级 root 服务 `groot-usb-passthrough.service` 独立运行，不随用户级服务重启而连带重启。
- **Windows 通信通道**：与 Windows 侧的 PowerShell 交互统一经由本地 `windows.sock` 通道完成，不直接临时发起裸 SSH 会话。
- **事件驱动同步**：检测到 Windows 上线事件时立即触发设备同步，周期性轮询间隔为 `30` 秒（仅作安全兜底）。
- **非破坏性启动**：服务启动阶段严禁自动执行全量分离或清空客户端持久化 stash。
- **单次驱动迁移限制**：Windows 端驱动全局迁移属于显式、单向的破坏性操作（会停止全部重试、卸载已挂载端口、清空 stash 并将注册表 `ReattachMaxAttempts` 设为 `0`），只能在维护窗口停用旧实例后按项目 `docs/local-services-migration.md` 规范执行，严禁在日常排障时随意触发。

## 管理操作与状态判定

- **日常操作**：支持查看全部受管设备列表与单个设备详情、查询服务整体状态、按名称启用或禁用指定设备，以及手动触发一次即时对账（reconcile）。
- **健康验收标准**：
  - 已启用的虚拟音频设备（`headset`、`speaker`）必须处于 `exported` 且在 Windows 侧显示 `attached`。
  - 已物理接入的直通外设必须处于 `ready`；物理未接入的直通鼠标或键盘显示 `waiting` 属于正常待机状态，不应误判为故障。
  - Windows 侧 `usbip-win2` stash 应为空，且 `ReattachMaxAttempts` 保持为 `0`。
  - 本机 TCP `3240` 端口的活动连接数应与实际 `attached` 的设备数量一致；异常时结合用户级 `groot-usb.service` 状态与最近 `100` 行服务日志定位。
