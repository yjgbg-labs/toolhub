---
name: dns
description: 配置mihomo的DNS hosts解析时使用
---

# DNS

当用户要求查看、添加、替换或删除路由器 mihomo 配置里的 DNS hosts 解析时，使用本 skill。

## 核心原则

- 这个环境里的 DNS hosts 管理入口是 `groot mihomo dns`。
- 默认 SSH 目标是 `root@10.0.0.1`。
- 默认远端配置文件是 `/etc/mihomo/config.yaml`。
- 只处理 mihomo YAML 顶层 `hosts:` 下的简单静态解析条目。
- 不要为了简单 hosts 解析去整文件编辑 mihomo 配置。

## 先确认用法

查看帮助：

```bash
groot mihomo dns --help
```

已知帮助信息：

```text
Usage: groot mihomo dns <action> [args...]

Actions:
  list                  List entries under hosts:
  new <host> <ip>       Add or replace an entry under hosts:
  delete <host>         Delete an entry under hosts:
```

## 常用命令

列出当前 hosts 解析：

```bash
groot mihomo dns list
```

添加或替换一条解析：

```bash
groot mihomo dns new example.com 1.2.3.4
groot mihomo dns new test.lan 10.0.0.10
```

删除一条解析：

```bash
groot mihomo dns delete example.com
```

指定其他 SSH 目标：

```bash
groot mihomo --host root@10.0.0.2 dns list
groot mihomo --host root@10.0.0.2 dns new example.com 1.2.3.4
groot mihomo --host root@10.0.0.2 dns delete example.com
```

## 工作流

添加或修改解析时：

1. 先运行 `groot mihomo dns list` 查看现有条目。
2. 确认用户要添加或替换的域名和 IP。
3. 运行 `groot mihomo dns new <host> <ip>`。
4. 如有必要，运行 `groot mihomo dns list` 确认结果。

删除解析时：

1. 先运行 `groot mihomo dns list` 确认条目存在。
2. 运行 `groot mihomo dns delete <host>`。
3. 如有必要，运行 `groot mihomo dns list` 确认结果。

## 实现细节

`groot mihomo dns` 当前实现要点：

- 会通过 `scp` 拉取远端 `/etc/mihomo/config.yaml` 到本地临时目录。
- `list` 解析 YAML 顶层 `hosts:` 块，输出 `<host>\t<value>`。
- `new` 会添加或替换 `hosts:` 下两空格缩进的条目，格式类似 `  'example.com': 1.2.3.4`。
- `delete` 会删除匹配 host 的简单条目。
- 修改后会上传到远端临时路径，执行 `mihomo -t -d /etc/mihomo -f <临时配置>` 校验。
- 校验成功后会备份原配置为 `/etc/mihomo/config.yaml.bak.YYYYMMDDHHmm`，覆盖正式配置并重启 mihomo。

## 注意事项

- `new` 是添加或替换，不是只添加；如果 host 已存在会覆盖它的值。
- `delete` 只按 host 名删除；删除前先确认用户要删的是哪一条。
- 如果用户要改的是完整 DNS 策略、nameserver、fake-ip、规则集或代理规则，这不属于简单 hosts 管理，应切换到 `mihomo` skill 处理配置编辑。
- 如果 SSH 目标不是默认路由器，使用 `--host <user@host>`，不要猜测。
- 命令会在修改成功后重启 mihomo；执行写操作前说明会影响路由器代理服务。
