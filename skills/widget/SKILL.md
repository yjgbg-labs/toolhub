---
name: widget
description: 修改或排查 Groot Windows 原生悬浮窗时使用
---

# Groot 悬浮窗

用户提到“悬浮窗”或 `groot widget`，并要求修改内容、布局、状态或安装行为时使用。普通网页和其他 Windows 窗口不适用。

## 代码位置

- 命令入口：`lib/widget/index.ts`
- Redis 存储：`lib/widget/store.ts`、`lib/widget/redis.ts`
- 原生窗口：`lib/widget/windows-host/Program.cs`
- 原生绘制：`lib/widget/windows-host/WidgetCanvas.cs`
- Redis 轮询：`lib/widget/windows-host/RedisWidgetClient.cs`
- 状态模型：`lib/widget/windows-host/WidgetState.cs`
- Windows 安装：`lib/widget/install.ts`、`lib/widget/install-windows.ps1`

## 架构约定

- Redis 是 Widget 状态与设置的唯一真源，不保存本地 `widget.json` 或 Windows `settings.json`。
- `groot widget set/delete` 分别使用 `HSET/HDEL` 修改 `groot:widget:items` 的独立字段。
- `groot widget show` 将按面板顺序以表格输出 Redis 中的全部 item，供程序化读取。
- `groot widget config` 读写 Redis String `groot:widget:settings`。
- Windows 原生程序每 2 秒读取 Redis，断线后自动重建连接，不依赖 Linux HTTP 服务。
- Linux 使用 `groot` ACL 用户；Windows 使用受限的 `widget` 用户，只能读状态并回写设置。
- `value` 统一为文本；省略 value 时显示红黄绿三灯。

## 核心规则

- 延续深色、等宽字体、紧凑键值行的视觉语言，不擅自全面重设计。
- DPI 尺寸统一在 `WidgetCanvas` 中按 `DeviceDpi` 换算。
- TTL 过期后文本变灰，灯全部熄灭。
- 保留无边框、置顶、托盘、拖动、圆角和自动高度行为。
- 修改状态结构时同步检查 TypeScript 和 C# 模型及测试。
- 遵守仓库 `AGENTS.md`，源码只在新分支 worktree 中修改。

## 修改流程

1. 读取本次涉及的 TypeScript、C# 和测试，确认现状。
2. 使用 `apply_patch` 修改，每次补丁不超过 200 行。
3. 执行统一验证：

```bash
groot jobs build
```

4. C# 改动合并后重新安装原生程序：

```bash
groot widget install
```

5. 通过实际 Windows 截图检查 DPI、行高、文字基线、灯尺寸和自动高度。

## 验证

```bash
groot widget config read
groot windows exec 'Get-Process GrootWidget -ErrorAction Stop | Select-Object Name,Id,SessionId,Responding | ConvertTo-Json'
```

- 必须确认 Redis Hash 中存在最新状态，设置 String 可正常读取。
- Windows 进程必须位于交互会话并响应正常。
- 修改后执行一次 `set`，确认窗口无需重启即可更新。
- 重启 Windows 程序，确认能恢复 Redis 中最后状态和窗口设置。
