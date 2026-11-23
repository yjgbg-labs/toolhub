---
name: twitter
description: 查看或监控X/Twitter上的推文、用户、时间线、线程和趋势数据时使用
---

# Twitter

通过 Groot 服务端的 `fxTwitter` 命名空间匿名只读访问 X/Twitter 公开数据（底层对接 `https://api.fxtwitter.com` 的 `/2/` 接口）。仅处理公开内容的查询、监控与整理；发布、点赞、关注、私信、登录以及全站关键词搜索（当前上游匿名接口返回 `503` 不可用）不属于本能力范围。

## 核心约定与安全边界

- 统一使用 Groot 服务端的 `fxTwitter` 命名空间执行操作，调用前先按需检索最新签名，无需自行拼接底层 HTTP 请求。
- 公共接口免鉴权，严禁向用户索取 X 账号密码、Cookie、`auth_token` 或 `ct0`。
- 单次上游请求超时固定为 `25s`（超时返回 `504`），解压后响应上限为 `4 MiB`（超限返回 `413`）。服务端同时校验 HTTP 状态码与返回 JSON 顶层的 `code` 字段。

## 关键参数、输入兼容与分页约定

- **推文标识 `id`**（用于 `status`、`thread`、`conversation`、`reposts`）：支持直接传入 `2`–`20` 位纯数字推文 ID，或传入完整推文链接（自动从路径中提取数字 ID）。
- **用户标识 `handle`**（用于 `user`、`about`、`timeline`、`followers`、`following`）：支持带或不带 `@` 的用户名（`1`–`15` 位字母、数字或下划线），或用户主页链接。
- **用户时间线 `timeline`**：
  - `tab`：默认 `statuses`，可选 `statuses`、`media`、`articles`；遇到时间线内的聚合线程会自动展开为推文列表。
  - `count`：默认 `20`，范围 `1`–`100`。
  - `withReplies`：默认 `false`（仅在 `statuses` 标签生效）。
  - `since`：可选非负 Unix 秒级时间戳下界（仅在 `statuses` 标签生效），适合配合条件触发器做新推文增量监控。
- **对话与关系分页**：
  - `conversation` 的 `rankingMode` 默认 `likes`（可选 `likes`、`recency`），`limit` 默认 `30`（范围 `1`–`100`）。
  - 分页响应包含 `cursor.top` 与 `cursor.bottom`，请求下一页时将 `cursor.bottom` 传入 `cursor` 参数，按需拉取最少页数。
- **趋势与翻译**：
  - `trends` 的 `count` 默认 `20`，范围 `1`–`50`。
  - `status`、`thread`、`conversation`、`timeline` 均支持可选语言代码 `lang`（如 `zh`、`en`、`ja`），命中时填充在 `translation` 字段。
  - 未来上游新增或恢复的 `/2/*` 路径可通过 `fxTwitter.api` 传入相对 `path` 与 `query` 兜底调用。

## 结果整理与状态码处理

- 整理结果时优先给出推文 `url`、作者 `screenName`、发布时间与精确计数，明确区分原创推文、转推（`repostedBy` 非空）、引用推文（`quote`）、回复（`replyingTo`）、长文章（`article`）及社区备注（`communityNote`），不主动下载大体积媒体文件。
- **错误与占位状态**：
  - `tombstoneReason` 非空或返回 `404`（`fx_twitter_not_found`）：区分已删除、账号封禁、私密或不可见，不在对话链中静默丢弃。
  - `403`（`fx_twitter_forbidden`）：受保护或受限账号，公共接口无法读取。
  - `429`（`fx_twitter_rate_limited`）：触发上游每分钟 `1000` 次 IP 限流，立即停止并拉长轮询间隔，禁止并发重试。
  - `502` / `503`（`fx_twitter_upstream` / `fx_twitter_unavailable`）：区分上游故障或未开放特性（如全站搜索），结合状态页 `https://status.fxtwitter.com` 说明原因，不误报为内容不存在。
