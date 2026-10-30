---
name: twitter
description: 查看或搜索X/Twitter上的推文、用户、线程和趋势数据时使用
---

# Twitter

通过 FxTwitter 官方公共实例只读访问 X/Twitter 数据。重点是查询和理解公开内容；发布、点赞、关注、私信、登录和修改账号不属于本 skill。

## 固定入口

- API 基址：`https://api.fxtwitter.com`。
- 当前 API 版本：`/2/`。
- 接口索引：`https://docs.fxembed.com/api/twitter/`。
- OpenAPI：`https://api.fxtwitter.com/2/openapi.json`。
- 服务状态：`https://status.fxtwitter.com`。

这是 FxTwitter 的公共实例，不是 X 官方开发者 API。公共读取不需要 API key，也不要向用户索取 X Cookie、密码、`auth_token` 或 `ct0`。

## 查询原则

1. 从用户输入提取推文 ID、用户名或搜索条件。推文 URL 中 `/status/` 后的数字就是 ID；用户名去掉开头的 `@`。
2. 优先选择满足需求的最窄接口，避免抓取不需要的时间线、回复或关注列表。
3. 已知基础入口可以直接请求；其他能力先读取接口索引，再按对应文档确认路径和参数，不要凭印象猜接口。
4. 优先使用 `webfetch` 请求 JSON。需要 URL 编码、查看响应头或精确控制分页时，可使用 `curl`，并用 `jq` 读取结果。
5. 检查 HTTP 状态和响应 JSON 的 `code` 字段；只有两者都成功时才把数据当作有效结果。

基础入口：

```text
GET /2/status/{id}                 获取单条推文
GET /2/profile/{handle}            获取用户资料
GET /2/thread/{id}                 展开作者线程
GET /2/conversation/{id}           展开线程和回复
```

不要在 skill 中穷举全部 API。搜索、趋势、用户时间线、媒体、引用、转推、关注关系等需求，从接口索引或 OpenAPI 动态查找当前定义。

## 请求示例

```bash
curl --fail-with-body --silent --show-error \
  'https://api.fxtwitter.com/2/status/20' | jq

curl --fail-with-body --silent --show-error \
  'https://api.fxtwitter.com/2/profile/jack' | jq
```

将用户提供的动态值放入 URL 前必须正确编码。使用 `curl` 查询带参数的接口时，优先使用 `--get --data-urlencode`，不要手工拼接包含空格、`#`、`&` 或非 ASCII 字符的查询词。

## 分页与限流

- 列表响应通常包含 `cursor.top` 和 `cursor.bottom`；下一页使用 `cursor.bottom` 作为 `cursor` 参数。
- 默认只取回答问题所需的最少页数。不要为了“可能有用”而遍历完整时间线、粉丝或关注列表。
- API v2 当前按来源 IP 限制为每分钟 1000 次。收到 `429` 时停止并说明限流，不要并发重试或通过代理规避。
- 对多页结果说明实际查询范围和是否仍有下一页，不能把部分结果描述为完整集合。

## 结果处理

- 优先使用响应中的 `status.url`、用户 handle、推文 ID 和精确时间标识来源。
- 区分正文、引用推文、回复、转推和媒体，不要把引用内容误归给当前作者。
- 点赞、转推、回复、粉丝等计数会变化；回答时避免暗示它们是永久值。
- 用户要求总结时忠实保留立场、否定和不确定性；事实核查需使用独立来源，不能把推文本身当作事实证明。
- 返回结果时附上原始 X/Twitter URL；如果查询了多页或使用搜索条件，也简要说明范围。
- 不主动下载媒体。需要分析图片或视频时，先使用 API 返回的媒体 URL，并尊重用户要求和工具能力。

## 失败处理

- `404` 或 tombstone：根据响应中的 `reason` 区分删除、私密、封禁、屏蔽或不可用；无法区分时不要武断推断。
- `401`、`403`：报告公共实例无法读取该内容，不要尝试获取或使用用户登录凭据绕过。
- `429`：报告限流并稍后重试。
- `5xx`：先检查服务状态；说明可能是 FxTwitter 或 X 上游故障，不要把它解释为内容不存在。
- 文档与实际响应不一致时，以当前 OpenAPI 和实际响应为准，并明确指出差异。
