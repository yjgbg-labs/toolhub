---
name: create-skill
description: 创建或修改OpenCode skill时使用
---

# 创建 Skill

当用户要求创建、修改或检查 OpenCode skill 时，使用本 skill。

## 文件位置

Skill 必须放在独立目录中：

```text
~/workspace/groot-cli/skills/<skill-name>/SKILL.md
```

全局 `~/.config/opencode/skills` 已链接到该目录，不要重复创建副本。

## Frontmatter

`SKILL.md` 必须以 YAML frontmatter 开头：

```yaml
---
name: example-skill
description: 处理示例任务时使用
---
```

约束如下：

- `name` 只能包含英文字母和连字符。
- `name` 必须与目录名一致。
- `description` 必须说明何时使用该 skill，而不只是描述其功能。
- `description` 必须使用中文，长度不超过 50 个字。

## 正文规则

- 正文必须使用中文；命令、代码、路径和专有名词可以保留原文。
- 文件总长度不得超过 150 行，包含 frontmatter、空行和代码块。
- 优先写可执行的步骤、约束和失败处理，避免背景介绍和重复帮助文本。
- 示例只在能消除歧义时保留。
- description 负责触发场景，正文首段可补充边界，但不要重复堆砌。

## 工作流程

1. 明确 skill 处理的任务和不处理的边界。
2. 选择简洁的英文连字符名称。
3. 在 `skills/<name>/SKILL.md` 创建文件。
4. 写明触发场景、核心规则、操作步骤和失败处理。
5. 检查正文是否为中文。
6. 检查 description 是否包含“何时使用”且不超过 50 字。
7. 检查总行数不超过 150 行。
8. 确认全局 skill 链接可以读取该文件。

## 完成检查

```bash
wc -l skills/<name>/SKILL.md
```

还要人工检查：

- frontmatter 位于文件最前面且字段完整。
- description 能根据用户请求准确触发。
- 命令与当前实现一致，不虚构子命令或参数。
- 没有把敏感信息写入示例。

修改 skill 后提醒用户重启 OpenCode；运行中的会话不会重新加载 skill。
