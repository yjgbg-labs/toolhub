---
name: windows-host-ops
description: 从Linux检查或修改Windows主机时使用
---

# Windows 主机操作

当 OpenCode 运行在 Linux，而任务目标是用户的 Windows 主机时，使用本 skill。

## 核心约定

- 先用 `groot windows status` 检查本地 Windows 服务和反向 SSH 状态。
- 通过 `groot windows exec` 操作 Windows；兼容别名为 `groot windows powershell`。
- Windows `C:` 通过 SSHFS 挂载在 `/mnt/windows-c`，不要假设存在 WSL 的 `/mnt/c`。
- 优先使用 PowerShell 原生命令，不要无故套用 `cmd.exe`。
- 修改系统、服务、注册表、显示器、网络或输入设备前，先读取现状并说明影响。
- 需要管理员权限时明确告知，不要假装普通权限可以完成。

## 命令形式

执行内联 PowerShell：

```bash
groot windows exec "Get-Date"
groot windows exec "Get-TimeZone | Format-List Id,DisplayName"
groot windows exec "Get-ChildItem 'C:\\Users'"
```

执行本地脚本：

```bash
groot windows exec --script ./script.ps1
groot windows exec --script ./script.ps1 arg1 "arg with spaces"
```

从 stdin 执行脚本：

```bash
groot windows exec --stdin < ./script.ps1
```

不要写成 `groot windows exec -Command ...`；该桥接不使用原生 `powershell.exe` 的参数解析方式。

## 输出与引用

- Windows 路径放在 PowerShell 字符串中并正确转义。
- 结构化结果优先使用 `Select-Object`、`Format-List` 或 `ConvertTo-Json -Depth 5`。
- 先用短命令验证桥接，再逐步增加复杂表达式。

```bash
groot windows exec "Get-Service sshd | Format-List Name,Status,StartType"
groot windows exec "Get-Process | Select-Object -First 5 Name,Id,CPU | ConvertTo-Json -Depth 3"
```

## Windows 文件

- 先用 `mountpoint -q /mnt/windows-c` 判断挂载状态。
- 正常情况下由 Groot Schedule 的 `windows-c-mount` 任务每 10 秒检测并恢复挂载。
- 不要在 Windows 离线时把挂载不可用误判为文件不存在。

## 故障处理

先运行 `groot windows status` 和 `groot windows exec "Get-Date"`。桥接失败或挂载异常时，再读取 [mechanism.md](./mechanism.md)，不要在日常操作中预加载实现细节。

## 不适用范围

- 纯 Linux 任务。
- 只修改当前仓库文件的任务。
- 可以直接用本地 Linux 工具完成的任务。

工作区已有 `win_set_input.ps1`、`win_display_modes.ps1` 和 `win_ddc_runner.ps1` 等脚本。显示器、输入源、EDID 或 Windows 硬件控制任务应先检查这些脚本，再决定是否通过 `--script` 执行。
