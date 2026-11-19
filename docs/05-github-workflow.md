# GitHub协作规范

状态：本地工程采用本规范；远程仓库、成员权限和分支保护是否已建立，应以根README/实际GitHub设置为准。文档中的操作步骤不代表设置已生效。

## 账号与仓库准备

1. 成员1收集五人的GitHub账号，在组内确认谁持有仓库管理权限。当前逻辑编号 `member-1` 至 `member-5` **不是GitHub用户名**，不能拿去实际分配Issue。
2. 默认私有，未经团队决定不改成公开。个人仓库通过 **Settings → Collaborators / Manage access → Add people** 邀请其他四人；组织仓库给开发团队Write权限，管理权限只给确需维护设置者。
3. 每人接受邀请后，用自己的身份克隆、提交和评审，不共享令牌。开发机先设置本人 `user.name` 和已验证/隐私邮箱。
4. 管理员在 **Settings → Rules → Rulesets** 或 **Branches** 对main要求PR、至少1位他人批准、CI检查通过；禁止强推和删除，尽量限制管理员绕过。检查名称以第一次Actions实际运行生成的名称为准。
5. 私有仓库的技术保护能力可能受GitHub套餐限制。若页面不支持，记录阻塞并确认可用方案；不能把“口头要求审核”写成“已启用保护”，也不自动公开仓库。

若远程尚未建立，安装并登录GitHub CLI后，在仓库根执行以下步骤。先核对当前账号和仓库是否已经存在；仓库存在时不要重复创建。

```sh
gh auth login
gh api user --jq .login
gh repo view Jdeciderz23/group-photo-consensus
# 上一条确认不存在且账号/归属正确后才执行；本地需已有初始提交
gh repo create group-photo-consensus --private --source=. --remote=origin --push
```

如果仓库由网页预先创建且保持为空，本地已有提交时可连接：

```sh
git remote -v
# 确认没有origin后执行；已有origin时先核对，不覆盖
git remote add origin https://github.com/Jdeciderz23/group-photo-consensus.git
git push -u origin main
```

`Jdeciderz23` 是当前连接器识别的账号；如果团队决定改用组织，先把命令中的归属改成已确认的组织名。不要把令牌放进URL或提交到仓库。首次初始化允许一次建立main的基础提交，随后全部改动走功能分支与PR。

## 每项任务的流程

1. 创建或认领Issue：写清目标、逻辑负责人映射后的真实账号、验收标准、依赖和评审人。被依赖任务未完成时，先做契约/模拟和测试，不悄悄绕开接口。
2. 从最新main开短期分支，每个分支完成一个可评审任务。例：`feature/w2-01-event-create`、`feature/w2-02-photo-review`、`feature/w2-03-member-policy`、`feature/w2-04-fair-selection`、`feature/w2-05-photo-upload`；修复用`fix/`，文档用`docs/`。
3. 小步提交，格式 `type(scope): 中文或英文的具体改动`，如 `feat(event): 校验活动创建名称`、`test(policy): 拒绝跨活动访问`。常用type为feat/fix/test/docs/chore/refactor，不写无信息的`update`。
4. 提PR到main，用模板说明问题、做法、测试、接口变化和AI参与；关联 `Closes #实际Issue号`。本地W1/W2任务ID不是GitHub Issue号，不能写成关闭不存在的编号。
5. 至少一位非作者评审，CI全绿，涉及需求/权限/架构决策由相应人员明确确认。审核者检查异常路径、权限、测试、契约和敏感信息；有问题提出修改再复核。
6. 保持一个PR聚焦一件事，优先Squash合并以便追踪；合并后同步main。未通过检查不得强行合入；本地测试截图不能替代实际CI结果。

```sh
git switch main
git pull --ff-only origin main
git switch -c feature/w2-01-event-create
# 修改、运行检查
git add <明确的文件路径>
git commit -m "feat(event): 增加活动创建校验"
git push -u origin feature/w2-01-event-create
gh pr create --base main --fill
```

## 冲突处理

共享分支优先merge最新main，避免改写他人历史。只在自己独占、无人基于其开发的分支上使用rebase；需要更新已推送分支时先告知评审人并使用`--force-with-lease`，绝不对main使用force。

```sh
git fetch origin
git switch feature/w2-01-event-create
git merge origin/main
# 编辑冲突文件，理解两边意图；不要直接整文件选ours/theirs
git add <已解决的文件>
git commit
# 再运行测试并push
```

不能确认接口含义时，与模块负责人共同解决；无法完成可 `git merge --abort` 恢复本次合并。锁文件冲突先保留正确的包声明，再用规定包管理器重新生成锁文件，禁止手工拼接包校验值。

## 共享责任与留痕

- 课程要求每人至少10个有实际内容的merged PR和一次主讲，贯穿课程完成；不在前两周凑数，不伪造记录。代码、文档和测试都要关联真正完成的工作。
- 需求或接口变更先建Issue，说明影响，评审后修改契约及调用方；不只在聊天里口头改。
- 新增数据库迁移、共享DTO、根构建配置时，在PR中点名受影响模块负责人；避免五人同时修改公共入口。
- AI使用记录随PR提交：工具、用途、人工确认/修改、实际测试。核心逻辑由负责同学理解并能现场讲解。
- `.env`、真实照片、访谈个人信息、上传目录、构建缓存不入Git。误提交密钥时立即撤销凭据并通知仓库管理员，删除文件本身不能使泄露失效。
