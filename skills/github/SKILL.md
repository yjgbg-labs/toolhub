---
name: github
description: 查询或操作GitHub仓库、PR、Issue、CI与版本时使用
---

# GitHub

当用户要求查看、搜索、监控或操作 GitHub 仓库、Pull Request、Issue、Actions 工作流、Release、通知及底层 REST/GraphQL 接口时，使用本 skill。

## 核心约定

- 统一通过 Groot 服务端的 `github` 命名空间及其子命名空间（`github.repo`、`github.pr`、`github.issue`、`github.run`、`github.release`）执行，调用前先按需检索最新工具签名。
- 本地 Git 分支检出、提交与变基仍遵守工作区版本控制规范；GitHub 远端元数据查询、PR 审查合并、CI 日志诊断与外部版本信号轮询优先走 `github` 命名空间。
- 仓库级操作均支持可选参数 `repo`（`owner/repo` 格式或 GitHub 远端地址）。省略 `repo` 时，服务端根据调用方隐式传递的 `cwd` 向上查找 `.git`（兼容 Git Worktree 的 `commondir`）并从 `origin`（次选 `upstream`）推导目标仓库；在无工作目录上下文的后台任务或跨仓库查询中必须显式传入 `repo`。
- 回复与日志摘要中严禁输出任何明文 Token、Secret 或凭证文件内容。

## 认证与会话边界

- 凭证独立持久化在 `$GROOT_HOME/github/auth.json`（默认 `~/.config/groot/github/auth.json`，权限 `0600`），单账号槽位，不隐式回退读取外部工具配置。
- 登录与退出接口仅暴露给终端客户端，不对 Code Mode 与 GraalJS 暴露：
  - 未传 `token` 时默认走浏览器 OAuth Device Flow（默认 `scopes` 为 `repo,workflow,gist,notifications,read:org`，可通过环境变量 `GITHUB_CLIENT_ID` 或参数 `clientId` 覆盖客户端标识），通过 SSE 实时输出一次性授权码与验证链接并轮询完成落盘。
  - 传入 `token` 时直接校验 `/user` 并原子落盘。
  - 退出操作会同时清除内存态与本地 `auth.json` 文件。
- 遇到 `401`（`github_unauthenticated`）时，停止盲目重试并提示用户在终端完成登录。

## 关键参数、默认值与限制

- **单次请求超时**：服务端访问 `https://api.github.com` 与 `https://github.com` 单次超时固定为 `30s`（超时返回 `504`）。
- **列表与搜索分页**：
  - `limit`：在 `pr.list`、`issue.list`、`notifications` 中默认 `30`，在 `search`、`run.list`、`release.list` 中默认 `20`，有效范围均为 `1`–`100`。
  - `search` 的 `kind` 取值为 `repos`、`code`、`issues`、`prs`、`commits`；选择 `prs` 或 `issues` 时会自动补齐 `is:pr` 或 `is:issue` 限定词。
- **PR 检查与变更读取**：
  - `pr.get` 单次聚合返回可合并状态、`reviewDecision`、未解决行级评论线程数 `unresolvedThreads` 以及最新提交的 `checks` 汇总（`total`、`passed`、`failed`、`pending`），适合在条件触发器中单次判定状态。
  - `pr.diff` 的 `filesOnly` 默认 `false`；`maxBytes` 默认 `262144`（256 KiB），有效范围 `1024`–`1048576`（1 MiB），超出时截断并将 `truncated` 置为 `true`。
  - `pr.merge` 的 `method` 默认 `squash`（可选 `squash`、`merge`、`rebase`），`deleteBranch` 默认 `false`。
- **Actions 日志与重跑**：
  - `run.logs` 支持传入 `runId` 或具体 `jobId`；按 `runId` 查询时 `failedOnly` 默认 `true`（最多提取前 `5` 个失败 Job），每个 Job 截取尾部 `tailLines` 行（默认 `120`，范围 `1`–`2000`）。跨域跳转至日志存储时会自动剥离 `Authorization` 请求头。
  - `run.rerun` 的 `failedOnly` 默认 `true`。
- **Release 与远端文件**：
  - `release.get` 省略 `tag` 或传入 `latest` 时读取最新正式发布版本。
  - `repo.read` 读取目录时返回条目清单，读取文件时若为合法 UTF-8 文本则填充 `content`，二进制文件填充 `base64`。
- **底层逃逸舱**：
  - 长尾 REST 接口使用 `github.api`（`path` 支持 `{owner}` 与 `{repo}` 占位符自动替换；`GET` 数组接口可设 `paginate` 为 `true`，`maxPages` 默认 `10`、上限 `50`，JSON 响应上限 `4 MiB`）。
  - 复杂关联查询或长尾变更使用 `github.graphql`。

## 错误码与排障建议

- `400`（`invalid_github`）：参数格式非法或当前目录无法推导仓库，补充显式 `repo` 或修正参数。
- `401`（`github_unauthenticated`）/ `403`（`github_forbidden`）：未登录或 Token 缺少对应 `scope`（如修改 Workflow 需 `workflow` 权限）。
- `404`（`github_not_found`）：目标仓库、编号或 Tag 不存在，或私有仓库无访问权限。
- `409`（`github_conflict`）/ `422`（`github_validation`）：分支冲突、检查未通过导致不可合并或字段校验失败，先通过 `pr.get` 或 `run.logs` 核查原因后再处理。
- `429`（`github_rate_limited`）：触发 GitHub API 速率限制，结合 `whoami` 返回的 `rateLimit.resetAt` 推迟或拉长轮询间隔。
