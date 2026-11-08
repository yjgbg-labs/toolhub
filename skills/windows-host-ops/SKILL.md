---
name: windows-host-ops
description: 从Linux检查或修改Windows主机时使用
---

# Windows 主机操作

当 OpenCode 运行在 Linux，而任务目标是用户的 Windows 主机时，使用本 skill。

## 核心约定

- 先用 `groot-cli shell env list` 确认 `windows` 环境，再运行 `Get-Date` 检查连接。
- 通过 `tools.shell.execute({env: "windows", script})` 或 `groot-cli shell execute` 操作 Windows。
- Windows `C:` 通过 SSHFS 挂载在 `/mnt/windows-c`，不要假设存在 WSL 的 `/mnt/c`。
- 优先使用 PowerShell 原生命令，不要无故套用 `cmd.exe`。
- 修改系统、服务、注册表、显示器、网络或输入设备前，先读取现状并说明影响。
- 需要管理员权限时明确告知，不要假装普通权限可以完成。

## 命令形式

执行内联 PowerShell：

```bash
groot-cli shell execute --body '{"env":"windows","script":"Get-Date"}'
groot-cli shell execute --body '{"env":"windows","script":"Get-TimeZone | Format-List Id,DisplayName"}'
groot-cli shell execute --body '{"env":"windows","script":"Get-ChildItem C:\\Users"}'
```

执行 Linux 上的本地 PowerShell 脚本（文件内容进入 `script` 字段）：

```bash
jq -n --rawfile script ./script.ps1 '{env:"windows",script:$script}' | groot-cli shell execute --body -
```

Code Mode 先用 `search` 查找签名，然后调用：

```js
const result = (await tools.shell.execute({env: "windows", script: "Get-Date", timeout: "30s"})).body;
if (result.exitCode !== 0) throw Error(result.stderr || `PowerShell exit ${result.exitCode}`);
console.log(result.stdout);
```

`shell.execute` 接收 JSON 对象；脚本最多 64 KiB，默认超时 30 秒，每路输出最多 64 KiB。
脚本直接通过 `-EncodedCommand` 执行，还受目标命令行长度限制。超时/取消只结束本地 SSH 调用，远端进程和文件由用户管理。

## 输出与引用

- Windows 路径放在 PowerShell 字符串中并正确转义。
- 结构化结果优先使用 `Select-Object`、`Format-List` 或 `ConvertTo-Json -Depth 5`。
- 先用短命令验证桥接，再逐步增加复杂表达式。
- 按需在脚本中设置输出编码和错误策略；要传递外部程序退出码时显式使用 `exit $LASTEXITCODE`。

```bash
groot-cli shell execute --body '{"env":"windows","script":"Get-Service sshd | Format-List Name,Status,StartType"}'
groot-cli shell execute --body '{"env":"windows","script":"Get-Process | Select-Object -First 5 Name,Id,CPU | ConvertTo-Json -Depth 3"}'
```

## Windows 文件

- 先用 `mountpoint -q /mnt/windows-c` 判断挂载状态。
- 挂载与 Shell 的 SSH 连接独立；自动恢复情况以当前 `groot-cli schedule list` 为准。
- 不要在 Windows 离线时把挂载不可用误判为文件不存在。

## 故障处理

先检查 `groot-cli health`、`groot-cli shell env list` 和 `windows` 环境的 `Get-Date`。连接或挂载异常时，再读取 [mechanism.md](./mechanism.md)。

## 不适用范围

- 纯 Linux 任务。
- 只修改当前仓库文件的任务。
- 可以直接用本地 Linux 工具完成的任务。

工作区已有 `win_set_input.ps1`、`win_display_modes.ps1` 和 `win_ddc_runner.ps1` 等脚本。显示器、输入源、EDID 或 Windows 硬件控制任务应先检查这些脚本，再将所需内容放入 `script` 执行。
