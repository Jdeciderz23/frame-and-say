# W2-05 图片上传、缩略图与持久化验证

2026-11-24（北京时间），对应 [#13](https://github.com/Jdeciderz23/frame-and-say/issues/13)，基于主线 `bd2c171`。本次验证使用合成图片和两个演示身份。W1-05 的输入边界回归见 [PR #56](https://github.com/Jdeciderz23/frame-and-say/pull/56)，两个分支可独立评审。

## 验收场景

| 场景 | 可观察结果 |
|---|---|
| 单张 JPEG/PNG 上传 | 活动成员收到 `201`。响应和列表仅含照片及版本元数据、受保护缩略图路径。JPEG、PNG 原图字节各自与上传一致。 |
| 真实 multipart 边界 | 单文件恰好 8 MiB、含表单开销的请求恰好 9 MiB 时上传成功。单文件 8 MiB + 1 字节时返回 `413`。直连后端在请求总长 9 MiB + 1 字节时返回 `413`。经 Nginx 时，同样超限的匿名请求收到 `413`，直连的匿名请求收到 `401`，据此确认代理在鉴权前拦截。 |
| 非法输入 | 空文件 `400`，伪装成 PNG 的文本及截断 PNG `415`。非法、超限和非成员上传后，活动列表仅包含两张成功上传的照片。 |
| 缩略图与隔离 | 匿名 `401`；已登录但属于其他活动时，尚未加入目标活动返回 `403`；同时属于两个活动但活动、照片或版本组合不匹配时返回 `404`。合法成员读取 PNG，返回 `Cache-Control: no-store`。HTTP 缩略图与私有文件逐字节一致，后端测试实际解码并核对尺寸。 |
| 存储失败与清理 | 模拟写失败、删除已上传缩略图后读取，合法访问得到通用 `503`，错误正文不含文件名或磁盘路径。写失败不创建照片/版本行；读取失败不删除已存在的原图或元数据。文件写入后若目录事务因活动满 100 张而拒绝，应用清理新文件。 |
| V1/V2 | 测试先使用上传 API 创建 V1 并确认名单，再用独立文件和数据库记录构造 V2。列表仅返回该照片最新版本，V2 名单尚未确认；各版本地址读取自己的缩略图，V1 原图与缩略图不被覆盖。新版本上传 API 属于 [US-21 / #39](https://github.com/Jdeciderz23/frame-and-say/issues/39)。 |
| 重启一致性 | 重启之前记录完整照片元数据、两份原图和两份缩略图的 SHA-256。重启后用原活动成员读取，列表、版本标识和哈希相同，缩略图仍拒绝匿名请求。 |

活动隔离、权限失败顺序与 [lzndXJ 的 #7 评审记录](https://github.com/Jdeciderz23/frame-and-say/issues/7#issuecomment-7129522773)对照。PR 评审时请照片接口维护方与 lzndXJ 复核新增场景，重点是不同活动下的 `403/404` 与存储不可读时的 `503`。

## 本机实测

Windows / Temurin 21.0.13.1 / Maven 3.9.9 / Node 24.22.3 / pnpm 11.25.0：

- 在 `backend` 目录执行 `./mvnw.cmd '-Dmaven.repo.local=../.cache/m2' -B clean verify`，34 项测试通过，其中 `PhotoStorageBoundaryIntegrationTest` 新增 4 项。
- 从仓库根目录执行 `pnpm --dir frontend test`、`pnpm --dir frontend build`，6 项测试通过，类型检查及生产构建成功。
- 启动打包的后端 JAR，使用 `demo` 配置并将 `SPRING_DATASOURCE_URL` 指向独立的文件型 H2 数据库。为同一轮检查设置专用 `APP_PHOTO_DIR`，两次启动均使用同一数据库、存储目录及临时演示令牌。使用 Nginx 1.28.3 Windows 便携版，沿用 [Nginx 配置](../../frontend/nginx.conf)的代理位置、`9m` 限额和 413 处理，替换 Docker DNS 上游为本机地址。依次执行：

  ```powershell
  node scripts/smoke-photo-storage.mjs create $state --nginx
  # 停止并用相同配置重新启动后端，等待 readiness 返回 UP。
  node scripts/smoke-photo-storage.mjs verify $state --nginx
  ```

  两阶段均通过。后端以不同进程 ID 启动，文件型 H2、原图和缩略图均成功跨进程读取。验证结束后停止本次服务并删除临时数据库、图片、状态和盘符映射。本机运行辅助脚本 `.tools/w2-05-restart.ps1` 和日志保存在 Git 忽略目录。Windows Nginx 无法读取当前中文路径时，便携版使用了仅映射至测试临时目录的 ASCII 盘符。

## MySQL Compose 和 MinIO

仓库的 `mysql-compose` CI 在原有业务冒烟之外运行图片专项检查。下列命令适用于已按 README 初始化的专用测试 Compose 项目，设置独立项目名及空闲端口，避免使用已有业务数据卷。

```sh
docker compose --profile app up -d --build --wait
node --env-file=.env scripts/smoke-photo-storage.mjs create "$state" --compose
docker compose --profile app stop frontend backend mysql
docker compose --profile app up -d --wait
node --env-file=.env scripts/smoke-photo-storage.mjs verify "$state" --compose
```

`$state` 指向专用临时状态文件。Compose 模式通过 Nginx 调用 API，并在后端容器内只读核对图片卷中的原图及缩略图。结束后清理专用测试项目和临时状态文件。

Windows 和 Ubuntu WSL 未具备 Docker CLI，本机实测覆盖 Nginx + 文件型 H2。MySQL Compose 的新增场景以本分支的远程 CI 结果为准。当前 `MINIO_IMAGE` 为空，未验证镜像、服务和私有桶，MinIO 适配器联调继续阻塞。待具备已核验镜像和测试环境后，先拉取并检查私有桶，再验收适配器。

Codex 协助编写测试、执行实测和整理契约说明。测试使用合成图片；本分支的人工作业复核和组员评审待进行。
