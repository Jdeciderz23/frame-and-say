# 照有商量协作指南

五位成员从 GitHub Issue 领取工作，用短期分支提交 PR 到 `main`。Issue 的 Assignee 负责跟进验收，其他成员可以通过关联 PR 交付其中一部分。模块负责人见[五人分工](docs/04-team-responsibilities.md)，任务进度见 [GitHub Issues](https://github.com/Jdeciderz23/frame-and-say/issues)。

## 开分支和推送

分支名统一用 `类型/Issue号-英文短描述`。填写 GitHub 页面上的数字编号，短描述以小写英文字母开头，其余可用小写字母、数字和单个连字符。每个分支围绕一个主要 Issue，用户名和项目周次留在任务记录里。

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

后续提交推到同一分支，已打开的 PR 会自动更新。功能改动先运行 `./scripts/check.sh`，纯文档改动检查链接、引用和 `git diff --check`。提交信息写清类型和行为，如 `feat(recommendation): 校验候选照片版本`。每人只向自己负责的任务分支推送。禁止直推、强推或删除 `main`，已发布的任务分支也禁止强推。主线更新或出现冲突时，在自己的分支运行 `git fetch origin` 和 `git merge origin/main`，解决冲突后重新验证、正常推送。

## 提交 PR

1. 目标选 `main`。标题使用表中对应的前缀，可加模块名，如 `docs(research): 梳理分享用途和用户故事`。
2. 正文单独写一行 `Refs #6` 或 `Closes #6`，编号与分支中的主要 Issue 相同。阶段交付用 `Refs`，已满足该 Issue 全部验收条件才用 `Closes`。其他相关任务可各写一行 `Refs #编号`。
3. 按模板说明改动、执行过的命令及结果，以及 AI 参与和人工核查情况。接口或数据库变更同步更新契约、迁移和测试。真实照片、访谈个人信息、令牌和本机 `.env` 留在本机。
4. 准备好后，在 PR 的 **Reviewers** 选择一位能验证本次改动的其他成员。涉及跨模块接口时，请使用方核对契约。任务卡不预先指定评审人。

## 评审与合并

- 评审人查看实际差异和验收证据。需修正的内容用 **Request changes** 标到具体行，确认可以合并时提交 **Approve**。普通评论不计批准。
- 合并人核对最新提交已有至少 **一位非作者批准**，修改请求已处理、讨论已解决、分支已同步最新 `main`，且 `pr-policy`、`frontend`、`backend`、`mysql-compose` 四项检查均通过。批准后追加提交须重新评审。
- 条件满足后由 PR 作者使用 **Squash and merge**，作者不在时可由仓库负责人代合。每个 PR 在主线保留一个提交。合并后作者核实改动已进入 `main`、该分支没有后续工作，再清理自己的任务分支。

仓库已将 PR 合并方式限定为 Squash。Actions 会检查推送、分支名与 PR 关联，直接推送主线会在推送后报错。当前私有仓库的 GitHub 套餐未开放分支保护，无法在服务端拦截直推或未获批准的合并。合并人须在点击按钮前核对上面四项检查和批准记录。[GitHub 分支保护说明](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)

## 已经推送的工作

- 已打开的 [#16](https://github.com/Jdeciderz23/frame-and-say/pull/16) 继续在原分支修订，保留讨论和评审历史。分支名仅对此 PR 过渡，正文主要关联 `Refs #6`，另可关联 #7。
- 已推送但尚未建 PR 的推荐算法可原样保留提交。从原分支最新提交创建规范名，再用新分支开 PR。作者若还有本地改动，先在原分支保存并提交。

```sh
git switch feature/w1-04-recommendation-dto
git fetch origin
git merge --ff-only origin/feature/w1-04-recommendation-dto
git switch -c feature/4-recommendation-dto
git push -u origin HEAD
```
