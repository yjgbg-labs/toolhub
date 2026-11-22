# Windows Shell 与挂载机制参考

仅在 `windows` Shell 环境执行异常或 `/mnt/windows-c` 挂载不可用时读取本参考文档。

## 执行链路与关键参数

请求从客户端进入 Groot HTTP 服务的 `shell` 模块（源码位于 `server/src/main/scala/groot/shell/`），读取环境注册表中的 `windows` 条目，通过运行 `groot-http.service` 的本机用户 OpenSSH 连接至 Windows 端 `sshd`，最终由远端 `powershell.exe` 执行。

关键配置与运行数值：

- **环境注册表路径**：`$GROOT_HOME/shell/environments.json`（默认数据根目录 `~/.config/groot`）。
- **初始默认参数**：首次启动导入默认环境时读取 `POWERSHELL_SSH_TARGET`（默认 SSH 主机别名 `windows`）与 `POWERSHELL_SSH_IDLE`（ControlMaster 空闲保持时长，默认 `5m`，最短 `1s`）。
- **认证与连接复用**：连接的用户、端口、密钥与代理由服务所属用户的 SSH 配置决定，强制启用主机密钥严格校验与非交互认证；OpenSSH ControlMaster 空闲 `5` 分钟自动回收，删除或重建同名环境时已开始的旧调用会独立运行完再释放旧连接。
- **传输、超时与取消清理**：脚本以带 UTF-8 BOM 的临时 `.ps1` 精确传输并由 Windows PowerShell `5.1` 执行，结束后自动清理；脚本及每路输出上限 `64 KiB`，连接加执行默认超时 `30s`；发生超时、取消或输出超限时，通过请求专属标记与进程创建时间定位并终止当次 Windows 进程树（远程清理最长等待 `5s`），连接故障绝不自动重跑脚本。

## 分层排障顺序

1. **服务端与环境层**：确认 Groot HTTP 服务健康响应正常、用户级 `groot-http.service` 处于运行状态（可结合最近 `100` 行服务日志排查），并核对 `shell` 环境列表中 `windows` 项登记的 `target` 值。
2. **SSH 传输层**：针对登记的 `target` 解析本机 OpenSSH 生效配置，逐项核查目标主机地址、端口、登录用户名、私钥路径、`known_hosts` 及 Tailscale/Headscale 网络连通性，再排查 Windows 主机上的 `sshd` 服务状态。
3. **SSHFS 挂载层**：`/mnt/windows-c` 与 `shell` 的 ControlMaster 互不共用连接；挂载失效时先检查 `/mnt/windows-c` 的实际挂载状态与底层挂载源地址以排查 SFTP 连通性，并查看 `schedule` 定时任务列表及对应任务日志确认挂载自愈任务的最近执行结果。
