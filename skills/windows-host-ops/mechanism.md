# Windows Shell 与挂载机制

仅在 `shell.execute` 的 Windows 环境或 `/mnt/windows-c` 挂载异常时读取。

## 执行链路

```text
CLI / Code Mode / GraalJS
  -> Groot HTTP shell.execute
  -> 持久化环境注册表中的 windows
  -> 服务用户的 OpenSSH（ControlMaster 复用连接）
  -> Windows sshd
  -> powershell.exe
```

- 环境注册表位于 `$GROOT_HOME/shell/environments.json`，默认数据根目录为 `~/.config/groot`。
- 首次初始化的 `windows` 环境使用 `POWERSHELL_SSH_TARGET`（默认 SSH 别名 `windows`）和 `POWERSHELL_SSH_IDLE`（默认 `5m`）。
- 目标的用户、端口、私钥和代理配置来自运行 `groot-http.service` 的用户 SSH 配置；严格检查 known_hosts，采用非交互认证。
- 通过 `shell.env.add/list/delete` 管理环境。删除环境后可重新注册同名配置；正在执行的旧调用完成后释放旧连接。
- PowerShell 以 UTF-8 BOM 和准确字节数传输到远端临时 `.ps1`，使用 Windows PowerShell 5.1 执行，完成后删除。
- 连接或执行超时默认 30 秒。超时、取消、输出超限会请求终止本次 Windows 进程树；用请求标记和 PID 创建时间定位，远程清理最多等待 5 秒。
- 连接故障不会自动重跑脚本。每次调用使用独立 PowerShell 进程；空闲连接默认 5 分钟释放。

## 实现入口

```text
server/src/main/scala/groot/shell/ShellRoutes.scala    统一命令组
server/src/main/scala/groot/shell/ShellService.scala   环境注册表与执行生命周期
server/src/main/scala/groot/powershell/PowerShellService.scala  Windows SSH 执行器
```

## 排障顺序

```bash
groot-cli health
groot-cli shell env list
groot-cli shell execute --body '{"env":"windows","script":"Get-Date","timeout":"10s"}'
systemctl --user status groot-http.service --no-pager --full
journalctl --user -u groot-http.service -n 100 --no-pager
ssh -G windows
```

`ssh -G` 的参数应使用环境实际登记的 `target`。先核对目标解析、用户、端口、密钥及网络连通性，再处理 Windows 的 `sshd`。

## 文件挂载

Shell SSH 与 SSHFS 是两条独立链路。`/mnt/windows-c` 异常时检查实际挂载和当前任务：

```bash
mountpoint -q /mnt/windows-c
findmnt -M /mnt/windows-c
groot-cli schedule list
```

以 `findmnt` 显示的源地址为准排查 SFTP；如果存在挂载维护任务，再查看该任务的 `schedule log`。
