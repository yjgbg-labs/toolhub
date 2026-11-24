---
name: telegram
description: 收发Telegram机器人消息、媒体附件、会话与更新轮询时使用
---

# Telegram

当用户要求通过 Telegram Bot 发送通知、编辑或置顶消息、上传图片与文件、下载附件、轮询收到的私聊或群组消息、管理会话及监听回调事件时，使用本 skill。

## 核心约定

- 统一通过 Groot 服务端的 `telegram` 命名空间及其子命名空间（`telegram.chat`、`telegram.webhook`）执行，调用前先按需检索最新工具签名。
- 支持结合 `notify.when` 或 `schedule` 对 `telegram.updates`（开启 `unreadOnly` 与 `ack`）做增量轮询，实现外部消息触发与自动回复。
- 回复与日志摘要中严禁输出任何明文 `botToken` 或凭证文件内容。

## 配置、凭证与网络边界

- **热加载配置（`$GROOT_HOME/application.conf` 的 `telegram` 段）**：
  - `apiBase`：默认 `https://api.telegram.org`，可指向自建 Cloudflare Worker 反代地址以绕开共享代理出口限流。
  - `botToken`：Bot HTTP API 令牌，非空时优先于本地状态文件。
  - `defaultChatId`：默认发送目标（数字会话 ID 或公开频道 `@username`）。
  - `proxyUrl`：可选显式 HTTP/HTTPS 代理地址（为空时直连 `apiBase`）。
  - `timeout`：单次请求超时，默认 `30s`。
  - `maxResponseBytes` / `maxUploadBytes`：JSON 响应上限默认 `4 MiB`（范围 `1 KiB`–`64 MiB`），文件上传与下载上限默认 `50 MiB`（范围 `1 KiB`–`50 MiB`）。
  - `maxRateLimitWaitMs`：遇到上游 `429` 且返回 `retry_after` 不超过该阈值时自动等待重试（默认 `35000` 毫秒，最多重试 `2` 次）。
- **状态持久化（`$GROOT_HOME/telegram/state.json`，权限 `0600`）**：
  - 终端专用的 `login` 与 `logout` 接口不对 Code Mode 与 GraalJS 暴露，用于在未写入配置文件时校验并持久化令牌。
  - 自动维护已发现会话列表 `chats`（上限 `200` 条）、默认私聊目标 `defaultChatId` 与已确认游标 `lastUpdateId`；切换不同 Bot ID 时自动隔离游标与会话缓存。

## 关键参数、默认值与限制

- **目标会话自动推导**：
  - `send`、`edit`、`delete`、`pin`、`unpin`、`photo`、`document`、`chat.get` 均可省略 `chatId`。
  - 省略时按“显式参数 → 配置项 `defaultChatId` → 状态文件 `defaultChatId` → 唯一已知私聊 → 自动拉取一次 `updates` 发现私聊”的顺序解析；用户首次与新 Bot 交互前需先在客户端对 Bot 点击 Start 发送任意消息。
  - 需要固定默认目标时调用 `telegram.chat.use` 校验并落盘。
- **消息发送与编辑**：
  - `send` 与 `edit` 的正文上限 `4096` 字符；`parseMode` 默认 `plain`（可选 `plain`、`MarkdownV2`、`Markdown`、`HTML`）。
  - 支持传入二维内联按钮矩阵 `buttons`（每项含 `text` 及 `url` 或 `callbackData`）、回复引用 `replyToMessageId`、话题 `messageThreadId`、静默发送 `disableNotification` 与关闭链接预览 `disableWebPagePreview`。
- **更新轮询与游标**：
  - `updates` 的 `limit` 默认 `30`（范围 `1`–`100`），`timeoutSeconds` 默认 `0`（长轮询范围 `0`–`50` 秒）。
  - `unreadOnly` 默认 `false`，`ack` 默认 `false`；增量消费场景将两者均设为 `true`，服务端会自动从 `lastUpdateId + 1` 拉取并在成功后推进游标。
- **媒体上传、下载与底层接口**：
  - `photo` 与 `document` 的 `source` 为本地存在的文件路径（或以 `/`、`./`、`../`、`~/` 开头）时自动走 `multipart/form-data` 上传，否则作为远程 URL 或 `file_id` 发送。
  - `download` 按 `fileId` 下载文件；传入 `outputPath` 时落盘并返回绝对路径，未传且文件不超过 `256 KiB` 时内联返回 `contentBase64`。
  - 长尾 Bot API 方法（如设置命令菜单、发送输入状态、回应回调查询）通过 `telegram.api` 传入 `method` 与 JSON `payload` 调用。

## 错误码与排障建议

- `400`（`invalid_telegram`）：参数格式非法、文本超长，或尚未发现可用的 `chatId`（提示用户先向 Bot 发送一条消息或显式传入 `chatId`）。
- `401`（`telegram_unauthenticated`）/ `403`（`telegram_forbidden`）：未配置有效令牌，或用户已屏蔽该 Bot / 群组权限不足。
- `404`（`telegram_not_found`）：本地待上传文件、远端附件或目标方法不存在。
- `409`（`telegram_conflict`）：当前 Bot 绑定了 Webhook 导致无法调用 `updates`，先通过 `telegram.webhook.info` 检查并用 `telegram.webhook.delete` 解除。
- `429`（`telegram_rate_limited`）：超过自动等待上限的频率限制，降低轮询或发送频率，或检查 `apiBase` 反代配置。
