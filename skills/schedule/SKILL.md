---
name: schedule
description: 创建、修改或排查Groot定时任务及迁移旧版调度时使用
---

# Groot 定时任务

创建、修改、重置、删除或排查 Groot 周期性后台任务时使用本 skill。周期任务由 `groot-http.service` 内置的事件驱动调度器调用 `graaljs` 的 `submit` 提交至隔离的 GraalJS 环境执行，每次尝试与重试均分配独立的 GraalJS 任务 ID（一次性内联源码任务直接使用 `graaljs` 的 `submit`，`schedule` 仅负责持久化文件任务）。

## 核心约定

- **配置入口**：统一通过 Groot 服务端的 `schedule`、`schedule.config` 与 `graaljs` 命名空间（调用前按需检索签名）管理配置与查看运行状态，严禁直接手工编辑运行中的状态文件或 `schedule.json`，也不要操作已废弃的 `groot-schedule.service`。除非用户明确指定 systemd，否则不去翻查系统级定时器。
- **脚本文件约束**：任务定义中的 `script` 必须为**服务端本地绝对 `.js` 文件路径**（不支持内联源码、相对路径、`~` 展开或 URI），文件须为非空 UTF-8 文本且上限 `1 MiB`，支持顶层异步等待与服务端注入的 `tools.*`（禁止导入 Node/Bun 模块）。更新脚本文件建议采用原子替换，修改内容无需重置任务配置，下一次尝试自动生效。
- **完整配置原子写入**：读写全局配置时必须同时包含顶层 `resources`（逻辑并发令牌总容量）与 `jobs`（任务映射），切勿将运行时列表返回的状态字段写回任务静态定义。

## 关键路径、参数默认值与取值范围

- **服务与存储路径**：默认服务地址 `http://127.0.0.1:18080`（可通过 `GROOT_BASE_URL` 覆盖）；默认数据根目录 `~/.config/groot`（可通过 `GROOT_HOME` 覆盖），包含 `schedule.json`、`schedule/state/`、`schedule/logs/`、`graaljs/tasks.sqlite3` 与 `graaljs/logs/`。
- **调度间隔 `every`**：非负整数加单一时间单位 `ms`、`s`、`m`、`h` 或 `d`（不支持小数或 `1h30m` 复合写法；`0ms` 表示前次结束后立即再次调度）。
- **执行限额**：guest CPU 限额 `cpu` 默认 `1s`，墙钟超时 `timeout` 默认 `30s`（不含排队等待时间），均须为正整数加时长单位；单个 GraalJS 上下文堆上限 `4 MiB`（全局 isolate `64 MiB`）。
- **重试策略 `retry`**：
  - `maxRetries`：额外重试次数，默认 `3`，有效范围 `0`–`100`（`0` 表示禁用重试，默认最多共尝试 `4` 次）。
  - `delay`：首次重试基础等待时长，默认 `1s`（支持 `0ms`）。
  - `backoff`：指数退避倍率，默认 `2`（至少为 `1` 且有限）。
  - `maxDelay`：单次重试最大等待上限，默认 `30s`（不小于 `delay`）；第 n 次失败等待 `min(delay × backoff^(n-1), maxDelay)`，默认依次等待 `1s`、`2s`、`4s`。
- **源码缓存与日志参数**：调度器按文件内容 `SHA-256` 维护容量 `128` 项的 `Source` LRU 缓存；任务缺失或文件非法以 `source_error` 记为提交前失败（`lastTaskId` 为空）；`schedule.log` 的 `lines` 默认 `100`、有效范围 `1`–`10000`；删除任务保留历史日志；`lastExitCode` 为兼容标记（成功 `0`、取消/中断 `143`、其他失败 `1`）。
- **宿主命令调用限额**：脚本内调用命名 Shell 环境（默认含 `local`、`windows`、`mac`、`r6s`）时，脚本与单路输出上限各 `64 KiB`，默认超时 `30s`。

## 状态流转与排障要点

- **状态含义**：同名任务严格串行不重叠；新建覆盖、重置、删除或修改定义均会清空待重试记录；`status` 为 `retrying` 表示等待退避定时器（期间释放逻辑资源），`queued` 表示等待逻辑资源令牌，内部执行阶段见 `lastTaskState`；重试耗尽后不会自动停用任务，下一周期按最近一次尝试开始时间加 `every` 恢复，`attempt` 重置为 `1`。
- **排障与部署**：结合任务的 `schedule.log` 及对应 `lastTaskId` 的 `graaljs` 状态与日志定位脚本错误；若调度服务本身异常，检查用户级 `groot-http.service` 状态与近期日志（如最近 `100` 行）；服务端升级通过项目 `server-deploy` 任务交付。
