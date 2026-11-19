# 启动任务索引

前两周开发任务已在 GitHub 建立并指派。打开对应 Issue 查看当前负责人、依赖、关联 PR 和验收进度。分支名使用表中的 GitHub 编号。需求故事按[第 2 课交付要求](06-first-two-weeks.md#第-2-课交付要求)逐条建立 Issue。

| 项目周编号 | GitHub Issue |
|---|---|
| W1-01 | [#1 检查仓库基线并完善活动输入测试](https://github.com/Jdeciderz23/frame-and-say/issues/1) |
| W1-02 | [#2 完成前端主页、路由和健康请求验证](https://github.com/Jdeciderz23/frame-and-say/issues/2) |
| W1-03 | [#3 验证后端健康、数据库连接和身份边界样例](https://github.com/Jdeciderz23/frame-and-say/issues/3) |
| W1-04 | [#4 定义纯Java推荐输入输出与确定性样例](https://github.com/Jdeciderz23/frame-and-say/issues/4) |
| W1-05 | [#5 复现图片存储并完善输入校验测试](https://github.com/Jdeciderz23/frame-and-say/issues/5) |
| W1-06 | [#6 完成真实访谈并追溯候选需求](https://github.com/Jdeciderz23/frame-and-say/issues/6) |
| W1-07 | [#7 评审角色、授权边界、API和数据草案](https://github.com/Jdeciderz23/frame-and-say/issues/7) |
| W1-08 | [#8 复现启动并核对团队权限与CI](https://github.com/Jdeciderz23/frame-and-say/issues/8) |
| W2-01 | [#9 接入活动创建和列表界面](https://github.com/Jdeciderz23/frame-and-say/issues/9) |
| W2-02 | [#10 接入照片浏览、偏好和本人授权界面](https://github.com/Jdeciderz23/frame-and-say/issues/10) |
| W2-03 | [#11 完善成员访问与名单修订授权](https://github.com/Jdeciderz23/frame-and-say/issues/11) |
| W2-04 | [#12 实现有硬约束和解释的公平选择算法原型](https://github.com/Jdeciderz23/frame-and-say/issues/12) |
| W2-05 | [#13 完善上传、缩略图与持久化验证](https://github.com/Jdeciderz23/frame-and-say/issues/13) |

[初始化数据](issues.json)可用 `node scripts/publish-issues.mjs` 预览。导入脚本跳过已存在的任务，已发布任务的变更直接记录在 GitHub Issue。
