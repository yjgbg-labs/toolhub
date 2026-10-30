# Windows 桥接与挂载机制

仅在 `groot windows exec`、反向 SSH、`windows.sock`、`/mnt/windows-c` 或挂载任务异常时读取。

## 拓扑

```text
CLI / USB / Widget / Code
  -> /run/user/1000/groot/windows.sock
  -> groot-windows.service
  -> SSH 12394@127.0.0.1:18022
  -> Windows 127.0.0.1:22 sshd
  -> powershell.exe

Linux sshfs
  -> Windows 10.0.0.197:18024
  -> /C:/
  -> /mnt/windows-c
```

PowerShell 和文件挂载是两条独立链路。Schedule 的 `windows-c-mount` 使用直连 SFTP，不依赖 `windows.sock`。

## 反向隧道

Windows 服务 `covid19-tunnel` 维护：

```text
-R 127.0.0.1:18022:127.0.0.1:22 yjgbg@10.0.0.105
```

Windows 服务启动后，Linux 出现回环监听。`groot-windows.service` 每 250 ms 读取 `/proc/net/tcp*`，维护 revisioned online 状态并向 Display、USB 推送快照和事件。

## Windows 服务

```text
lib/windows/service.ts   socket 服务、状态和 PowerShell broker
lib/windows/client.ts    CLI 与其他服务的 typed client
lib/powershell/command.ts SSH stdin、参数编码和旧隧道恢复
```

默认 socket：

```text
/run/user/1000/groot/windows.sock
```

SSH 环境只属于 `groot-windows.service`：

| 参数 | 默认值 | 环境变量 |
| --- | --- | --- |
| 目标 | `12394@127.0.0.1` | `POWERSHELL_SSH_TARGET` |
| 端口 | `18022` | `POWERSHELL_SSH_PORT` |
| 私钥 | `~/.ssh/id_ed25519` | `POWERSHELL_SSH_KEY` |

脚本通过 RPC 传给 Windows 服务，再以 UTF-8 BOM 和已知字节数写入 SSH stdin。Windows 端写入随机临时 `.ps1`，执行后删除。参数使用 UTF-16LE/base64，避免跨 shell 引用损坏。

SSH banner 超时时，Windows 服务只终止拥有该监听的唯一 `session-N.scope`，再重试瞬时连接错误。远端命令非零不会重复执行。

## 直连挂载

`~/.config/groot/schedule/scripts/windows-c-mount.ts` 每 10 秒协调一次：

1. 检查 `10.0.0.197:18024`。
2. 检查 `/mnt/windows-c` 的 mount 与响应状态。
3. 离线时懒卸载失效 FUSE。
4. 在线且缺失或无响应时，用 SSHFS 挂载 `12394@10.0.0.197:/C:/`。

Schedule 引擎和任务配置不属于本地控制服务重构。

## 排障顺序

```bash
systemctl --user status groot-windows.service --no-pager --full
groot windows status
ss -ltn 'sport = :18022'
groot windows exec 'Get-Date'
journalctl --user -u groot-windows.service -n 100 --no-pager
```

### Socket 不存在

```bash
systemctl --user start groot-windows.service
stat -c '%a %U:%G %n' /run/user/1000/groot/windows.sock
```

权限必须为 `600`。不要绕过服务给业务代码增加直连 SSH fallback。

### 本地端口不存在

Windows 尚未建立反向隧道。通过 Windows 本地控制台检查：

```powershell
Get-Service covid19-tunnel,sshd | Format-List Name,Status,StartType
```

### 端口存在但 PowerShell 失败

```bash
ssh -T -p 18022 -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes \
  12394@127.0.0.1 powershell.exe -NoLogo -NoProfile -Command Get-Date
```

仍有旧监听时先检查：

```bash
ss -H -lntpe 'sport = :18022'
```

不要按进程名批量终止 `sshd-session`。

### 挂载失败

```bash
nc -z -w 1 10.0.0.197 18024
findmnt -M /mnt/windows-c
groot schedule log windows-c-mount --lines 100
```

只处理挂载任务，不修改 Schedule 服务本身。不要把私钥、Windows 凭据或其他 Secret 写入命令、配置或日志。
