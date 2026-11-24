# 照有商量 · Frame & Say

照有商量是一款面向旅行、聚会和社团活动的协同选片工具。大家可以在同一场活动里浏览照片、表达偏好，出镜者也可以决定自己的照片能否分享。喜欢一张照片和同意发布是两回事。

## 项目功能

- **活动与成员**：创建活动、添加成员，活动内容仅对成员开放。
- **照片上传与浏览**：支持单张 JPEG 或 PNG 上传并生成缩略图，照片存储在服务端的私有目录。
- **个人偏好**：每位成员可以查看和修改自己的照片评分。
- **发布授权**：出镜者按照片版本、出镜名单和分享用途确认授权。名单变更后需要重新确认。

在网页主导航打开「我的活动」，连接本机联调身份后即可创建活动、查看已加入的活动，点击活动名称可进入相册。服务连接可在「服务连接」页检查。见 [API 文档](docs/api/openapi.yaml)和[本地体验步骤](docs/api/local-walkthrough.md)。

## 本地运行

需要 Java 21、Node.js 24 和 pnpm 11.25.0，Maven 由 Wrapper 提供。在仓库根目录运行：

```sh
./scripts/init-env.sh
pnpm --dir frontend install --frozen-lockfile
./scripts/dev.sh
```

打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)。后端运行在 `8080`，默认使用内存 H2，重启后演示数据会清空。初始化脚本会在本地 `.env` 生成用于体验 API 的测试令牌。

需要持久化存储时，先启动 Docker，再运行：

```sh
docker compose up -d --wait mysql
./scripts/dev.sh --local
```

## 开发与测试

`frontend/` 使用 Vue 3、TypeScript 和 Vite。`backend/` 使用 Spring Boot 和 Flyway，业务数据库为 MySQL 8.4。

```sh
./scripts/check.sh
```

该命令运行前端测试、类型检查和生产构建，并执行后端测试与打包。更多模块和数据设计见 [技术架构](docs/03-architecture.md)。
