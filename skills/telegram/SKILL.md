---
name: telegram
description: 收发Telegram机器人消息、媒体附件、会话与更新轮询时使用
---

# Telegram

当用户要求通过 Telegram Bot 发送通知、编辑或置顶消息、上传图片与文件、下载附件、轮询或订阅收到的私聊与群组消息、管理默认会话及监听回调事件时，使用本 skill。

## 核心约定

- 统一通过 Groot 服务端的 `telegram` 命名空间及其子命名空间（`telegram.chat`、`telegram.webhook`）执行，调用前先按需检索最新工具签名。
- 会话消息监听优先结合 `notify.when`（等待回复唤醒当前 AI 会话）或 `schedule`（常驻后台周期消费）调用 `telegram.updates` 并同时开启 `unreadOnly` 与 `ack`。
- 回复与日志摘要中严禁输出任何明文 Bot 令牌或状态文件中的敏感凭证。

## 配置、凭证与网络边界

- **热加载传输配置（`$GROOT_HOME/application.conf` 的 `telegram` 段，不含凭证与会话状态）**：
  - `apiBase`：默认 `https://api.telegram.org`，可指向自建 Cloudflare Worker 反代地址以绕开共享代理出口限流。
  - `proxyUrl`：可选显式 HTTP/HTTPS 代理地址（默认空字符串，直连 `apiBase`）。
  - `timeout`：单次请求超时，默认 `30s`。
  - `maxResponseBytes` / `maxUploadBytes`：JSON 响应上限默认 `4 MiB`（范围 `1 KiB`–`64 MiB`），文件上传与下载上限默认 `50 MiB`（范围 `1 KiB`–`50 MiB`）。
  - `maxRateLimitWaitMs`：遇到上游 `429` 且返回 `retry_after` 不超过该阈值时自动等待重试（默认 `35000` 毫秒，最多重试 `2` 次）。
- **凭证与会话持久化（`$GROOT_HOME/telegram/state.json`，权限 `0600`）**：
  - 机器人令牌与初始 `defaultChatId` 统一通过终端专用的 `login` 接口校验并写入，退出通过 `logout` 清除；这两个接口不对 Code Mode 与 GraalJS 暴露。
  - 自动维护已发现会话列表 `chats`（上限 `200` 条）、默认目标 `defaultChatId` 与已确认游标 `lastUpdateId`；登录切换不同 Bot ID 时自动隔离游标与会话缓存。

## 关键参数、默认值与限制

- **目标会话自动推导**：
  - `send`、`edit`、`delete`、`pin`、`unpin`、`photo`、`document`、`chat.get` 均可省略 `chatId`。
  - 省略时按“显式参数 → 状态文件 `defaultChatId` → 唯一已知私聊 → 自动拉取一次 `updates` 发现私聊”的顺序解析；需要修改固定默认目标时调用 `telegram.chat.use` 校验并落盘。
- **消息发送与编辑**：
  - `send` 与 `edit` 的正文上限 `4096` 字符；`parseMode` 默认 `plain`（可选 `plain`、`MarkdownV2`、`Markdown`、`HTML`）。
  - 支持二维内联按钮矩阵 `buttons`（每项含 `text` 及 `url` 或 `callbackData`）、回复引用 `replyToMessageId`、话题 `messageThreadId`、静默发送 `disableNotification` 与关闭链接预览 `disableWebPagePreview`。
- **更新轮询与会话订阅**：
  - `updates` 的 `limit` 默认 `30`（范围 `1`–`100`），`timeoutSeconds` 默认 `0`（长轮询范围 `0`–`50` 秒）。
  - `unreadOnly` 默认 `false`，`ack` 默认 `false`；增量订阅场景将两者均设为 `true`，服务端自动从 `lastUpdateId + 1` 拉取并在成功后推进游标（注意避免多个任务并发长轮询同一 Bot 的 `getUpdates`）。
- **媒体上传、下载与底层接口**：
  - `photo` 与 `document` 的 `source` 为本地文件路径时自动走 `multipart/form-data` 上传，否则作为远程 URL 或 `file_id` 发送。
  - `download` 按 `fileId` 下载文件；传入 `outputPath` 时落盘并返回绝对路径，未传且文件不超过 `256 KiB` 时内联返回 `contentBase64`。
  - 长尾 Bot API 方法（如设置命令菜单、发送输入状态、回应回调查询）通过 `telegram.api` 传入 `method` 与 JSON `payload` 调用。

## 错误码与排障建议

- `400`（`invalid_telegram`）：参数格式非法、文本超长，或尚未发现可用的 `chatId`（提示用户先向 Bot 发送一条消息或显式传入 `chatId`）。
- `401`（`telegram_unauthenticated`）/ `403`（`telegram_forbidden`）：未通过 `login` 登录有效令牌，或用户已屏蔽该 Bot / 群组权限不足。
- `404`（`telegram_not_found`）：本地待上传文件、远端附件或目标方法不存在。
- `409`（`telegram_conflict`）：当前 Bot 绑定了 Webhook 导致无法调用 `updates`，先通过 `telegram.webhook.info` 检查并用 `telegram.webhook.delete` 解除。
- `429`（`telegram_rate_limited`）：超过自动等待上限的频率限制，降低轮询频率或检查 `apiBase` 反代配置。
