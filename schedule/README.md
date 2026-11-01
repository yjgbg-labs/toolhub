# 远程设备定时任务

这些 GraalJS 脚本直接调用普通 SDK 命令：

| 任务 | 周期 | 采集/执行入口 |
| --- | --- | --- |
| `widget-router-metrics` | 10s | `tools["bash-on-r6s"]`，R6S CPU、温度、内存、磁盘和 `lan2` 上下行 |
| `widget-mouse-battery` | 10s | `tools.powershell`，现有 `VID_1D57/PID_FA65` 接收器的被动 HID 电量报告 |
| `windows-tray-always-show` | 1m | `tools.powershell`，将当前用户托盘图标设为常显 |

```sh
groot-cli jobs --job schedule-deploy
```

部署先运行脚本逻辑测试，再通过真实 GraalJS 分别执行一次，等待日志流结束并检查终态。
实测会更新悬浮窗，并执行托盘注册表设置；Windows 和 R6S 应在线。
随后将脚本原子安装到 `$SPROUT_HOME/scripts`，通过 `schedule/new` 注册各任务。
`schedule/jobs.json` 定义周期、资源配额和超时；重试使用已有任务配置或服务端默认值。
配置和已有脚本保存在 `$SPROUT_HOME/backups/remote-schedules-*`。
部署工具运行在服务端所在的本机，API 默认使用 `config.env` 中的 `SERVER_PORT`。

- SSH 连接由命令 service 复用，各任务直接传 `body` 字符串和普通 `timeout` 参数。
- R6S 每轮只读取一次计数器，使用 `tools.state` 保存前一轮样本，计算完整周期内的 CPU 和网速。
  首次采样、重启、计数器重置或间隔超过 120 秒时显示 `--`，下一轮恢复。
- 鼠标沿用已验证的 HID 读取逻辑，每次读取最多 5 秒。充电显示 `CHARGING`、充满显示 `FULL`。
  采集失败显示 `OFFLINE` 并抛错，让 schedule 执行重试。
- 托盘任务只写入需要变更的 `IsPromoted=1` 和 `EnableAutoTray=0`，记录本轮修改数。

查看结果：

```sh
groot-cli schedule list
groot-cli schedule log --name widget-router-metrics --lines 30
groot-cli schedule log --name widget-mouse-battery --lines 30
groot-cli schedule log --name windows-tray-always-show --lines 30
```
