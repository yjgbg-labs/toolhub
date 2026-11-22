---
name: proxy
description: 查看或切换mihomo代理节点、测延迟、查活动连接时使用
---

# Proxy

当用户要求查看代理组和节点、切换节点、测试节点延迟或查看 mihomo 活动连接时，使用本 skill。

## 核心约定

- 统一使用 `groot_codemode` 的 `tools.mihomo.proxy.*` 或命令行 `groot-cli mihomo proxy`，不要手工拼 `curl` 或在脚本中硬编码 `secret`。
- 控制端地址与认证凭据由 Groot 服务端自动从路由器 `/etc/mihomo/config.yaml` 解析并缓存，不要让用户提供。
- 只有 `Selector` 类型的代理组支持手动切换（`use`）；`URLTest` / `Fallback` / `LoadBalance` 组由 mihomo 自动选路，手动切换会返回 409。
- 节点名包含 emoji 和空格（如 `🇺🇸 美国 09 家宽`），传参时必须原样保留，禁止截断或改写。
- 永久修改代理组、分流规则或订阅走 `mihomo` skill；管理静态 `hosts:` 解析走 `dns` skill。

## Code Mode 调用（推荐）

```js
// 1. 列出所有代理组摘要（名称、类型、当前节点、节点数）
const groups = (await tools.mihomo.proxy.groups()).body;

// 2. 查看指定组内的候选节点及最近延迟
const detail = (await tools.mihomo.proxy.nodes({group: "SELECT"})).body;

// 3. 切换 Selector 组到指定候选节点（即时生效，不改 config.yaml）
const switched = (await tools.mihomo.proxy.use({group: "SELECT", node: "🇭🇰 香港 01"})).body;

// 4. 并发测速：传 groups 测指定组当前节点，不传测全部组当前节点，传 group 测单组内全部节点
const pairDelay = (await tools.mihomo.proxy.delay({groups: ["AUTO", "AI"]})).body;
const groupDelay = (await tools.mihomo.proxy.delay({group: "HK", timeoutMs: 5000})).body;

// 5. 查看活动连接（按总流量降序，支持按 host / rule 子串过滤与 limit 截断）
const conns = (await tools.mihomo.proxy.connections({host: "google", limit: 30})).body;
```

## CLI 常用命令

```bash
groot-cli mihomo proxy groups
groot-cli mihomo proxy nodes --group SELECT
groot-cli mihomo proxy use --group SELECT --node "🇭🇰 香港 01"
groot-cli mihomo proxy delay
groot-cli mihomo proxy delay --groups AUTO --groups AI
groot-cli mihomo proxy delay --group HK
groot-cli mihomo proxy connections --limit 50
```

## 工作流与注意事项

- **切换节点**：先查 `groups` 确认目标组为 `Selector`，再查 `nodes` 核对候选节点全名，最后调用 `use` 切换。`use` 仅修改运行时状态，若需永久调整默认策略请用 `mihomo` skill 修改配置。
- **排查慢速**：调用 `delay({group: "<组名>"})` 对比组内各节点延迟，超时节点会返回 `delayMs: null` 与 `error` 原因，不会中断整批测速。
- `delay` 默认探测 `http://www.gstatic.com/generate_204`、超时 `5000` 毫秒，可通过 `url` 与 `timeoutMs` 调整。
- 若返回控制端不可达错误，先用 `tools.mihomo.status()` 检查 `mihomo.service` 是否处于运行状态。
