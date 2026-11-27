# 后端健康、数据库与身份边界验证

对应任务 [#3](https://github.com/Jdeciderz23/frame-and-say/issues/3)。本页说明后端基础验证的范围，便于成员复现和后续联调。

## 健康接口与数据库探针

| 入口 | 作用 | 数据库不可用时 |
|---|---|---|
| `GET /api/health` | 前端检查 HTTP 服务是否可连接，返回 `status: UP` 和 `application: group-photo-consensus`。 | HTTP 服务仍可响应时继续返回 200，不能据此认定数据库可用。 |
| `GET /actuator/health/readiness` | 就绪检查，包含应用就绪状态和真实数据库探针。 | 返回 503，状态为 `DOWN`，不公开内部连接信息。 |
| `GET /actuator/health/liveness` | 检查应用进程存活，不将外部数据库故障当成进程故障。 | 应用仍存活时返回 200。 |

这三个 GET 入口无需身份凭据。业务接口仍需 Bearer 凭据。

`HealthIntegrationTest` 直接向测试 H2 执行 `SELECT 1`，并验证健康接口的响应契约。数据库故障用测试替身在连接获取处注入异常，验证 readiness 返回 503，同时 HTTP 探针与 liveness 仍可用。测试结束后自动重置替身。

## 数据库配置

| Profile | 数据库 | 使用方式与限制 |
|---|---|---|
| `demo`（默认） | 内存 H2 | 无需 Docker；用于本机演示，重启后数据清空。 |
| `test` | 独立内存 H2 | 配置在 `src/test/resources`，由自动化测试显式启用；执行真实 SQL 和 Flyway 迁移。身份边界测试逐次回滚业务数据。 |
| `local` | MySQL | 连接地址来自 `DB_HOST`、`DB_PORT`、`MYSQL_DATABASE`，账号来自 `MYSQL_USER`，密码由 `MYSQL_PASSWORD` 提供。需先准备 MySQL，再按根目录 README 启动。 |

H2 的 MySQL 兼容模式不等于 MySQL 实连验证。没有可运行的 MySQL 时，应在验证记录中说明环境阻塞，后续再复现迁移、连接和重启后的数据读取。

## 测试身份与活动成员样例

`src/test/java` 下的 `SyntheticActors` 提供两个合成身份，由测试类显式配置，仅供自动化测试复用。

| 身份 | 样例中的成员关系 |
|---|---|
| `organizer` | 创建活动 A，在 A 中为发起人。 |
| `participant` | 创建活动 B，在 B 中为发起人；起初不是 A 的成员，经 A 的发起人添加后，在 A 中为普通参与者。 |

角色属于具体活动，不由测试账号的名称决定。`IdentityBoundaryIntegrationTest` 通过真实的认证过滤链和业务接口建立上述关系，并验证：

- 无凭据或凭据无效时，成员接口返回 401。
- 非成员无法读取或修改另一活动的成员关系，失败后数据库不新增成员。
- 创建活动时夹带 `userId`、`actorId` 或 `role` 等未知字段返回 400，不创建活动或成员记录。
- 额外请求头或查询参数不能将当前操作者改成别人。
- 添加成员接口中的 `userId` 表示被添加的目标成员；权限仍按 Bearer 凭据中的当前操作者判断，普通成员不能借此取得发起人权限。

现有 `EventMembershipIntegrationTest` 继续验证成员添加的幂等性和角色不被覆盖。未配置演示令牌时，业务接口保持关闭，健康接口仍可访问。

## 本机演示身份与正式认证

当前 `BootstrapActors` 将本机显式配置的令牌对应到两个固定合成账号，供 demo/local 联调使用。使用根目录的初始化脚本在未入库的 `.env` 中生成本机令牌，不使用自动化测试中的固定凭据。

这套机制尚无正式账号登录、凭据签发与过期、退出及邀请生命周期。正式认证与邀请方案在 [#7](https://github.com/Jdeciderz23/frame-and-say/issues/7) 评审，由 [#11](https://github.com/Jdeciderz23/frame-and-say/issues/11) 跟进；本任务不将演示凭据作为正式登录交付。

## 运行检查

需要 Java 21。在仓库根目录运行后端检查：

```sh
./scripts/maven.sh -B verify
```

Windows PowerShell 可在 `backend` 目录使用 Wrapper：

```powershell
.\mvnw.cmd -B verify
```

后端在 demo 模式启动后，可用 PowerShell 检查实际 HTTP 响应：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

实际执行环境、命令与结果记录在[工程验证记录](../verification-log.md)。
