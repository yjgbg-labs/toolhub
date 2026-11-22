---
name: mihomo
description: 查看、启停、重启、校验、热重载或回滚路由器上的mihomo服务与配置时使用
---

# Mihomo

当用户要求查看、启动、停止、重启、编辑、校验、热重载、回滚或排查路由器上的 mihomo 时，使用本 skill。

## 核心约定

- 统一使用 `groot_codemode` 的 `tools.mihomo.*` 或命令行 `groot-cli mihomo`，不要直接调用本机 mihomo 或裸跑 SSH 覆盖配置。
- 服务固定绑定已注册的 `r6s` Shell 环境，默认配置为 `/etc/mihomo/config.yaml`（目录 `/etc/mihomo`）。
- 修改前先读取状态、日志或配置；修改后再次验证。
- 不在回复中泄露密码、订阅 URL、token、secret、uuid 或私钥。
- 简单静态 DNS `hosts:` 条目管理使用 `dns` skill（`tools.mihomo.dns.*`）。
- 代理组查询、节点切换、延迟测试、查看活动连接使用 `proxy` skill（`tools.mihomo.proxy.*`）。

## Code Mode 调用（推荐）

调用前可先用 `search({namespace: "mihomo"})` 确认最新签名：

```js
// 状态与日志
const status = (await tools.mihomo.status()).body;
const logs = (await tools.mihomo.logs({lines: 100})).body;

// 服务启停
await tools.mihomo.start();
await tools.mihomo.stop();
await tools.mihomo.restart();

// 配置读取、预校验、热重载写入与回滚
const yaml = (await tools.mihomo.config.read()).body;
const check = (await tools.mihomo.config.validate(nextYaml)).body;
const writeRes = (await tools.mihomo.config.write(nextYaml)).body;
const rollbackRes = (await tools.mihomo.config.rollback()).body;
```

## CLI 常用命令

```bash
groot-cli mihomo --help
groot-cli mihomo status
groot-cli mihomo logs --lines 100
groot-cli mihomo logsFollow --lines 100
groot-cli mihomo config read
groot-cli mihomo config validate --body @./config.yaml
groot-cli mihomo config write --body @./config.yaml
groot-cli mihomo config rollback
groot-cli mihomo start
groot-cli mihomo stop
groot-cli mihomo restart
```

`logsFollow` 为仅 CLI 暴露的 SSE 实时流，持续输出日志直到断开，只在用户明确要求实时追踪日志时使用。

## 配置写入与回滚机制

`mihomo.config.write` 在服务端互斥锁保护下自动完成以下流程：

1. 对比远端现有内容，无变化时直接返回 `{changed: false}`，不写盘也不重载。
2. 上传至远端临时文件并执行 `mihomo -t -d /etc/mihomo` 校验；校验失败返回 400，原配置保持不变。
3. 备份原配置为 `/etc/mihomo/config.yaml.bak.<UTC时间戳>`（自动保留最近 20 份），原子替换正式配置。
4. 若 `mihomo.service` 正在运行且控制端地址未变，调用 Mihomo REST `PUT /configs?force=true` 热重载；否则通过 `systemctl start/restart` 生效。

`mihomo.config.rollback` 会恢复最新一份 `.bak.*` 备份，经 `mihomo -t` 校验通过后热重载或重启服务。只有用户明确要求，或刚写入的配置确认异常时才执行。

## 排障顺序与注意事项

1. 调用 `tools.mihomo.status()` 查看 `activeState`、`mainPid` 与 `version`。
2. 调用 `tools.mihomo.logs({lines: 100})` 检查近期报错。
3. 必要时调用 `tools.mihomo.config.read()` 检查配置，向用户展示时必须对敏感字段打码。
4. `stop`、`restart`、`config.write` 和 `config.rollback` 会影响路由器代理流量，执行前说明影响；命令失败先报告原始错误要点。
