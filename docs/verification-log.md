# 工程验证记录

| 提交 | 验证结果 |
|---|---|
| [`0afe50e`](https://github.com/Jdeciderz23/frame-and-say/commit/0afe50e8a961b599b34afbc4c6767055928800d6) | 前端 9 项测试、类型检查及构建通过，后端 119 项测试通过。MySQL Compose 经 Nginx 验证上传限额，重启数据库与后端后再次核对照片元数据、原图及缩略图。[CI 记录](https://github.com/Jdeciderz23/frame-and-say/actions/runs/45435403593) |
| [`85550db`](https://github.com/Jdeciderz23/frame-and-say/commit/85550db076d8cbb848eb20bcf46ee24a778de0cf) | 调研报告与首批用户故事经 [#16](https://github.com/Jdeciderz23/frame-and-say/pull/16) 合入主线。前端、后端和 MySQL Compose 均通过。[CI 记录](https://github.com/Jdeciderz23/frame-and-say/actions/runs/44682641343) |
| [`95008d7`](https://github.com/Jdeciderz23/frame-and-say/commit/95008d7ac828cd741ac1a51a6a5b4ff2f0c2117c) | Maven 下载失败后重跑成功。Compose 检查通过 Nginx 调用业务接口，重启后再次读取活动、照片元数据和缩略图。[CI 记录](https://github.com/Jdeciderz23/frame-and-say/actions/runs/44584398646) |

## 2026-11-24 私有图片存储与输入校验

对应 [Issue #5](https://github.com/Jdeciderz23/frame-and-say/issues/5)，负责人 `zqjiejie`。
基于主线 `bd2c171`，本地分支 `test/5-image-validation`。本次补充测试与契约说明，未修改生产存储、图片处理或成员授权代码。

### 验收证据

| 要求 | 自动验证与结果 |
|---|---|
| 文件名和存储键独立 | JPEG/PNG 分别使用 `../../same.*`、`C:\uploads\same.*`，扩展名与内容不符且声明 `text/plain`，每个名字连续上传两次。成功生成两个不同 UUID 版本；原始字节保持一致；目录内仅有 `original` 与 `thumbnail.png`；数据库媒体类型来自真实内容。 |
| 响应仅提供受保护缩略图路径 | 上传与列表响应精确检查七个契约字段，没有文件名、磁盘路径或原图地址；缩略图路径绑定活动、照片和版本。匿名读取 `401`、非成员 `403`、跨活动 `404`；成员读取 PNG 且 `Cache-Control: no-store`。尝试静态文件路径和原图 API 返回 `404`。 |
| 临时私有目录与清理 | 集成测试和存储单测使用 JUnit `@TempDir`，不写默认 `data/photos`。存储测试验证删除幂等、只删除目标版本、拒绝覆盖；支持 POSIX 时断言版本目录为 `0700`。本次 Windows 运行使用继承的系统临时目录 ACL，未验证 POSIX 权限分支。正常测试结束后未发现 `junit*` 目录或 `backend/data/photos`。 |
| JPEG/PNG 真实解码 | 合成非纯色像素并检查解码后的黑白区域、尺寸、缩放比例和小图不放大。拒绝伪扩展名文本、伪装 GIF，以及仍可读取尺寸但无法解码的 PNG/JPEG。合法图片即使扩展名或声明类型不符也按真实内容接受，这与现有实现一致。 |
| 文件和像素边界 | 两种格式均覆盖恰好 8 MiB、超过 8 MiB、恰好 2400 万像素、超过 2400 万像素；覆盖 12000 和 12001 的宽/高边界。额外验证声明长度过大时不打开流、声明长度过小时仍限制实际读取、空文件和读取失败。 |
| 拒绝后的状态 | 两种格式的空文件、伪装文本、字节超限及像素超限分别返回 `400` / `415` / `413`，且文件集合、照片表和版本表均无新增。 |

测试文件：

- [PhotoImageProcessorTest](../backend/src/test/java/com/groupphoto/consensus/photo/PhotoImageProcessorTest.java)：29 项。
- [LocalPhotoStorageTest](../backend/src/test/java/com/groupphoto/consensus/photo/LocalPhotoStorageTest.java)：2 项。
- [PhotoWorkflowIntegrationTest](../backend/src/test/java/com/groupphoto/consensus/photo/PhotoWorkflowIntegrationTest.java)：14 项，其中原有 2 项，新增 12 项。
- [SyntheticImages](../backend/src/test/java/com/groupphoto/consensus/photo/SyntheticImages.java)：内存生成测试图片，不提交真实照片或依赖外部图片文件。

### 实际命令与结果

环境：Windows / PowerShell、Temurin **21.0.13.1**、Maven Wrapper **3.9.9**、Node **24.22.3**、pnpm **11.25.0**。
Java 便携包放在 Git 忽略的 `.tools/temurin21`，已按 Adoptium 返回的 SHA-256 校验；未更改全局 Java 配置。

在仓库根目录执行前端检查：

```powershell
$env:CI = 'true'
$env:npm_config_cache = "$PWD/.cache/npm"
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend test
pnpm --dir frontend build
```

安装退出 `0`；前端 **6 项通过**，类型检查和生产构建通过（34 个模块）。

从 `backend` 目录执行后端检查，本机命令如下；已有 Java 21 的成员可使用自己的 `JAVA_HOME`：

```powershell
$env:JAVA_HOME = (Resolve-Path '../.tools/temurin21/jdk-21.0.13.1+2').Path
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:MAVEN_USER_HOME = (Join-Path (Split-Path $PWD) '.cache/maven')
./mvnw.cmd '-Dmaven.repo.local=../.cache/m2' -B verify
```

结果：`BUILD SUCCESS`，后端 **73 项通过，0 失败、0 错误、0 跳过**（本次新增 43 项）。H2 执行 Flyway V1、V2，生成 `target/group-photo-consensus.jar`。
本机完整日志位于忽略目录 `.tools/backend-verify.log`，JUnit 报告位于 `backend/target/surefire-reports/`。

已实际尝试 `scripts/check.sh`：内置 Git shell 起初缺工具 PATH，补齐后仍无法识别本机 `pnpm.cmd`。因此执行上述 PowerShell 等价步骤，**不将 shell 脚本本身记为通过**。沙箱中的集成测试还遇到 Java attach 命名管道访问拒绝；停止该次运行后，在允许进程通信的环境完成上面的全量验证。

另用本次打包的 JAR 启动 H2 demo 与 Vite（仅 `127.0.0.1:8080` / `5173`），进程环境注入两枚随机演示令牌和独立 `APP_PHOTO_DIR`，执行：

```powershell
node scripts/smoke-compose.mjs create <专用临时目录/state.json>
```

结果：退出 `0`，经 Vite 代理完成活动创建、成员访问、合成 PNG 上传、受保护缩略图读取及名单修订导致旧授权失效。补充 HTTP 检查确认匿名缩略图返回 `401`，磁盘恰有一份原图和一份缩略图。验证结束后停止本次启动的两个进程并删除专用图片及状态目录。本机复现辅助脚本为忽略目录中的 `.tools/w1-05-smoke.ps1`，标准手动流程见[本机 API 联调](api/local-walkthrough.md)。

`git diff --check` 通过。

### Docker / MinIO 的实际状态与后续边界

| 检查 | 本机结果 |
|---|---|
| `Get-Command docker -ErrorAction SilentlyContinue` | 无结果；默认 Docker Desktop CLI 路径也不存在，未具备 Docker/Compose 环境。 |
| `MINIO_IMAGE` | 当前进程未配置；仓库 `.env.example` 留空，storage overlay 要求显式提供已核验镜像。 |
| MinIO 镜像拉取、启动、桶权限、适配器联调 | **未验证**，没有把任何镜像标成可用。生产代码当前仅有本地存储适配器。 |
| MySQL 与服务重启后的持久化 | 本次为 H2 测试和 demo，**未验证** MySQL 或重启持久化，不能引用上方历史 CI 结果代替本机证据。 |

后续在具备 Docker 后，由成员核验具体 MinIO 镜像来源、标签或摘要并实际拉取、启动、检查私有桶，再记录 `MINIO_IMAGE`；MinIO 适配器、代理请求总大小 9 MiB、存储失败与重启一致性继续按 [#13](https://github.com/Jdeciderz23/frame-and-say/issues/13) 跟进。MockMvc 上传测试不经过真实 Servlet multipart 解析或 Nginx，不作为这两层上传限额的验证。

## 2026-11-24：#3 后端健康与身份边界

基于主线 `bd2c171` 验证。范围及复现方法见[后端基础验证](api/backend-baseline.md)。

| 检查 | 实际结果 |
|---|---|
| 工具链 | Windows 11，Java 21.0.10（本机 DataGrip 内置 JDK）、Maven Wrapper 3.9.9、Node 24.13.0、pnpm 11.25.0。未修改系统 Java 配置。 |
| 后端完整检查 | 仓库根目录执行 `backend/mvnw.cmd -f backend/pom.xml -Dmaven.repo.local=.cache/m2 -B -ntp verify`，42 项测试通过，0 失败、0 错误、0 跳过，打包成功。 |
| 数据库基础 | 测试实际执行 H2 `SELECT 1` 与 Flyway V1、V2；注入连接故障后 readiness 返回 503/DOWN，HTTP 探针与 liveness 仍返回 200。 |
| 身份与成员 | 新增 10 个参数化或独立验收场景，覆盖双身份、无效凭据、非成员拒绝、冒用字段与头/参数、失败请求无写入，以及目标成员 ID 不改变操作者。样例数据逐次回滚。 |
| 未配置令牌 | 未配置演示令牌时，即使提供测试凭据，业务接口仍返回 401。测试身份样例保留在 `src/test`，已检查最终 JAR 不含新增的测试身份或身份边界测试类。 |
| 前端回归 | 按锁文件安装依赖后，`pnpm --dir frontend test` 的 6 项测试通过；`pnpm --dir frontend build` 的类型检查与生产构建通过。 |
| 实际 HTTP | 使用构建后的 JAR、demo H2 和随机本机端口启动。`/api/health`、readiness、liveness 均返回 200/UP；无凭据访问 `/api/events` 返回 401，并携带 `WWW-Authenticate: Bearer`。检查后已停止本次进程。 |
| 文档检查 | 新增后端基础说明，明确健康接口含义、demo/test H2 与 local MySQL 配置、合成账号和正式认证缺口。文档相对链接及 `git diff --check` 通过。 |

执行 Maven 前，将本次进程的 `JAVA_HOME` 指向本机 JDK 21，并将 `MAVEN_USER_HOME` 指向仓库内 `.cache/maven`。依赖、构建产物和临时 HTTP 日志均在忽略目录中。

本机 MySQL 阻塞：`docker info` 提示 Docker Engine 管道不存在，当前引擎未运行，未执行本机 MySQL 实连或重启持久化验证。H2 结果不作为 MySQL 结果；对应验证由 PR 的 `mysql-compose` 检查补充，本机复现继续在 #8 跟进。正式登录与邀请方案由 #7、#11 继续评审。

## 本地联调基线

以下为早期联调快照。前端 6 项测试、类型检查和生产构建通过。后端 7 项测试通过，H2 执行 Flyway V1、V2。通过 Vite 代理使用两组测试身份验证了活动隔离、合成图片上传、受保护的缩略图，以及出镜名单修订后拒绝旧授权请求（HTTP 409）。

本地运行与测试命令见[项目 README](../README.md)。复现业务请求见[API 联调步骤](api/local-walkthrough.md)。MinIO 适配器的独立验证仍待跟踪。[环境与 CI #8](https://github.com/Jdeciderz23/frame-and-say/issues/8)
