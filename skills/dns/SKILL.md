---
name: dns
description: 查看、添加、修改或删除mihomo静态DNS hosts解析时使用
---

# DNS

当用户要求查看、添加、修改或删除路由器 mihomo 配置顶层 `hosts:` 下的静态域名解析时，使用本 skill。

## 核心原则

- 统一使用 `groot_codemode` 的 `tools.mihomo.dns.*` 或命令行 `groot-cli mihomo dns`。
- 仅操作 `/etc/mihomo/config.yaml` 顶层 `hosts:` 下的静态映射，保留原有注释与排版，不要为此整文件重写配置。
- `# external-dns-mihomo:start` 至 `# external-dns-mihomo:end` 之间的条目由 `external-dns-mihomo` 自动托管（`managed: true`）；默认禁止覆盖或删除托管条目（返回 409），除非用户明确确认传 `force: true`。
- 修改完整的 `dns:` 策略、nameserver、fake-ip 或分流规则不属于本 skill，应使用 `mihomo` skill。

## Code Mode 调用（推荐）

```js
// 列出全部静态 hosts 条目（含 managed 标记）
const hosts = (await tools.mihomo.dns.list()).body;

// 新增或更新静态解析（写在托管块之外，经 mihomo -t 校验后自动 PUT /configs?force=true 热重载）
const writeRes = (await tools.mihomo.dns.set({host: "nas.yjgbg.lab", value: "10.0.0.50"})).body;

// 删除静态解析
const delRes = (await tools.mihomo.dns.delete({host: "nas.yjgbg.lab"})).body;
```

## CLI 常用命令

```bash
groot-cli mihomo dns --help
groot-cli mihomo dns list
groot-cli mihomo dns set --host nas.yjgbg.lab --value 10.0.0.50
groot-cli mihomo dns delete --host nas.yjgbg.lab
```

## 工作流与注意事项

1. 修改或删除前先调用 `tools.mihomo.dns.list()` 查看现有条目及 `managed` 状态。
2. `set` 为幂等添加或替换：目标值未变化时返回 `{changed: false}`，不会触发备份或热重载。
3. `set` 和 `delete` 在变更时会先执行远端 `mihomo -t` 校验并创建备份，校验通过后通过 `PUT /configs?force=true` 热重载配置。
4. 若遇到 `409 mihomo_conflict`，说明目标域名由 `external-dns-mihomo` 托管，应提示用户通过对应的 Kubernetes Ingress/Service 注解修改，不要盲目加 `force: true`（否则下次同步仍会被覆盖）。
