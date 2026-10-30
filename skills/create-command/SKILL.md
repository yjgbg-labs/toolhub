---
name: create-command
description: 新增 groot 子命令或调整子命令骨架时使用
---

# 创建 groot 子命令

当用户要求新增 groot 子命令时使用本 skill。只做命令骨架与接线，不实现具体业务逻辑。

## 核心约定

- 命令名 = 目录名：`lib/<name>/index.ts`，`<name>` 用小写字母、数字、连字符。
- 框架（bin/main.ts）会自动发现并 import 所有模块，**不需要**注册、改框架代码或新建 metadata 文件。
- 每次 `groot` 调用都会 import 全部模块：模块顶层**禁止副作用**（不许连网络、起进程、读硬件），逻辑全部放进 run 函数。
- 模块运行在框架进程内：**禁止 `process.exit()`**，用 `return <退出码>` 结束。
- meta 只放 `description`（一行英文，出现在 `groot --help` 里）；命令名由目录决定，meta 里没有 name 字段。

## 操作步骤

1. 按仓库 AGENTS.md 的 worktree 流程开分支（`feat/<name>`），不直接在原目录改。
2. 创建 `lib/<name>/index.ts`，套用骨架（见下）。
3. 有解析类纯函数时，在 `tests/<name>/` 下加测试（bun:test），参考 `tests/display/index.test.ts`。
4. 验证：
   ```bash
   bun test
   ./bin/main.ts --help          # 应列出新命令及 description
   ./bin/main.ts <name> --help   # 应打印命令自己的用法
   ```
5. 提交后合入 master。`~/.local/bin/groot` 软链指向 master 的 bin/main.ts，合并即生效。

## 骨架

```ts
import { defineCmd, getFlag, type GrootContext } from "../_share";

export default defineCmd(
  { description: "One-line summary shown in groot --help" },
  async function run(ctx: GrootContext): Promise<number> {
    const [subcommand, ...rest] = ctx.argv.raw;

    if (!subcommand || subcommand === "--help" || subcommand === "-h") {
      console.log("Usage: groot <name> <subcommand> [args...] [flags...]");
      return 0;
    }

    // if (subcommand === "xxx") { ...; return 0; }

    console.error(`groot <name>: unknown subcommand "${subcommand}"`);
    return 1;
  },
);
```

## 可用设施

- `ctx.argv`：`args`（位置参数）、`flags`（`--key value`/`--no-key`/重复 flag）、`afterDoubleDash`、`raw`。
- `ctx.exec(cmd, args)`：执行外部命令返回 stdout，非零退出会抛错；交互式命令用 bun 的 `spawn` 并 `stdio: "inherit"`。
- `_share` 辅助：`getFlag`、`hasFlag`、`getPositiveIntFlag`、`formatTable`，统一从这里 import，不在模块内重复定义。
- 正常输出用 `console.log`，错误用 `console.error` 并返回非零。

## 结构扩展

单文件膨胀时在 `lib/<name>/` 内按职责拆子目录（参考 `lib/dlna/` 的 `protocol/`、`playback/`、`display/`、`infra/` 分层），index.ts 只做分发。测试统一放仓库根 `tests/<name>/`，镜像源码目录结构，import 用相对路径指回 `../../lib/...`。

## 失败处理

- `--help` 列表里没有新命令：检查 index.ts 是否有 default export、文件是否直接放在 `lib/<name>/` 下。
- 报 `failed to load`：模块顶层有执行代码或 import 失败，把逻辑移进 run 函数。
