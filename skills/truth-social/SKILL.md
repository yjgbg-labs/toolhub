---
name: truth-social
description: 查看或总结特朗普及其政治阵营的Truth Social发言时使用
---

# Truth Social

只读查询 Truth Social 上的公开政治发言，重点关注 Donald Trump、其政府与竞选团队、盟友及相关政治人物。发布、ReTruth、点赞、关注、私信、登录和修改账号不属于本 skill。

## 数据源

- 官方站点：`https://truthsocial.com`。
- 官方公开接口沿用 Mastodon 风格，但可能被 Cloudflare 拦截。
- Trump 官方 RSS：`https://truthsocial.com/@realDonaldTrump.rss`。
- Trump 归档：`https://trumpstruth.org`，仅收录 `@realDonaldTrump`。
- Trump 归档 RSS：`https://trumpstruth.org/feed`。
- 归档说明：`https://trumpstruth.org/about` 和 `https://trumpstruth.org/faq`。

`trumpstruth.org` 由 Defending Democracy Together 维护，与 Truth Social、Trump 及其竞选团队无隶属关系。它通常每几分钟检查新帖，并补充视频转录和 AI 生成的图片描述，但可能存在缓存、延迟或识别错误。

查询 Trump 时先请求官方 RSS；若遇到 `403`、Cloudflare HTML、超时或非 RSS 响应，立即回退到归档 RSS，不要重试绕过。归档 RSS 由第三方站点直接提供，客户端不访问 Truth Social，因此通常不受 Truth Social Cloudflare 限制。

不要向用户索取 Truth Social 密码、Cookie、访问令牌或双重验证信息，也不要绕过 Cloudflare 挑战。

## 查询流程

1. 从请求中提取人物、handle、时间范围、关键词和用户时区；“最近 N 天”默认解释为滚动的 N×24 小时。
2. 先确认账号身份。不要仅凭显示名认定账号属于某位政治人物，也不要把一次转发自动解释为政治归属或背书。
3. 查询 Trump 时按“官方 RSS、归档 RSS、归档搜索与详情页”的顺序读取；查询其他人物时先尝试官方公开页面或接口。
4. 只取回答所需的时间和页数。必须翻页到跨过时间下界，才能把结果称为完整范围。
5. 按原始帖子 ID 去重，区分原创、引用、回复、ReTruth、链接、图片和视频。

## Trump 时间线

先尝试官方 RSS：

```text
GET https://truthsocial.com/@realDonaldTrump.rss
```

只有 HTTP 成功、响应是有效 RSS/XML 且包含预期账号内容时才使用。官方 RSS 不支持日期筛选时，在客户端按 `pubDate` 过滤；若返回 Cloudflare 页面，不要把 HTML 当作空时间线。

官方 RSS 不可用时，按日期读取归档 RSS：

```text
GET https://trumpstruth.org/feed?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD
```

归档 RSS 关键字段：

- `pubDate`：UTC 发布时间。
- `link`：归档详情页。
- `description`：正文 HTML，图片或视频帖可能为空。
- `truth:originalUrl`：原始 Truth Social URL。
- `truth:originalId`：原始帖子 ID。

日期参数按自然日筛选。查询滚动时间窗时，应多取覆盖边界的自然日，再按 `pubDate` 和精确时间戳过滤，不能直接把日期结果当成 72 小时结果。

关键词与媒体内容搜索：

```text
GET https://trumpstruth.org/search?query=TERM&sort=desc&per_page=100&start_date=YYYY-MM-DD&end_date=YYYY-MM-DD&removed=include
```

- 使用 URL 编码传递关键词和参数。
- 搜索范围包含正文、视频转录和图片描述，命中图片描述时明确说明它由归档生成。
- `removed=include` 会包含归档记录中的已删除帖子；不能把归档可见误写成原帖当前仍在线。
- 详情页 `/statuses/{archive_id}` 可用于查看完整正文、媒体描述和原始链接。

## 其他政治人物

确认 handle 后，可以尝试以下官方接口：

```text
GET https://truthsocial.com/api/v1/accounts/lookup?acct={handle}
GET https://truthsocial.com/api/v1/accounts/{id}/statuses?limit=40
```

只有 HTTP 成功且响应为预期 JSON 时才使用结果。当前环境可能返回 Cloudflare `403`；此时停止直接请求，不要伪造结果、套用 Trump 专用归档或通过代理和登录凭据规避。

若官方接口不可读，可以使用公开新闻、网页缓存或可靠归档回答，但必须标明这是间接来源、实际覆盖范围和更新时间。没有可验证来源时，直接说明暂时无法读取该人物的时间线。

关注 Trump 政治势力时，可从其提及、引用和 ReTruth 扩展到政府官员、候选人、竞选组织及媒体盟友，但每个账号都要独立确认身份。默认不要把完整关注列表或所有互动对象都抓取为“阵营成员”。

## 时间与统计

- 归档页面显示 `America/New_York` 时间，RSS 的 `pubDate` 使用 UTC；回答前统一转换到用户时区。
- ReTruth 可能同时出现原帖和转发记录；统计时按原始 ID 和行为类型去重，并说明是否计入 ReTruth。
- 纯图片、视频或链接帖不能仅因正文为空而丢弃，应查看归档详情页后再归类。
- 对多页或日期查询说明实际范围、结果数量、是否包含已删除内容以及是否仍有下一页。

## 结果表达

- 先总结主要议题，再列出最重要的原话、时间和来源。
- 同时提供原始 Truth Social URL 和可访问时的归档详情页。
- 使用“Trump 称”“该帖子宣称”“他转发了”等措辞区分发言与事实。
- 发帖只能证明账号发布过该内容，不能证明其中的选举、战争、经济或犯罪主张属实。
- 用户要求事实核查时必须查询独立来源，不能用 Trump 归档或其转发文章相互印证。
- 图片描述和视频转录可能出错；涉及精确数字、威胁、政策或引语时，优先核对原始媒体。

## 失败处理

- 官方 RSS 或站点 `403`：Trump 查询回退到归档 RSS，其他账号报告能力边界。
- 官方站点 `404`：可能是删除、handle 错误或账号不可用，无法区分时不要武断推断。
- 归档无结果：检查日期、时区、分页和媒体帖后再报告；不要把一次空页描述为确定没有发帖。
- 归档延迟或不可用：说明第三方服务状态，不要把故障解释为内容不存在。
- 来源之间冲突：优先保留原始帖子 ID、精确时间和原文，并明确指出差异。
