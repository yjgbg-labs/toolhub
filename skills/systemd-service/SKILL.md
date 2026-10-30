---
name: systemd-service
description: 管理或排查Linux systemd服务与定时器时使用
---

# Systemd 服务管理

当用户要求创建、修改、启停、排查 systemd 服务或 timer 时，使用本 skill。

## 范围

- 用户进程默认使用 user unit：`~/.config/systemd/user/groot-<name>.service`。
- 只有需要特权资源、专用系统用户或登录前运行时才使用 system unit。
- system unit 位于 `/etc/systemd/system`，提权修改前必须获得用户同意。
- 不修改或停用无关 unit。
- Groot 管理的 unit 使用 `groot-` 前缀。
- `groot-windows.service`、`groot-media.service`、`groot-usb.service` 由 `local-services` skill 管理；不要手写第二套 unit 或重复启动 serve。

## 先检查

```bash
systemctl --user status groot-<name>.service --no-pager --full
systemctl --user cat groot-<name>.service
systemctl --user show groot-<name>.service -p FragmentPath -p UnitFileState -p ActiveState -p SubState
journalctl --user -u groot-<name>.service -n 100 --no-pager
```

system unit 去掉 `--user`。覆盖文件前必须读取现有内容。

## Unit 约定

- `ExecStart`、`WorkingDirectory` 和文件参数使用绝对路径。
- 不依赖 alias、交互式 shell 配置或调用者当前目录。
- `ExecStart` 不是 shell；需要管道或重定向时才显式调用 shell。
- unit 文件权限为 `0644`，不能带可执行位。
- 密钥不要写进 unit 或日志，使用受限的 `EnvironmentFile` 或 credentials。
- 长期服务默认 `Restart=on-failure` 和合理的 `RestartSec`。
- oneshot 任务不要使用 `Restart=always`。
- 只添加同一 systemd manager 中确实存在的依赖目标。

基础 user service：

```ini
[Unit]
Description=<用途>
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
WorkingDirectory=/absolute/path
ExecStart=/absolute/path/to/command arg1
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

system service 通常使用 `WantedBy=multi-user.target`。

## 开机启动

user service 无登录启动需要 linger：

```bash
loginctl show-user "$USER" -p Linger
loginctl enable-linger "$USER"
```

新增用户组不会更新已运行的 user manager；依赖新组权限前需要重新登录或重启 user manager。

## Timer

周期任务优先使用项目约定的 `sprout schedule`；只有用户明确要求 systemd timer 时才创建 `.service` 与 `.timer`。

```ini
[Timer]
OnBootSec=1min
OnUnitActiveSec=5min
Persistent=true

[Install]
WantedBy=timers.target
```

定时 service 通常使用 `Type=oneshot`。启用 `.timer`，不要直接启用对应 service。

## PATH 与可执行文件解析

`ExecStart` 不是 shell，不会读取用户 shell 的 `PATH`；裸命令名只按编译期固定搜索路径（`/usr/local/bin:/usr/bin:/bin` 等）解析，解析失败即 `203/EXEC`。三种机制：

- **首选绝对路径**，最稳、无外部依赖。
- **裸命令名用 `ExecSearchPath=`**（systemd ≥250）指定搜索目录。通常与 `Environment=PATH=` 成对出现——前者解决 ExecStart 自身的解析，后者决定服务**子进程**的环境（服务内再调 `groot` 等用户工具时需要）：

```ini
[Service]
Environment=PATH=/home/yjgbg/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecSearchPath=/home/yjgbg/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=groot schedule serve
```

注意：`systemd-analyze --user verify` 对 `ExecSearchPath` 的解析检查会误报 `Command xxx is not executable`，以实际启动状态为准。

- **`~/.config/environment.d/*.conf`** 给整个 user manager 设全局环境，所有 user service 继承，但只适合子进程环境，不保证 ExecStart 裸命令解析。

被 systemd 直接执行的脚本（含软链接目标），shebang 用绝对路径（如 `#!/home/yjgbg/.bun/bin/bun`）；`#!/usr/bin/env bun` 在 user manager 默认 PATH 下找不到 `~/.bun/bin` 里的 bun，会以 `127` 失败。

## 应用与验证

```bash
chmod 0644 ~/.config/systemd/user/groot-<name>.service
systemd-analyze --user verify ~/.config/systemd/user/groot-<name>.service
systemctl --user daemon-reload
systemctl --user enable --now groot-<name>.service
systemctl --user is-enabled groot-<name>.service
systemctl --user status groot-<name>.service --no-pager --full
journalctl --user -u groot-<name>.service -n 100 --no-pager
```

`enable` 成功不代表进程健康，还要检查 active 状态、退出码、日志、监听端口和应用健康检查。

## 常见故障

- `203/EXEC`：可执行文件拼写错误、裸命令名不在搜索路径（见"PATH 与可执行文件解析"）或没有执行权限；指向软链接时确认链接和目标都存在。
- shell 中存在但服务中不存在：检查 unit 的 `Environment=PATH=`（子进程环境）和 user manager 的环境变量。
- `216/GROUP` 或设备拒绝：检查 user manager 的补充组和设备权限。
- 重启循环：查看第一次失败日志并设置启动限流。
- timer 不运行：确认 timer 和 service 文件同时存在，并检查 `list-timers`。
- 手动进程占用端口：停止脱离 systemd 的旧进程。

## 删除

```bash
systemctl --user disable --now groot-<name>.service
rm ~/.config/systemd/user/groot-<name>.service
systemctl --user daemon-reload
systemctl --user reset-failed
```

timer 要同时删除 `.timer` 和配套 `.service`，并确认没有残留手动进程。
