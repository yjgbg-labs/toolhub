---
name: windows-host-ops
description: 从Linux检查或修改Windows主机时使用
---

# Windows 主机操作

当运行环境位于 Linux，而任务目标是检查或操作用户的 Windows 主机时，使用本 skill。纯 Linux 任务、仅修改当前本地仓库文件或可直接由本地 Linux 工具完成的任务不适用。

## 核心约定与关键参数

- **远程执行通道**：统一通过 Groot 服务端的 `shell` 与 `shell.env` 命名空间调用已注册的 `windows` 环境（调用前按需检索签名），先确认环境已注册并用轻量时间查询验证连通性。
- **Shell 与限额参数**：远端运行环境为 Windows PowerShell `5.1`（优先使用原生 PowerShell 能力而非套用 `cmd.exe`）；单次请求脚本正文与 stdout/stderr 单路输出上限均为 `64 KiB`（UTF-8），默认执行超时 `30s`（支持正整数加 `ms`/`s`/`m`/`h`/`d` 单位）。
- **文件挂载路径**：Windows 系统盘通过 SSHFS 挂载在 Linux 本机的 `/mnt/windows-c`（不要假设存在 WSL 的 `/mnt/c`）；挂载链路与 `shell` 执行通道相互独立，操作前先确认挂载点在线，切勿在 Windows 离线或未挂载时将不可读误判为目标文件不存在。
- **输出与变更规范**：Windows 路径在字符串内须妥善转义；查询结构化信息时优先限制字段并序列化为 JSON（嵌套深度建议控制在 `3`–`5` 层）；修改系统配置、服务、注册表、显示器、网络或外设前必须先读取现状并告知影响，涉及管理员权限时提前明确说明。
- **既有硬件脚本参考**：涉及显示器输入源切换、显示模式或 EDID/DDC 控制时，优先查阅工作区内已有的 `win_set_input.ps1`、`win_display_modes.ps1`、`win_ddc_runner.ps1` 等脚本逻辑后再组装执行内容。

## 排障流程

1. 依次检查 Groot 服务健康状态、`shell` 环境注册表中 `windows` 项是否存在，以及 `windows` 环境的最小脚本连通性（可将单次探测超时设为 `10s`）。
2. 若远程执行或 `/mnt/windows-c` 挂载出现异常，进一步阅读同目录下的 [mechanism.md](./mechanism.md) 按链路逐层定位。
