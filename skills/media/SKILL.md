---
name: media
description: 播放、控制或排查本机DLNA与媒体渲染时使用
---

# Media 与 DLNA

播放、控制或排查本机 DLNA 投屏与本地音视频渲染时使用本 skill。

## 服务边界与控制能力

- **唯一常驻服务**：用户级服务 `groot-media.service` 是唯一的媒体宿主进程，内部统一托管 DLNA 接收端、PlaybackService、mpv 与 Weston，禁止额外启动独立的旧版 DLNA 服务进程。
- **支持的控制动作**：查询当前播放状态、传入直链或通过标准输入提交结构化请求启动播放、暂停、恢复、按秒数定位进度（如定位至 `120` 秒）以及停止播放。
- **敏感鉴权保护**：包含 Bilibili 等平台的 access key 或带签名媒体地址的结构化播放请求必须通过标准输入传递，禁止将鉴权参数直接拼入命令行参数或写入日志。

## 显示与渲染规则

- **双屏独立**：Linux 主机独占自有显示器，Windows 主机使用另一台独立显示器；不存在共享屏幕切换、DDC 输入源仲裁或独立的显示管理服务。
- **合成器生命周期**：Weston 随 `groot-media.service` 常驻并在异常退出后自动拉起；暂停、停止、播放结束（EOF）、播放报错或 mpv 进程退出仅影响当次播放，Weston 保持常驻直至媒体服务停止。
- **播放前置条件**：每次加载媒体前必须确认合成器已持有 DRM connector；合成器不可用时当次播放 generation 直接失败并返回 `playState` 为 `6`。
- **并发隔离**：旧播放 generation 的异步清理流程不得干扰新发起的播放实例。

## 关键路径、前缀与排障要点

- **环境配置文件**：`~/.config/groot/services/media.env`。
- **组件日志路径**：`~/.config/groot/dlna/groot-dlna-mpv.log`、`~/.config/groot/dlna/groot-dlna-weston.log`；排查服务级异常时同时检查用户级 `groot-media.service` 状态及近期日志（如最近 `200` 行）。
- **音频设备命名前缀**：PipeWire 输出设备必须携带 `pipewire/` 前缀，ALSA 输出设备必须携带 `alsa/` 前缀。
- **中断保护**：当前存在活动播放时，除非用户明确接受中断，否则不要重启 `groot-media.service`。
