---
name: proxy
description: 查看或切换mihomo代理节点、测延迟、查连接时使用
---

# Proxy

当用户要求查看代理组和节点、切换节点、测试节点延迟或查看 mihomo 活动连接时，使用本 skill。

## 核心约定

- 统一使用 `groot mihomo proxy`，不要直接 curl mihomo 的 REST API。
- API 地址和 secret 由命令自动从路由器 `/etc/mihomo/config.yaml` 读取，不要让用户提供。
- 默认目标是 `root@10.0.0.1`。
- 只有 `Selector` 类型的代理组可以手动切换；`URLTest` 组由 mihomo 按延迟自动选节点，切换会被拒绝。
- 节点名包含 emoji 和空格（如 `🇭🇰 Hong Kong丨04`），使用时必须原样引用，不要改写或截断。
- 修改配置文件（hosts、规则、订阅）走 `mihomo` 或 `dns` skill，不属于本 skill。

## 常用命令

```bash
groot mihomo proxy groups              # 列出代理组、类型、当前节点
groot mihomo proxy nodes <group>       # 列出组内节点，* 为当前节点
groot mihomo proxy use <group> <node>  # 切换 Selector 组到指定节点
groot mihomo proxy delay [group]       # 测延迟：不跟组名测各组当前节点，跟组名测组内全部节点
groot mihomo proxy connections         # 查看活动连接（HOST/NET/DOWN/UP/CHAIN/RULE）
```

指定其他路由器：

```bash
groot mihomo proxy --host root@10.0.0.2 groups
```

## 工作流

切换节点：

1. `groot mihomo proxy groups` 确认组名、类型和当前节点。
2. `groot mihomo proxy nodes <group>` 查看候选节点。
3. `groot mihomo proxy use <group> <node>` 切换。

排查慢速：

1. `groot mihomo proxy delay <group>` 比较组内节点延迟，失败节点显示 `fail (...)`。
2. 如需切换，按上面的流程操作。

## 注意事项

- `use` 是即时生效的运行时切换，不修改配置文件，mihomo 重启后会回到配置默认值；要永久改默认节点选择需走 `mihomo` skill 编辑配置。
- `delay` 默认用 `http://www.gstatic.com/generate_204`，可用 `--url`、`--timeout` 调整。
- 命令失败先报告原始错误；API 不通时检查 mihomo 是否在运行（`groot mihomo status`）。
