---
name: media
description: 播放、控制或排查本机DLNA与媒体渲染时使用
---

# Media 与 DLNA

`groot media serve` 是唯一媒体进程，内部包含 DLNA、PlaybackService、mpv 和 Weston。不要另起 `groot dlna serve`。

## Jarvis 与本地控制

```bash
groot media status
groot media play '<direct-url>'
groot media play --stdin
groot media pause
groot media resume
groot media seek 120
groot media stop
```

结构化 Bilibili 请求从 stdin 传入，不把 access key 或签名 URL 写进日志和命令参数。

## 显示输出规则

- Linux 独占自己的显示器，Windows 用另一台，没有共享屏、没有 DDC 输入切换、没有 display 服务和 `groot display` 命令。
- Weston 随 Media 服务常驻，崩溃后自动重启。
- 每次 loadfile 前确认合成器占有 DRM connector，这是播放唯一的显示侧前置条件。
- 合成器不可用时该 generation 失败并回 `playState 6`。
- pause、stop、EOF、失败和 mpv 退出只影响播放，Weston 保持常驻直到服务退出。
- 旧 generation 的异步清理不得影响新播放。

## 排障

```bash
systemctl --user status groot-media.service --no-pager --full
journalctl --user -u groot-media.service -n 200 --no-pager
groot media status
```

还要检查：

```text
~/.config/groot/services/media.env
~/.config/groot/dlna/groot-dlna-mpv.log
~/.config/groot/dlna/groot-dlna-weston.log
```

PipeWire 设备必须使用 `pipewire/` 前缀，ALSA 设备使用 `alsa/` 前缀。播放中不要重启 Media，除非用户接受中断。
