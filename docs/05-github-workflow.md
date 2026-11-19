# GitHub 协作规范

开发仓库为 [Jdeciderz23/frame-and-say](https://github.com/Jdeciderz23/frame-and-say)，可见性保持私有。每位成员用自己的 GitHub 账号克隆、提交和评审。正式认领前，成员 1 把内部编号 `member-1` 至 `member-5` 对应到实际协作者账号。

## 成员与合并保护

1. 管理员在 [仓库访问设置](https://github.com/Jdeciderz23/frame-and-say/settings/access) 的 **Collaborators → Add people** 邀请经团队确认的账号。成员接受后，管理员把 Issue 的 Assignee 分配给相应成员。
2. 对照 [Actions](https://github.com/Jdeciderz23/frame-and-say/actions) 的首次运行，记录前端、后端和 MySQL Compose 检查的实际名称。管理员在 **Settings → Rules** 或 **Branches** 要求以 PR 合并、至少一位非作者批准、必需检查通过、解决评审对话，并禁止强推或删除 `main`。
3. 走一条实际的成员 PR 验证设置生效。若 GitHub 套餐限制了私有仓库的保护功能，成员依照评审流程提交，管理员记录尚未启用的限制并与教师确认可用方案。

## 从 Issue 到 PR

1. 在 Issue 确认负责人、评审人、验收标准和依赖。把 W1/W2 内部任务 ID 映射到实际的 `#编号`，并把依赖链接补齐。
2. 从最新 `main` 开一个短期分支。示例 `feature/w2-01-event-create`。修复用 `fix/`，文档用 `docs/`。
3. 小步提交，例如 `feat(event): 校验活动创建名称`。改动触及 API、迁移、权限或共享配置时，邀请实际消费者或模块负责人评审。
4. 打开网页 PR 模板，保留关联任务、改动范围、运行命令和结果、AI 使用等字段。完成 Issue 时在正文写 `Closes #实际编号`。使用 CLI 时打开网页编辑，或显式传入包含这些内容的正文文件。
5. 一位非作者批准且必需检查全部通过后再合并。默认使用 Squash，合并后同步 `main`。

```sh
git switch main
git pull --ff-only origin main
git switch -c feature/w2-01-event-create

# 完成一个任务后，先执行 ./scripts/check.sh，再提交本次改动的文件。
git push -u origin HEAD
gh pr create --base main --web
```

可以在 GitHub 网页打开 **Compare & pull request**。若在 CLI 使用 `--body-file`，请填写同样的模板内容，避免使用从提交消息推导正文的 `--fill`。

## 任务导入与依赖

`docs/issues.json` 保存 13 个启动任务。仓库管理员可运行以下命令预览和导入。重复执行时按任务 ID 跳过已有 Issue。没有实际 GitHub 账号的任务先保持未分配，由管理员按团队名单认领。

```sh
node scripts/publish-issues.mjs
node scripts/publish-issues.mjs --repo Jdeciderz23/frame-and-say --apply
```

把每条任务的评审人加入对应 PR。五人的默认评审环见 [开发分工](04-team-responsibilities.md)，授权策略变更还需模块负责人检查。首个基线提交后，日常改动都走功能分支。

## 合并冲突

共享分支优先合并最新 `main`，保留其他成员的提交。只在自己独占的分支 rebase。需要改写已推送的个人分支时，先通知评审人并使用 `--force-with-lease`。

```sh
git fetch origin
git switch feature/w2-01-event-create
git merge origin/main
# 解决冲突，再运行检查和提交解决结果。
```

需要取消当前合并可运行 `git merge --abort`。锁文件冲突先解决包声明，再使用统一的包管理器版本生成锁文件。

## 课程留痕

- 每人独立完成所认领任务的实现、测试和评审，按课程要求保留有实际内容的合并 PR 与主讲记录。
- 每份 API 或数据库变动在同一个 PR 更新契约和测试。合入后的 Flyway 迁移不就地重写，数据库变动用下一版迁移。
- 随 PR 记录 AI 工具、用途、人工确认和验证结果。真实照片、访谈个人信息、开发令牌、`.env` 和上传目录均留在本机。
