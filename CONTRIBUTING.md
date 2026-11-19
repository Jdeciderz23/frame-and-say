# 照有商量协作指南

五位成员从 GitHub Issue 领取工作，用短期分支提交 PR 到 `main`。Issue 的 Assignee 负责跟进验收，其他成员可以通过关联 PR 交付其中一部分。模块负责人见[五人分工](docs/04-team-responsibilities.md)，任务进度见 [GitHub Issues](https://github.com/Jdeciderz23/frame-and-say/issues)。

## 开分支和推送

分支名用 `类型/Issue号-英文短描述`。填写 GitHub 页面上的数字编号，短描述以小写英文字母开头，其余可用小写字母、数字和单个连字符。一个 Issue 可以按交付拆成多个 PR，每条分支围绕一次可独立评审的改动。

| 工作 | 分支示例 | PR 标题前缀 |
|---|---|---|
| 新功能 | `feature/4-recommendation-dto` | `feat:` |
| 修复 | `fix/9-event-validation` | `fix:` |
| 文档 | `docs/6-research-report` | `docs:` |
| 测试 | `test/5-image-validation` | `test:` |
| 重构 | `refactor/11-consent-policy` | `refactor:` |
| 工具、构建、协作配置 | `chore/8-team-workflow` | `chore:` |

以任务 #4 为例，在干净的工作区从最新主线开始：

```sh
git fetch origin
git switch --no-track -c feature/4-recommendation-dto origin/main
# 完成并检查本次改动，提交选定文件后推送
git push -u origin HEAD
```

后续提交推到同一分支，已打开的 PR 会自动更新。功能改动先运行 `./scripts/check.sh`，纯文档改动检查链接、引用和 `git diff --check`。提交信息写清类型和行为，如 `feat(recommendation): 校验候选照片版本`。发布后的任务分支禁止强推。主线更新或出现冲突时，在自己的分支运行 `git fetch origin` 和 `git merge origin/main`，解决冲突后重新验证、正常推送。

## 提交 PR

1. 目标选 `main`。标题使用表中对应的前缀，可加模块名，如 `docs(research): 梳理分享用途和用户故事`。
2. 正文单独写一行 `Refs #6` 或 `Closes #6`，编号与分支中的主要 Issue 相同。阶段交付用 `Refs`，已满足该 Issue 全部验收条件才用 `Closes`。其他相关任务可各写一行 `Refs #编号`。
3. 按模板说明改动、执行过的命令及结果，以及 AI 参与和人工核查情况。接口或数据库变更同步更新契约、迁移和测试。真实照片、访谈个人信息、令牌和本机 `.env` 留在本机。
4. 组员的 PR 准备好后，确认 **Reviewers** 有代码所有者 `@Jdeciderz23`。涉及跨模块接口时，可以请接口使用方一起核对契约。

## 评审与合并

- 公开仓库的 `main` 必须通过 PR 合入，禁止强推和删除，保护规则也适用于管理员。合并时分支须包含最新的 `main`，并通过 GitHub Actions 的 `frontend`、`backend`、`mysql-compose` 三项必需检查。
- [生效的 CODEOWNERS](https://github.com/Jdeciderz23/frame-and-say/blob/main/.github/CODEOWNERS) 由 `@Jdeciderz23` 覆盖整个仓库。组员的 PR 由其核对差异和验收证据后点 **Approve**；需修改时用 **Request changes**。影响评审的新提交会使旧批准失效。
- `@Jdeciderz23` 自己发起的 PR 由本人记录自查与验证结果，待三项 CI 通过后使用仅限 PR 的管理员例外。课程要求的评审记录按教师要求留存。
- 合并前逐项处理评审意见。GitHub 当前未强制解决每条对话，组内由合并人核对处理结果。条件满足后，有写权限的成员可以用 **Squash and merge**。确认改动已进入 `main` 且分支没有后续工作后，手动删除任务分支。

`Collaboration` 工作流运行时会检查分支名、PR 标题和 Issue 引用。`pr-policy` 当前只提供校验结果。GitHub 的 [Ruleset](https://github.com/Jdeciderz23/frame-and-say/rules/27667734) 负责 Code Owner 审核和管理员 PR 例外。[经典保护配置样例](.github/branch-protection.json)对应 **Settings → Branches** 中所有人都要满足的 PR 与 CI 要求。
