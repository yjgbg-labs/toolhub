---
name: schedule
description: 创建、修改或排查 Groot 定时任务及迁移旧版调度时使用
---

# Groot 定时任务

周期任务由 Groot 调度到 GraalJS，每次成功提交的执行和重试都有独立 task ID。

## 核心约定

- 使用 `groot-cli schedule` 管理任务；一次性构建使用 `groot-cli jobs --job <job>`。
- CLI、Code Mode 和 GraalJS SDK 都由普通命令声明生成，不添加业务专用适配器。
- `script` 为服务端 JavaScript 文件的绝对路径；不接受内联 `source`、`command`、`cwd` 或运行状态字段。
- 脚本使用 `console`、`tools.*`（HTTP 用 `tools.http`，Shell 用 `tools.shell.execute({env, script})`）；JS 环境不能导入 Bun/Node 模块。
- 调度循环随 `groot-http.service` 启动；不要重启旧 `groot-schedule.service`。
- 调度由配置变更、任务完成和最近截止时间唤醒；空闲/等待资源时休眠，不轮询文件。
- 除非用户指定，不创建 cron 或 systemd timer。
- 配置修改通过 API/CLI 完成，不直接编辑运行中的配置及状态文件。

## 先检查

```bash
groot-cli health
groot-cli schedule list
groot-cli schedule config read
systemctl --user is-active groot-http.service
```

默认地址 `http://127.0.0.1:18080`，客户端可通过 `GROOT_BASE_URL` 覆盖。
默认数据根目录为 `${XDG_CONFIG_HOME:-$HOME/.config}/groot`，服务端可用 `GROOT_HOME` 覆盖：

- 配置：`schedule.json`
- 状态：`schedule/state/<编码后的任务名>.json`
- 历史日志：`schedule/logs/<编码后的任务名>.log`
- GraalJS 任务及源码：`graaljs/tasks.sqlite3`；任务日志：`graaljs/logs/<id>.log`

## 任务操作

```bash
groot-cli schedule new --name sample --body '{"every":"10m","script":"/home/user/.config/groot/scripts/sample.js"}'
groot-cli schedule list
groot-cli schedule log --name sample --lines 200
groot-cli schedule reset --name sample
groot-cli schedule delete --name sample
groot-cli graaljs status --id gjs_...
groot-cli graaljs logs --id gjs_...
```

- `every` 为非负整数加 `ms`、`s`、`m`、`h`、`d`，不支持 `1.5h` 或 `1h30m`。
- `cpu` 默认 `1s`、`timeout` 默认 `30s`，均须为正整数时长；超时不计排队时间。
- `new` 同名覆盖，`reset` 清空记录并立即到期。修改任务定义、删除、停用均清空待重试。
- 已提交的旧任务可以完成，但结果不会覆盖新状态或触发旧重试；同名任务始终不重叠。
- `list` 查看 `lastTaskId`、`lastTaskState`、`lastFinishedAt`、`lastError`、`attempt`、`nextRetryAt`、`nextRunAt`。
- `status=retrying` 为等待重试；`queued` 为等待逻辑资源；GraalJS 内部状态见 `lastTaskState`。
- `lastExitCode` 为结果标记：成功 0、取消/中断 143、其他失败 1，不是进程退出码。
- `log --lines` 范围 1–10000，默认 100，包含每次尝试的 ID、输出、结果和下次重试时间。
- 删除任务保留历史日志。一次性执行使用 `graaljs start`，没有 `schedule run`。

## 脚本文件与提交时校验

- 路径在服务端解析，必须为绝对本地路径；不支持 URL、`file:` URI、`~` 展开或相对路径。
- 文件为非空 UTF-8，最多 1 MiB。使用普通 `.js` 文件，内容可直接使用顶层 `await`。
- 每次执行/重试提交前读取脚本内容并计算 SHA-256；不依赖文件大小、mtime 或文件通知。
- schedule 维护最多 128 项的 `SHA-256 → 原生 Source` LRU；相同内容跨路径共享，名称及 URI 使用哈希。
- 命中复用原对象，未命中用同一次读取的字节构建；已提交任务自行持有 Source，LRU 淘汰不影响执行。
- 已提交 GraalJS 的任务（包括其内部队列）保留原快照；等待 schedule 逻辑资源、后续周期及重试在提交时重新读取。
- 不使用 watcher 或轮询定时器；旧内容按 LRU 淘汰，关闭 scheduler 时释放缓存。
- 编辑脚本不重置周期/重试状态，不中断活动 Context。建议原子替换，避免读到尚未写完的源码。
- 文件不可读、缺失、为空、编码或大小错误使后续尝试以 `source_error` 失败，不继续执行旧缓存。
- 文件错误由 schedule 记录并重试，此时尚未提交 GraalJS，`lastTaskId` 为空。
- 语法错误在执行时报告；修复文件后自动被后续尝试使用。一次性 `graaljs start` 仍接受内联 `source`。

## 重试

默认策略：`{"maxRetries":3,"delay":"1s","backoff":2,"maxDelay":"30s"}`。

- `maxRetries` 是额外重试次数，范围 0–100；0 禁用，默认最多执行 4 次。
- 默认失败后等待 1、2、4 秒；第 n 次失败延迟为 `min(delay × backoff^(n-1), maxDelay)`。
- `delay` 支持 0；`backoff` 至少为 1 且有限；`maxDelay` 至少为 `delay`。
- 错误、超时、资源超限、取消、中断及提交失败都重试；捕获错误后正常返回会被视为成功。
- 重试优先于普通周期，等待重试时释放逻辑资源；到期定时器唤醒，资源释放后立即调度队列。
- 成功或耗尽重试后恢复普通周期，下一周期为最近一次尝试开始时间加 `every`，`attempt` 重新从 1 开始。
- schedule 调用 `start`，从返回的 `GraalTask.id` 获取 ID 后保存；重启核对已保存 ID 的真实结果。
- 接受提交成功但 ID 尚未保存时崩溃造成的重复提交窗口。已产生的外部副作用不会回滚。

## 完整配置

```json
{
  "resources": {"cpu": 100},
  "jobs": {
    "sample": {
      "every": "10m",
      "script": "/home/user/.config/groot/scripts/sample.js",
      "cpu": "1s",
      "timeout": "30s",
      "retry": {"maxRetries": 3, "delay": "1s", "backoff": 2, "maxDelay": "30s"},
      "resource": {"cpu": 10},
      "enabled": true
    }
  }
}
```

`resource` 是逻辑并发令牌，与 GraalJS CPU/内存限制独立；值为非负数，须存在于顶层并不超过容量。
`config write` 必须包含 `resources`、`jobs`，先验证后原子写入并唤醒调度。
旧配置中的 `tickMs` 读取时忽略，新 API 不再返回它。`every: "0ms"` 表示前次完成后立即再次执行。
未修改的任务保留状态；修改定义的任务清空状态。不要把 `list` 的运行字段写回任务配置。

```bash
groot-cli schedule config read > config.json
groot-cli schedule config write --body @config.json
groot-cli schedule config read | jq '.jobs.sample.enabled = false' | groot-cli schedule config write --body -
```

请求体用 `--body <json|@file|->`，任务名等普通参数用 `--name sample` 分别传入。

## Code Mode / GraalJS

Code Mode 先用 `search` 获取当前准确签名。JavaScript SDK 普通参数扁平化，混合输入保留 `body`，仅请求体直接传对象：

```js
await tools.schedule.new({name: "sample", body: {every: "10m", script: "/home/user/.config/groot/scripts/sample.js"}});
await tools.schedule.log({name: "sample", lines: 100});
const config = (await tools.schedule.config.read()).body;
await tools.schedule.config.write(config);
const result = (await tools.shell.execute({env: "local", script: "df -Pk /", timeout: "3s"})).body;
if (result.exitCode !== 0) throw Error(result.stderr || `bash exit ${result.exitCode}`);
```

`shell.execute` 对三个客户端暴露；默认环境有 `local/windows/mac/r6s`，支持默认 `30s` 超时及本机绝对 `cwd`，返回 `exitCode/stdout/stderr`。
脚本及每路输出上限各 64 KiB，远程命令另受目标命令行长度限制。超时/取消清理本地进程组或 SSH 客户端，远端进程由脚本管理。JS CPU/内存限额不限制子进程。

## 升级与排障

- 升级旧 `command`/内联 `source` 配置前，备份配置、状态、日志及引用脚本；旧配置不能直接由新版启动。
- 将内联源码保存为持久 `.js` 文件；通过旧 API 写入空任务配置，部署新版，再通过新 API 写入 `script` 路径。
- 依赖系统命令、SSH、Windows 命令且无法直接迁移的任务先保留备份，明确告知暂停的任务。
- 部署使用项目的 `groot-cli jobs --job server-deploy`，再验证 health、task ID、真实终态和重试。
- 用 `schedule log` 与 `graaljs logs/status` 定位脚本错误；重试耗尽不会自动停用后续周期。
- 服务关闭等待活动 Context 清理；重启保留配置、状态及日志。

```bash
systemctl --user status groot-http.service --no-pager --full
journalctl --user -u groot-http.service -n 100 --no-pager
```
