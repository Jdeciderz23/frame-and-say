# 照有商量 · Frame & Say

照有商量让一次活动里的成员一起挑照片，并让出镜的人各自决定照片可以分享给谁。课程设计面向 5～10 人、最多 100 张照片的小型活动。

当前基线提供主页、服务检查，以及可供前后端联调的活动创建与列表、成员访问、单图上传与缩略图、偏好和按照片版本及出镜名单修订记录的授权。接下来由五人分别开发业务界面、正式身份和邀请流程、公平选片与结果解释，以及确认后导出。接口分为已实现和待实现两类，见 [OpenAPI](docs/api/openapi.yaml)。

## 加入开发

接受 [私有仓库](https://github.com/Jdeciderz23/frame-and-say) 的邀请后，使用本人的 GitHub 账号克隆：

```sh
git clone https://github.com/Jdeciderz23/frame-and-say.git
cd frame-and-say
```

准备 Git、Java 21、Node.js 24 和 pnpm 11.25.0。Maven 由 Wrapper 提供。首次下载依赖需要网络。若已有旧目录，先用 `git remote -v` 核对地址，无需改目录名。

```sh
./scripts/init-env.sh
./scripts/check.sh
./scripts/dev.sh
```

打开 [前端](http://127.0.0.1:5173)，使用页面中的「检查服务连接」验证 Vite 代理。[应用健康检查](http://127.0.0.1:8080/api/health) 只确认应用存活， [就绪检查](http://127.0.0.1:8080/actuator/health/readiness) 还检查当前数据库。健康响应内的 `group-photo-consensus` 是内部应用标识。

默认 `demo` 使用内存 H2 执行 Flyway 迁移，应用重启后数据库内容会清空。测试请使用合成图片。照片文件位于服务端私有目录，可用 `APP_PHOTO_DIR` 指定专用位置。业务记录需要持久化时请使用 MySQL 模式。

`init-env.sh` 为新 `.env` 生成本地密码和两个独立的令牌，已有文件会保留。旧开发环境如果缺少 `APP_BOOTSTRAP_ORGANIZER_TOKEN` 或 `APP_BOOTSTRAP_PARTICIPANT_TOKEN`，请分别使用 `openssl rand -hex 24` 生成并填入各自键值。两个合成身份分别为 `organizer`、`participant`，令牌只供本机测试，留空时相应身份无法认证。不要通过 Git、网页代码或 Issue 共享令牌。用两个身份实际调用接口的步骤见 [本机 API 联调](docs/api/local-walkthrough.md)。

Ctrl+C 会停止统一启动的两个服务。宿主开发使用固定端口 5173 和 8080。Mac/Linux 可直接运行脚本，Windows 可用 WSL 或在 IDE 中独立启动。

## MySQL 与容器开发

```sh
./scripts/init-env.sh
docker compose up -d --wait mysql
./scripts/dev.sh --local
```

`local` 使用 MySQL 8.4，并应用 `backend/src/main/resources/db/migration/` 下的 Flyway 迁移。密码只保存在本机的 `.env`。首次联调检查数据库 readiness，再创建一条测试数据，重启应用验证记录仍在。切勿用 H2 测试结果代替 MySQL 证据。修改 `.env` 不会自动更新既有数据卷中的 MySQL 账号密码。

如需前后端也运行在容器中：

```sh
docker compose --profile app up -d --build --wait
docker compose ps
```

容器模式中，后端等 MySQL 就绪，前端等后端就绪。Nginx 与 Spring 的上传上限一起维护。停止服务使用 `docker compose --profile app stop`，保留数据库和私有图片卷。

`docker-compose.storage.yml` 可用于独立的 MinIO 实验，届时需在 `.env` 中填写已核验的 `MINIO_IMAGE`。当前应用使用私有本地存储。后续接入 MinIO 适配器时，需要单独验证容器端点、私有桶与应用专用访问凭证。

## 工程与模块

```text
frontend/      Vue 3 + TypeScript + Vite，独立测试与构建
backend/       Spring Boot 单体，按业务分包，持久化使用 Flyway
docs/          需求调研、模块边界、五人分工和 API 契约
scripts/       开发启动、检查、环境配置和任务导入
.github/       Actions、Issue / PR 模板和分支保护基线
```

日常验证用 `./scripts/check.sh`。独立开发时可用 `pnpm --dir frontend dev` 或 `./scripts/maven.sh spring-boot:run`，独立的 Maven 入口从启动环境读取变量。CI 分别验证前端、后端和 MySQL 容器启动链路。

## 任务与评审

到 [Issues](https://github.com/Jdeciderz23/frame-and-say/issues) 认领任务。从最新的 `main` 开短期分支，提交带真实 Issue 编号和验证结果的 PR，请一位非作者评审。仓库管理员结合实际 Actions 检查名和私有仓库套餐设置合并保护。成员认领、评审轮换和冲突处理见 [GitHub 协作规范](docs/05-github-workflow.md)。

- [五人分工](docs/04-team-responsibilities.md) 与 [前两周计划](docs/06-first-two-weeks.md)
- [技术架构](docs/03-architecture.md) 与 [API 契约](docs/api/openapi.yaml)
- [启动文档索引](docs/README.md) 与 [工程验证记录](docs/验证记录.md)
