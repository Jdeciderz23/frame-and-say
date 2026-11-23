# 本机 API 联调

按 [README](../../README.md) 初始化环境、检查依赖并运行 `./scripts/dev.sh`。已有 `.env` 需要分别配置两个随机令牌，对应 `organizer` 和 `participant`。以下命令读取本机配置，上传一张程序生成的像素图，并核对活动权限、缩略图和授权修订。

在另一个终端回到仓库根目录，执行：

```sh
SMOKE_STATE=$(mktemp "${TMPDIR:-/tmp}/frame-and-say-smoke.XXXXXX")
node --env-file=.env scripts/smoke-compose.mjs create "$SMOKE_STATE"
```

此脚本通过前端端口请求同源 `/api`，也直接检查后端 readiness。它可用于默认端口的本机 Vite/H2 环境，或 Compose 提供的 Nginx/MySQL 环境。脚本检查活动和照片归属、未加入成员的 `403`、受保护缩略图，以及发起人更新名单后原修订的授权请求返回 `409`。具体字段见 [OpenAPI 契约](openapi.yaml)。

使用 MySQL 和持久化图片目录时，保留上面的临时状态文件。先重启 MySQL 和后端，再验证先前创建的活动、成员关系、照片元数据及缩略图仍可读取。

```sh
node --env-file=.env scripts/smoke-compose.mjs verify "$SMOKE_STATE"
rm "$SMOKE_STATE"
```

本机 H2 重启后会清空。用 H2 联调只运行 `create`，完成后可直接删除临时状态文件。联调使用合成数据，建议为联调指定 `APP_PHOTO_DIR` 专用目录。

前端读取缩略图时，用 `fetch` 带上活动成员令牌，再用返回的 Blob 创建对象 URL，离开页面时回收。联调接口保留未评分的 `null`，区别于中性偏好 `0`。改动授权时先读取当前 `subjectRevision`，收到 `409` 后刷新界面并让用户重新确认。联调令牌只用于本机。正式登录、推荐调用和导出仍由对应成员开发。

从首页选择「打开联调相册」，进入 `/album`。先连接当前活动成员的本机令牌，再选择脚本创建的活动。令牌保存在当前标签页的会话存储，选择「断开联调身份」可清除。复制相册地址时只包含活动和照片版本标识。用没有加入活动的令牌打开该地址时，页面提示无法访问。
