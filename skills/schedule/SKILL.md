---
name: schedule
description: 创建、修改或排查Sprout定时任务及迁移groot调度时使用
---

# Sprout 定时任务

周期任务由 Sprout Scala HTTP 服务执行，CLI 和 OpenCode Code Mode 共用同一套 API。

## 核心约定

- 使用 `sprout schedule` 管理任务；一次性项目构建仍使用 `groot jobs`。
- CLI 命令和 Code Mode 工具由服务端 OpenAPI 动态生成，不添加业务专用客户端命令。
- 调度循环随 `groot-sprout-http.service` 启动，无需单独的 `schedule serve`。
- 已迁移机器上的 `groot-schedule.service` 应为 stopped/disabled，不要重新启动旧调度器。
- 除非用户指定，不创建 cron 或 systemd timer。
- 配置修改通过 API 或 CLI 完成，不直接编辑运行中的配置和状态文件。

## 先检查

```bash
sprout health
sprout schedule list
sprout schedule config read
systemctl --user is-active groot-sprout-http.service
```

服务地址默认 `http://127.0.0.1:8080`；可通过 `SPROUT_URL` 或 `--url` 选择服务器。任务在服务器所在机器执行。

默认数据根目录为 `${XDG_CONFIG_HOME:-$HOME/.config}/sprout`，服务端可用 `SPROUT_HOME` 覆盖：

- 静态配置：`schedule.json`
- 运行状态：`schedule/state/<编码后的任务名>.json`
- 历史日志：`schedule/logs/<编码后的任务名>.log`

## 任务操作

```bash
sprout schedule new <name> --body '{"every":"10m","command":["/absolute/path/to/command","arg1"]}'
sprout schedule new <name> --body '{"every":"10m","resource":{"cpu":100,"memory":200},"command":["/absolute/path/to/command"]}'
sprout schedule list
sprout schedule log <name> --lines 200
sprout schedule reset <name>
sprout schedule delete <name>
```

- 周期仅支持非负整数加 `ms`、`s`、`m`、`h`、`d`；不支持 `1.5h` 或 `1h30m`。
- `new` 同名任务会覆盖配置并清空运行记录；`reset` 使启用任务立即到期。
- 同一任务不重叠执行。删除、停用或重建任务时，已启动的进程可以完成。
- `list` 输出 JSON，包含 `lastRunAt`、`lastExitCode`、`nextRunAt` 和 `status`。
- `log --lines` 为 1–10000，默认 100。删除任务保留历史日志。
- `command` 数组直接作为进程参数，不展开 shell 变量、管道或通配符。需要 shell 时使用 `["/usr/bin/bash", "-lc", "<command>"]`。
- 优先使用绝对路径。命令参数会显示在配置和日志中。
- 没有 `run` 子命令，一次性执行直接运行底层命令。

## 配置操作

```bash
sprout schedule config read
sprout schedule config write --body @config.json
sprout schedule config write --body - < config.json
sprout schedule config read | jq '.jobs["<name>"].enabled = false' | sprout schedule config write --body -
```

`config write` 接收完整配置，必须包含 `resources`、`tickMs`、`jobs`。服务端先校验，再串行化更新并原子替换；配置每个 tick 热加载。需要手工编辑时，先将 `config read` 输出保存到本地文件，编辑后通过 `config write --body @文件` 提交。

所有请求体统一使用 `--body <json|@file|->`；完整分区输入使用 `--input <json|@file|->`，例如 `{"path":{"name":"sample"},"body":{...}}`。`-` 显式表示从 stdin 读取。

```json
{
  "resources": { "cpu": 2000, "memory": 2000 },
  "tickMs": 1000,
  "jobs": {
    "sample": {
      "every": "10m",
      "resource": { "cpu": 100 },
      "command": ["/absolute/path/to/command", "arg1"],
      "enabled": true,
      "cwd": "/absolute/working/directory"
    }
  }
}
```

资源值为非负数；任务请求的资源名必须存在于顶层且不超过容量。资源是逻辑并发令牌，不是 Linux CPU/内存限制。`tickMs` 为正数，最大 2147483647。`cwd` 可省略或设为 null，此时继承服务工作目录。

运行状态与静态配置分离；不要在任务配置中写入 `lastRunAt` 或 `lastExitCode`。需要清空时使用 `reset`。

## Code Mode

先用 `search` 获取当前工具签名，然后使用返回的准确路径。典型调用：

```ts
await tools.sprout_schedule.list()
await tools.sprout_schedule.log({ path: { name: "sample" }, query: { lines: 100 } })
await tools.sprout_schedule_config.read()
```

创建任务使用 `sprout_schedule.new`，输入为 `{path: {name}, body: {every, command, resource, enabled, cwd}}`；完整配置写入使用 `sprout_schedule_config.write({body: config})`。

## 服务与迁移

```bash
systemctl --user status groot-sprout-http.service --no-pager --full
journalctl --user -u groot-sprout-http.service -n 100 --no-pager
```

部署在 Sprout 项目根目录执行 `groot jobs server-deploy`、`cli-deploy`、`plugin-deploy`。

首次从 Groot 迁移时，先确认用户要切换现有任务，再执行 `groot jobs schedule-migrate`。该 job 要求 Sprout 调度器为空，停止旧调度服务后复制配置、每个任务的状态和历史日志，并保留旧工作目录。验证成功才禁用旧服务；失败自动恢复旧执行器。

任务失败通过 `sprout schedule log` 定位；非零退出码会记录但不会自动停用后续执行。服务重启会终止活动子进程，状态和日志保留。
