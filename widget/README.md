# 原生 Widget

服务端集中管理状态，macOS AppKit 和 Windows WinForms 原生客户端通过一个 SSE 接口接收完整快照。
客户端代码位于根目录 `widget/`，与 `server/`、`cli/`、`plugin/` 并列。

## 使用

```sh
groot-cli widget set --body '{"key":"download","label":"下载","value":"完成","color":"green","order":10}'
groot-cli widget set --body '{"key":"network","label":"网络","color":"yellow"}'
groot-cli widget show
groot-cli widget delete --key network
groot-cli widget config read
groot-cli widget config write --body '{"width":360,"fontSize":14,"rowHeight":32}'
groot-cli widget subscribe
```

Code Mode / GraalJS 使用普通声明生成的 SDK：

```js
await tools.widget.set({key: "download", label: "下载", value: "完成", color: "green", order: 10});
const settings = (await tools.widget.config.read()).body;
await tools.widget.config.write({...settings, width: 360});
await tools.widget.delete({key: "download"});
```

`set` 和 `config.write` 仅声明请求体，JavaScript 直接接收请求体对象。
`subscribe` 声明 `exposeOn(cli)`，原生客户端直接使用 HTTP；其他五个命令默认三端可用。

- item 的 `key` 为 1–128 个 ASCII 字母、数字、点、下划线或连字符，首字符必须是字母或数字。
- `label` 必填，去除两端空白后最多 24 个 Unicode 码点；与 `value` 合计最多 40 个码点，内容为单行文字。
- `value` 省略、`null` 或空白时显示红黄绿三灯，否则按 `color` 显示文本值；`color` 默认 `green`。
- `order` 默认 0，按 `(order, key)` 排序。`set` 完整替换该 key；`delete` 返回是否存在。
- 最多 256 项，持久化快照最多 512 KiB；没有 TTL。断线保留最后收到的内容，并显示橙色连接提示。

## 状态与协议

`$SPROUT_HOME/widget/widget.sqlite3` 是唯一持久化真源，使用 SQLite WAL、FULL 同步和进程所有权锁。
items、settings、revision 作为一个快照原子提交，成功后才通知订阅者。无需 Redis。

```http
GET /widget/subscribe
Content-Type: text/event-stream

data: {"revision":"42","serverTime":"2026-10-31T12:00:00Z","items":[],"settings":{"x":null,"y":null,"width":440,"topMost":true,"opacity":0.96,"fontSize":14,"rowHeight":40}}

```

连接立即发送最新完整状态；变化时推送，空闲时每 15 秒发送当前快照保活。
`revision` 为持久化整数的十进制字符串，避免 JavaScript 整数精度问题。
慢订阅者可合并中间更新，重新连接时直接接收完整状态，不依赖 `Last-Event-ID` 或增量回放。
两个客户端均使用逐字节、有界的 SSE 解析，支持 UTF-8、CR/LF/CRLF、BOM 和多行 data；断开的未完整事件丢弃。
连接无数据 45 秒超时，失败按 1/2/4/8/16/30 秒加抖动重连，收到有效快照后重置退避。

## 窗口配置

`widget.config.write` **完整替换**配置，省略字段恢复默认值：

| 字段 | 默认值 | 约束 |
| --- | --- | --- |
| `x`, `y` | `null` | 同时设置或同时为空；相对主显示屏左上角的逻辑点，范围 ±65536 |
| `width` | `440` | 240–1600 逻辑点 |
| `topMost` | `true` | 是否置顶 |
| `opacity` | `0.96` | 0.2–1 |
| `fontSize` | `14` | 8–32 逻辑点 |
| `rowHeight` | `40` | max(24, fontSize+8)–120 逻辑点 |

位置为空时显示在主屏幕右上角；屏幕变化后将不可见的位置限制到可用区域。
深色圆角无边框、等宽字体、文本值或三色灯；高度随项目数量变化，超出屏幕高度时滚动。
Mac 背景使用 `NSVisualEffectView` 的深色 HUD 毛玻璃材质，实时模糊窗口背后的内容，失去焦点后仍保持效果。
背景与内容统一使用 16 点圆角；独立的背景蒙版裁剪毛玻璃，40% 深色叠层减少透底。
文字和灯在透明前景层绘制；`opacity` 仍控制整个窗口，设为 `1` 可保持前景清晰，背景由系统材质提供半透明效果。
拖动内容区域移动窗口，菜单栏图标仅提供“显示 / 隐藏”。
拖动结束通过 `config.write` 回写完整配置；本地写入串行化并合并连续手势，失败自动重试。
多个客户端共享配置，最后一次成功写入生效。客户端本地仅保存服务连接地址，不缓存状态或窗口配置。

## 构建与部署

目标 Mac 需要 macOS 13+、Xcode Command Line Tools/Swift 5.9+、可用 SSH 和已登录的桌面会话。
`config.env` 默认配置：

```sh
WIDGET_MAC_HOST=weichenglong@100.64.0.5
WIDGET_BASE_URL=http://100.64.0.3:18080
```

在项目根目录执行：

```sh
groot-cli jobs --job server-deploy
groot-cli jobs --job widget-deploy
```

源码归档按 SHA-256 定址上传至 Mac 的 `~/Library/Caches/groot-widget/<hash>/`，校验后在 Mac 编译和 ad-hoc 签名。
构建产物回传为 `widget/dist/GrootWidget-macos.tar.gz`，再次校验 SHA-256。
安装使用目标机现有构建产物，更新失败时恢复旧应用和启动配置。

- 应用：`~/Applications/GrootWidget.app`
- 连接：`~/Library/Application Support/GrootWidget/connection.json`，内容 `{"baseURL":"http://100.64.0.3:18080"}`
- 登录启动：`~/Library/LaunchAgents/lab.yjgbg.groot-widget.plist`
- 日志：`~/Library/Logs/GrootWidget/app.log`

LaunchAgent 在 Aqua 桌面会话启动，异常退出后自动重启。
手动启动可用 `open ~/Applications/GrootWidget.app`；手动恢复登录任务可用
`launchctl kickstart gui/$(id -u)/lab.yjgbg.groot-widget`。

设置 `WIDGET_DIAGNOSTICS=1` 部署可启用 `status.json` 和 `widget.png`（仅绘制自身窗口）用于验证。
默认关闭；日志仍记录连接状态和已应用快照的 revision、项目数及窗口大小。

### Windows

Windows 客户端位于 `widget/windows/`，使用现有 .NET 6 Windows Desktop 运行时与 SDK；不依赖 Redis、浏览器或 WebView。
Windows 需要已登录的桌面会话、可用 `groot windows exec` 桥接和挂载在 `WIDGET_WINDOWS_MOUNT`（默认 `/mnt/windows-c`）的 C: 盘。
Windows 必须能直接访问 `WIDGET_BASE_URL`；使用 `100.64.0.3` 时应接入同一 Tailscale/Headscale 网络。

```sh
groot-cli jobs --job widget-windows-test
groot-cli jobs --job widget-windows-deploy
```

任务将源码按 SHA-256 上传到 `%LOCALAPPDATA%\GrootWidget\build\<hash>`，在 Windows 测试和构建，校验并回传
`widget/dist/GrootWidget-windows.zip`。新产物构建成功后才替换运行中的程序，安装失败会恢复旧程序、连接文件和登录任务。

- 应用：`%LOCALAPPDATA%\GrootWidget\app\GrootWidget.exe`
- 连接：`%APPDATA%\GrootWidget\connection.json`，内容 `{"baseURL":"http://100.64.0.3:18080"}`
- 登录启动：任务计划程序 `GrootWidget`，仅在交互桌面会话运行，普通用户权限
- 日志：`%APPDATA%\GrootWidget\app.log`，超过 1 MiB 滚动为 `.log.1`

托盘仅提供“显示 / 隐藏”。保留深色等宽字体、拖动、圆角、置顶、DPI 缩放和自动高度；内容超出屏幕时使用鼠标滚轮。
Windows 11 22H2+ 使用 DWM 原生 Desktop Acrylic 亚克力背景；文字和灯以独立透明前景绘制，不支持时使用深色背景。
应用和托盘使用 [OpenCode 官网](https://opencode.ai/brand) 提供的黑色方形图标，原始资产和 Windows 多分辨率图标位于 `windows/Assets/`。
灯尺寸为亮灯 16 / 灭灯 12 逻辑点，灭灯色 `#080A0C`。连接断开时保留最后快照并显示橙色提示，自动退避重连。
拖动结束只回写完整共享配置；自动限制窗口到 Windows 可见区域不会回写位置。Mac 和 Windows 的配置仍以服务端为唯一真源。
`WIDGET_DIAGNOSTICS=1` 部署会额外输出自身窗口的 `widget.png`，默认关闭。
