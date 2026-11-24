# 本机 API 联调

按 [README](../../README.md) 初始化环境、检查依赖并运行 `./scripts/dev.sh`。已有 `.env` 需要分别配置两个随机令牌，对应 `organizer` 和 `participant`。以下命令读取本机配置，上传一张程序生成的像素图，并核对活动权限、缩略图和授权修订。

在另一个终端回到仓库根目录，执行：

```sh
SMOKE_STATE=$(mktemp "${TMPDIR:-/tmp}/frame-and-say-smoke.XXXXXX")
node --env-file=.env scripts/smoke-compose.mjs create "$SMOKE_STATE"
```

此脚本通过前端端口请求同源 `/api`，也直接检查后端 readiness。它可用于默认端口的本机 Vite/H2 环境，或 Compose 提供的 Nginx/MySQL 环境。脚本检查双身份的成员查询、非成员 `403`、跨活动照片 `404`、代签拒绝、群内与公开用途隔离，以及名单变更后的旧修订 `409`、刷新与本人再次确认。具体字段见 [OpenAPI 契约](openapi.yaml)。

使用 MySQL 和持久化图片目录时，保留上面的临时状态文件。先重启 MySQL 和后端，再验证先前创建的活动、成员关系、照片元数据及缩略图仍可读取。

```sh
node --env-file=.env scripts/smoke-compose.mjs verify "$SMOKE_STATE"
rm "$SMOKE_STATE"
```

本机 H2 重启后会清空。用 H2 联调只运行 `create`，完成后可直接删除临时状态文件。联调使用合成数据，建议为联调指定 `APP_PHOTO_DIR` 专用目录。

前端读取缩略图时，用 `fetch` 带上活动成员令牌，再用返回的 Blob 创建对象 URL，离开页面时回收。联调接口保留未评分的 `null`，区别于中性偏好 `0`。改动授权时先读取当前 `subjectRevision`，收到 `409` 后刷新界面并让用户重新确认。联调令牌只用于本机。正式登录、推荐调用和导出仍由对应成员开发。

## 授权过期后的刷新约定

1. 收到授权请求的 409 后，清除尚未保存的同意/拒绝选择，提示照片或出镜信息已更新。
2. 重新读取活动照片列表，取得该照片当前的 `photoVersionId`，再按当前用途读取 `consents/me`，取得最新修订和 `canDecide`。
3. 普通出镜者不调用发起人专用的出镜名单 GET 接口。若 `canDecide=false`，显示当前无法确认，等待发起人核实名单。
4. 若可决定，展示当前照片和用途，由本人再次作出选择后提交；不自动重放先前的同意。刷新期间遇到再次更新时，继续按 409 处理。

`MyConsent` 的五个字段和原有 204/400/403/404/409 响应状态保持一致，与 #53 的调用结构兼容。完整边界样例及本轮结果见[授权边界验证](authorization-boundaries.md)。

## 演示身份的启用范围

`app.bootstrap.enabled` 在基础配置中默认关闭，仅 demo、local、test 配置启用。关闭时，配置了两个演示令牌也不能认证；在其他环境或混合生产环境中强制启用会阻止启动。使用额外的本机 profile 时，需要显式选择允许的环境，不能将演示身份开关带入正式部署。

正式登录与邀请的迁移步骤见[身份与邀请方案（待评审）](identity-invitations-proposal.md)。当前 `addEventMember` 只添加已配置的合成成员，不代表已经实现邀请。
