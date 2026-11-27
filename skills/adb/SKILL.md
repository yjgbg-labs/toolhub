---
name: adb
description: 通过原生ADB协议控制、传输文件或管理Android与电视设备时使用
---

# ADB 设备控制

当用户需要操控局域网上的 Android 手机、平板、机顶盒或智能电视（如小米电视），执行按键遥控、亮熄屏控制、截图、二进制文件读写、应用安装卸载或远端命令时，使用本 skill。DLNA 投屏渲染归属 `media` skill。

## 核心约定

- 统一通过 Groot 服务端的 `adb` 命名空间完成全部操作，调用前先按需检索最新工具签名。
- 服务端使用纯异步 TCP 直接与目标设备的 `adbd` 守护进程（默认端口 `5555`）交互，自行完成按需建连、24 字节二进制帧编解码、RSA-2048 挑战签名、多路复用流管理与空闲连接自动回收，不依赖本机外部可执行程序或 `5037` 守护进程，也不在本机拉起任何 shell 子进程。
- 所有面向单台设备的操作必须显式指定 `target`（传入配置的设备别名如 `xiaomitv`，或显式 `host[:port]` 地址，无隐式默认设备推导），每次操作均根据 `target` 按需自动建立或复用连接。
- 禁止在局域网段执行批量端口扫描探测设备；未知设备地址时请向用户确认或从路由器 DHCP 租约记录查询。
- 回复与日志摘要中严禁输出任何私钥内容。

## 配置、密钥与目标别名

- **热加载配置（`$GROOT_HOME/application.conf` 的 `adb` 段）**：
  - `targets`：设备别名映射字典（键名仅限字母、数字、点、下划线与连字符，值为合法主机名或带 `1`–`65535` 端口的地址，省略端口默认 `5555`）。
  - `keyFile`：自定义 PKCS#8 RSA-2048 私钥路径（环境变量回退 `ADB_KEY_FILE`）。留空时依次查找 `$GROOT_HOME/adb/adbkey`、`~/.android/adbkey`；均不存在时自动在 `$GROOT_HOME/adb/adbkey` 生成并落盘（权限 `0600`）及配套 `.pub` 公钥文件。
  - `connectTimeout` / `authTimeout` / `commandTimeout` / `idleTimeout`：建连超时默认 `5s`，首次屏幕弹窗授权等待默认 `20s`，单次流执行超时默认 `30s`，空闲连接池回收超时默认 `30s`。
  - `maxOutputBytes` / `maxTransferBytes`：单次流输出或截图上限默认 `4 MiB`（范围 `4 KiB`–`64 MiB`），`sync` 文件传输上限默认 `256 MiB`（范围 `4 KiB`–`512 MiB`）。

## 电源、输入与文件操作约束

- **电视电源安全红线**：
  - 对不支持网络唤醒（Wake-on-LAN）的智能电视或盒子，发送电源键码 `26`（`POWER`）或执行系统关机可能导致彻底断电断网、只能用物理遥控器恢复。
  - 日常待机与亮屏优先使用 `screen` 接口（内部通过 `223` 熄屏与 `224` 唤醒切换并校验实时状态）；`keyevent` 默认拦截键码 `26`，仅在用户明确要求彻底关机时才将 `allowPowerOff` 置为 `true`。
  - `reboot` 接口必须显式传入 `confirm` 为 `true` 才会执行。
- **二进制文件传输与截图**：
  - `stat`、`ls`、`push`、`pull` 均基于原生 `sync:` 二进制子协议传输，`remotePath` 必须是以 `/` 开头的设备绝对路径。
  - `pull` 省略 `localPath` 时，仅允许拉取不超过 `256 KiB` 的小文件并内联返回 `contentBase64`；大文件必须指定 `localPath` 直接落盘。
  - `screencap` 通过远端临时文件加 `sync` 二进制拉取，避免旧版 Android 终端换行符转换破坏 PNG 数据，必须至少传入 `outputPath` 或开启 `includeBase64`。

## 错误码与排障建议

- `400`（`invalid_adb` / `invalid_adb_key`）：未指定 `target`、未知设备别名、目标地址/键名/路径格式非法，或触发电源安全拦截。
- `401`（`adb_unauthorized`）：设备尚未信任当前 RSA 公钥；首次触发任意操作时服务端会自动推送公钥并等待 `authTimeout`，提示用户在设备屏幕上点击允许调试后重试即可。
- `403`（`adb_forbidden`）：远端目录无读写权限或文件系统只读（普通应用可写暂存目录优先使用 `/data/local/tmp`）。
- `404`（`adb_not_found`）：本地待上传文件或远端目标路径不存在。
- `413`（`adb_limit`）：输出或文件体积超出配置上限。
- `502` / `504`（`adb_unreachable` / `adb_remote_error` / `adb_timeout`）：设备离线、网络调试未开启或命令超时，先核查连通性与 `devices` 状态。
