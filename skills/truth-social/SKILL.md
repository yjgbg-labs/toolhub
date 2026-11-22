---
name: truth-social
description: 查看或总结特朗普及其政治阵营的Truth Social发言时使用
---

# Truth Social

只读查询 Truth Social 上的公开政治发言，重点关注 Donald Trump、其政府与竞选团队、盟友及相关政治人物。发布、ReTruth、点赞、关注、私信、登录和修改账号不属于本 skill。

## 数据源与安全边界

- **官方站点与公开接口**：`https://truthsocial.com`（沿用 Mastodon 风格 API，可能受 Cloudflare 拦截）。
- **Trump 官方 RSS**：`https://truthsocial.com/@realDonaldTrump.rss`。
- **Trump 第三方归档**：`https://trumpstruth.org`（仅收录 `@realDonaldTrump`，归档 RSS 为 `https://trumpstruth.org/feed`，说明页为 `/about` 与 `/faq`）。该站点由第三方独立维护，通常数分钟内同步并附带视频转录与 AI 图片描述，可能存在缓存延迟或识别偏差。
- **合规底线**：查询 Trump 时优先访问官方 RSS，若返回 `403`、Cloudflare 验证页、超时或非有效 XML，立即回退至归档源，严禁尝试绕过 Cloudflare 挑战，也严禁向用户索取密码、Cookie、访问令牌或双重验证信息。

## 关键接口路径与参数

- **归档 RSS（`/feed`）**：支持自然日参数 `start_date` 与 `end_date`（格式均为 `YYYY-MM-DD`）。核心字段含 `pubDate`（UTC 时间）、`link`（归档详情地址）、`description`（正文 HTML，纯媒体帖可能为空）、`truth:originalUrl`（原帖地址）及 `truth:originalId`（原始帖子 ID）。
- **归档搜索（`/search`）**：查询参数包含 `query`（需做 URL 编码，匹配正文、视频转录与图片描述）、排序 `sort=desc`、每页条数 `per_page=100`、日期区间 `start_date` / `end_date`（`YYYY-MM-DD`），以及 `removed=include`（包含归档记录中的已删除帖子，不可将其误述为当前仍在线）；单帖详情路径为 `/statuses/{archive_id}`。
- **其他人物公开接口**：在官方域名下，账号检索路径为 `/api/v1/accounts/lookup`（查询参数 `acct` 为目标 handle），时间线路径为 `/api/v1/accounts/{id}/statuses`（单页条数 `limit` 可设 `40`）。若遇 `403` 拦截则立即停止直接抓取，改用公开新闻或可验证缓存并注明间接来源与覆盖范围。

## 查询、时区与去重规则

1. 提取人物、handle、时间窗、关键词及用户时区；“最近 N 天”默认按滚动 `N×24` 小时计算（调用按自然日过滤的归档接口时须向外多扩一天边界，再按精确时间戳裁剪，并翻页至跨过时间下界才可称为完整范围）。
2. 核实账号真实身份，不单凭显示名判定归属，也不将单次转发自动等同于政治背书。
3. 归档网页展示 `America/New_York` 时间，RSS `pubDate` 为 `UTC`，输出前统一换算至用户时区。
4. 按原始帖子 ID 去重，区分原创、引用、回复与 ReTruth；纯图片或视频帖须结合归档详情页归类，不可因正文为空而漏算。

## 结果表达与失败处理

- 先概括核心议题，再列出关键原话、精确时间及双链接（原始 Truth Social 链接与归档详情页），使用客观引述措辞区分个人主张与客观事实；事实核查必须引入独立外部信源。
- 官方 `403` 按规则回退或说明边界；`404` 区分不清删帖或错误 handle 时不做武断定论；归档为空或延迟时先核查时区、分页与纯媒体帖，不将服务异常描述为未发帖。
