# 成员与发布授权边界验证

对应 [#11](https://github.com/Jdeciderz23/frame-and-say/issues/11)，依据 [#7 权限评审](https://github.com/Jdeciderz23/frame-and-say/issues/7#issuecomment-7129522773)。本轮沿用现有接口和 `Publications.evaluate` 发布策略，补齐边界测试与双身份联调。

## 两个身份与活动

自动测试在独立 H2 中创建两场活动：活动 A 由 `organizer` 发起，`participant` 加入为普通成员；活动 B 由 `participant` 发起，`organizer` 不是成员。两场活动各有一张合成照片。账号名称不决定全局角色，权限按所在活动判断。

所有固定测试凭据只在 `src/test` 中配置。实际本机联调使用初始化脚本在 `.env` 中生成的随机令牌。业务身份始终来自 Bearer 凭据，不能通过请求体选择操作者。

## 本轮边界

| 场景 | 预期结果 |
|---|---|
| A 的普通成员查询 A 的成员 | 能读取本活动成员及角色。 |
| 非成员查询 B 的成员或授权 | 403；不新增授权记录。 |
| 同时属于两场活动，但混用活动、照片或版本 ID | 404；成员身份不替代资源归属校验。 |
| 发起人夹带 userId/actorId 试图代签 | 未知字段 400；不在出镜名单中的发起人直接提交也为 403。 |
| 普通成员读取或修改正式出镜名单 | 403；刷新本人授权状态应使用 `consents/me`。 |
| 发起人读取本人状态，而其他出镜者已拒绝 | 只获得发起人本人的状态，不返回其他人的具体决定。 |
| 群内同意、公开拒绝，或反向组合 | 两个用途分别判定，同意不能跨用途使用。 |
| 多人照片仅部分人同意 | 保持待确认，所有出镜者对当前版本、用途、修订均同意后才可用；任一人拒绝则不可用。 |
| 名单新增或移除人员 | 有效变更递增修订，旧许可不沿用；旧修订提交 409，已移除人员用当前修订提交为 403。 |
| 重复提交相同的已确认名单 | 修订不变，现有决定保留。 |
| 未确认空名单与发起人明确确认空名单 | 前者不具备发布资格；后者依照现有契约按空出镜集合判断。 |
| 产生新照片版本 | 新版本需要确认名单及用途；旧版本授权 GET/PUT 和候选资格检查均返回版本过期冲突。 |

名单变化后的测试同时核对历史记录仍存在、当前决定变为待确认、刷新前不新增记录，以及本人读取新修订并重新确认后的结果。

新版本场景使用测试专用 SQL 建立第二版元数据，并遵循照片行锁约定。该夹具只验证版本隔离；版本上传和文件交付分别由 #39、#42 实现。推荐资格的断言调用授权服务，不将其当成选片 HTTP 端点或导出链路已实现的证据。

## 演示身份限制

基础配置将 `app.bootstrap.enabled` 设为 false；demo、local、test 启用。启用时，所有实际使用的 profile 都必须属于这三类；未显式激活 profile 时检查默认 profile。其他环境默认拒绝演示令牌，强制启用则启动失败，混合 `production,demo` 也不能启用。格式错误、相同的双身份令牌和缺少令牌继续按现有规则处理。

这项限制不提供正式登录。第三方账号、会话过期、注销、正式账号目录和邀请仍按[身份与邀请方案](identity-invitations-proposal.md)评审。

## 验证入口

后端运行 `AuthorizationBoundaryIntegrationTest` 与 `BootstrapActorsTest`，完整检查使用：

```sh
./scripts/maven.sh -B clean verify
```

双身份 HTTP 联调继续使用现有脚本，MySQL Compose CI 也执行相同入口：

```sh
node --env-file=.env scripts/smoke-compose.mjs create /path/to/temporary-state.json
```

其中包含成员查询、活动隔离、代签拒绝、两个用途、修订过期后的刷新和显式重新确认。使用 MySQL 时，再由 `verify` 阶段检查重启后的数据及文件；H2 重启后数据清空。

## 2026-11-24 本机结果

基于主线 `bd2c171`，使用 Windows 11、Java 21.0.10、Maven Wrapper 3.9.9、Node 24.13.0、pnpm 11.25.0。

| 检查 | 实际结果 |
|---|---|
| 定向测试 | `BootstrapActorsTest` 11 项、`AuthorizationBoundaryIntegrationTest` 13 项，全部通过。 |
| 完整后端 | 在根目录执行 `backend/mvnw.cmd -f backend/pom.xml -Dmaven.repo.local=.cache/m2 -B -ntp clean verify`，54 项通过，0 失败、0 错误、0 跳过，打包成功。清理了切换分支后可能残留的旧测试产物。 |
| 前端 | `pnpm --dir frontend test` 的 6 项测试通过；`pnpm --dir frontend build` 的类型检查和生产构建通过。 |
| 实际 HTTP | 用构建后的 JAR、demo H2 和真实 Vite 代理，在随机本机端口运行增强后的 `smoke-compose.mjs create`，双身份与修订刷新检查通过。 |
| 非开发环境 | 为独立启动的 production profile 配置测试用内存 H2 和随机演示令牌，健康接口正常，两身份的业务请求均被拒绝为 401；显式设置 `app.bootstrap.enabled=true` 时启动失败。 |
| 静态检查 | `node --check scripts/smoke-compose.mjs`、OpenAPI YAML 解析、文档内部链接及 `git diff --check` 通过。 |

HTTP 验证使用进程环境中的随机凭据，临时日志与状态写入忽略的 `.cache`；验证后已停止本次启动的进程。Maven 使用仓库缓存与本机已有 JDK，未修改系统工具链设置。

本机 Docker 引擎尚未运行，未完成本机 MySQL 实连。增强脚本的 MySQL 结果及重启持久化验证以本 PR 的 `mysql-compose` CI 为准。新版本上传、导出和正式身份不属于本轮已实现能力。
