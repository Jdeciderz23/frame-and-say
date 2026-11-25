# 本机 API 联调

启动应用后，在首页选择「检查服务连接」，也可以直接访问 `/health`。页面调用同源 `GET /api/health`，仅在服务器返回 `{"status":"UP","application":"group-photo-consensus"}` 时显示连接正常。停止后端并重新检查可复现失败，恢复后端后再点「重新检查」即可复验。首页的流程说明用于介绍项目目标。

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

前端读取缩略图时，用 `fetch` 带上活动成员令牌，再用返回的 Blob 创建对象 URL，离开页面时回收。联调接口保留未评分的 `null`，区别于中性偏好 `0`。改动授权时先读取当前 `subjectRevision`，收到 `409` 后刷新界面并让用户重新确认。联调令牌只用于本机。正式登录和推荐调用仍由对应成员开发。

从首页选择「打开联调相册」，进入 `/album`。先连接当前活动成员的本机令牌，再选择脚本创建的活动。令牌保存在当前标签页的会话存储，选择「断开联调身份」可清除。复制相册地址时只包含活动和照片版本标识。用没有加入活动的令牌打开该地址时，页面提示无法访问。

## 授权过期后的刷新约定

1. 收到授权请求的 409 后，清除尚未保存的同意/拒绝选择，提示照片或出镜信息已更新。
2. 重新读取活动照片列表，取得该照片当前的 `photoVersionId`，再按当前用途读取 `consents/me`，取得最新修订和 `canDecide`。
3. 普通出镜者不调用发起人专用的出镜名单 GET 接口。若 `canDecide=false`，显示当前无法确认，等待发起人核实名单。
4. 若可决定，展示当前照片和用途，由本人再次作出选择后提交；不自动重放先前的同意。刷新期间遇到再次更新时，继续按 409 处理。

`MyConsent` 的五个字段和原有 204/400/403/404/409 响应状态保持一致，与 #53 的调用结构兼容。完整边界样例及本轮结果见[授权边界验证](authorization-boundaries.md)。

## 保存和比较照片版本

在相册中打开一张照片，用「照片版本」切换当前及历史缩略图。原上传者或活动发起人可在当前版本下选择修改后的 JPEG 或 PNG，再点「保存为新版本」。相册仍显示同一张照片，详情会切换到新版本；历史版本及其图片保留。对于没有上传者记录的旧照片，发起人可以提交新版本。

新的版本必须重新确认出镜名单、按用途取得授权。提交期间若当前版本被他人更新，请刷新版本列表并核对最新内容后再提交。[API 契约](openapi.yaml)记录了 `baseVersionId` 和相关响应。

## 演示身份的启用范围

`app.bootstrap.enabled` 在基础配置中默认关闭，仅 demo、local、test 配置启用。关闭时，配置了两个演示令牌也不能认证；在其他环境或混合生产环境中强制启用会阻止启动。使用额外的本机 profile 时，需要显式选择允许的环境，不能将演示身份开关带入正式部署。

正式登录与邀请的迁移步骤见[身份与邀请方案（待评审）](identity-invitations-proposal.md)。当前 `addEventMember` 只添加已配置的合成成员，不代表已经实现邀请。

## 导出经过复核的照片

发起人核对所需照片后，向 `POST /api/events/{eventId}/confirmed-selections` 提交用途和有序条目。每条包含 `photoId`、`photoVersionId`、`subjectRevision`。保存成功后，用同一身份调用 `GET /api/events/{eventId}/confirmed-selections/{selectionId}/export` 下载 ZIP。实际文件按版本 ID 命名，每个条目都是原图。导出直接读取服务端清单，已有授权只适用于对应的用途。

在专用合成活动环境中可运行以下冒烟检查。它创建两张已许可照片和一张清单外的照片，解压比较首次 ZIP 的原图，再撤回第二张照片的群内许可并确认重新下载返回 `409`。

```sh
EXPORT_STATE=$(mktemp "${TMPDIR:-/tmp}/frame-and-say-export.XXXXXX")
node --env-file=.env scripts/smoke-reviewed-export.mjs create "$EXPORT_STATE"
```

示例 ZIP 位于 `"$EXPORT_STATE.zip"`。使用持久化数据库时，可在重启后执行 `node --env-file=.env scripts/smoke-reviewed-export.mjs verify "$EXPORT_STATE"`，验证同一清单仍拒绝交付。完成后删除状态文件和样例 ZIP。

## 申请删除活动照片

原上传者用本人令牌向 `POST /api/events/{eventId}/photos/{photoId}/deletion-request` 提交申请。发起人通过 `GET /api/events/{eventId}/photo-deletion-requests` 查找申请，再以 `PUT /api/events/{eventId}/photos/{photoId}/deletion-request/approval` 批准。申请者或发起人可读取该照片的 `GET .../deletion-request`，核对申请人、处理人、时间和状态。

处理前，缩略图和已有合法导出仍可读取。批准后，相册移除整张照片，各版本旧链接返回 `404`，包含该照片的确认清单返回 `409`，发起人可重新确认仍有效的照片。处理记录和私有文件保留，实际清理与保留期限另行确定。未记载上传者的旧照片不能通过此流程申请。

用合成数据复验本流程：

```sh
DELETE_STATE=$(mktemp "${TMPDIR:-/tmp}/frame-and-say-deletion.XXXXXX")
node --env-file=.env scripts/smoke-photo-deletion.mjs create "$DELETE_STATE"
```

如果使用持久化数据库，可在重启后运行 `node --env-file=.env scripts/smoke-photo-deletion.mjs verify "$DELETE_STATE"`，核对已批准记录、旧链接和确认清单。结束后删除临时状态文件。

## 图片存储与输入边界回归

在仓库根目录执行：

```sh
./scripts/maven.sh -B -Dtest=PhotoImageProcessorTest,LocalPhotoStorageTest,PhotoWorkflowIntegrationTest test
```

Windows 可在 `backend` 目录用 `./mvnw.cmd -B '-Dtest=PhotoImageProcessorTest,LocalPhotoStorageTest,PhotoWorkflowIntegrationTest' test`。两种方式都需要 Java 21。
这组测试用内存合成的 JPEG/PNG 和 JUnit 自动清理的临时目录，验证内容解码、文件/像素边界、文件名与存储键隔离、受保护缩略图及拒绝上传无残留。
仅验证 H2 和本地文件适配器；Docker/MinIO、代理限额及持久化需独立联调。当前实测环境和结果见 [私有图片存储与输入校验记录](../验证记录.md#2026-11-24-私有图片存储与输入校验)。
