# 技术架构与模块边界

照有商量采用 Vue 3 + TypeScript 前端和 Spring Boot 3.5 模块化单体。前后端同仓库、独立构建，通过 `/api` 对接。Vite 用于本机开发，Compose 的应用配置使用 Nginx 转发。后端以 Java 21 为编译目标，推荐算法也使用 Java。

数据库迁移统一由 Flyway 执行。H2 用于开发演示和测试，MySQL 8.4 用于持久化。图片与缩略图写入服务端私有目录，数据库记录归属、版本和存储元数据。MinIO Compose 配置用于单独实验，实际接入需提供适配器及独立验证。

## 模块和调用方向

```mermaid
flowchart LR
  Web[Vue 应用] --> API["/api"]
  API --> Event[活动]
  API --> Membership[成员]
  API --> Photo[照片和版本]
  API --> Preference[偏好]
  API --> Consent[发布授权]
  API -. 待实现 .-> Selection[选片编排]
  Event --> Membership
  Photo --> Membership
  Preference --> Photo
  Consent --> Photo
  Consent --> Membership
  Photo --> Storage[私有文件存储]
  Selection -. 读取候选 .-> Photo
  Selection -. 读取评价 .-> Preference
  Selection -. 读取授权结论 .-> Consent
  Selection -. 提供不可变输入 .-> Algorithm[纯 Java 算法]
```

活动用例在创建活动的同一事务内写入发起人成员关系。成员资格和角色判断集中在 `membership`，该模块维护成员数据，避免反向依赖活动用例。照片模块负责照片归属、不可变的媒体版本、出镜名单修订和文件流。偏好与授权以已校验的照片版本和名单快照作为输入。授权策略不回调照片服务，纯算法只处理由应用用例准备好的输入。

前端各业务功能需要时新增 `features/events`、`features/photo-review` 等目录。已有 `views/` 提供主页和健康页，`api/` 承载共享请求工具。后端直接在 `com.groupphoto.consensus` 下按 `identity`、`event`、`membership`、`photo`、`preference`、`authorization`、`recommendation` 分包，多个功能共用的契约改动由消费者参与评审。

## API 与持久化约定

- [OpenAPI](api/openapi.yaml) 中的 `implemented` 表示当前联调入口，`planned` 表示接口草案。
- 业务请求使用显式 Bearer 头。当前联调身份由服务端配置，客户端不能指定操作者或其角色。正式登录和邀请流程待团队选型。
- 业务错误使用 `application/problem+json`。未提供有效身份返回 `401`，活动成员或角色不足返回 `403`，资源不存在或不属于路径指定活动返回 `404`，名单修订过期返回 `409`。
- 每个表保持清楚的写入归属。合入后的 Flyway 文件保留，新增变更使用下一版迁移。写入同时校验活动、照片及媒体版本的归属，数据库外键和唯一约束负责守住基本一致性。
- 业务文件限定 JPEG/PNG，单文件最多 8 MiB，单次请求最多 9 MiB。服务端先核对格式、尺寸与像素上限，再解码和生成缩略图。成员通过受保护的接口读取缩略图。

| 数据 | 写入归属 | 关键规则 |
|---|---|---|
| 活动 | 活动模块 | 创建活动与写入发起人成员关系同事务 |
| 成员 | 成员模块 | 一个用户在同一活动只有一条成员记录，角色仅在活动内生效 |
| 照片、版本、出镜名单 | 照片模块 | 照片属于一个活动，版本属于一张照片，名单变化递增 `subjectRevision` |
| 偏好 | 偏好模块 | 当前用户和照片版本唯一，未评分独立于评分 `0` |
| 发布授权 | 授权模块 | 当前用户、照片版本、用途和名单修订唯一 |
| 选片结果与导出 | 待开发 | 保存准确照片版本和用途，供后续复核和追溯 |

## 照片授权

首次上传后名单处于未确认状态。发起人提交名单时携带其看到的 `subjectRevision`，名单变化后使用新的修订号。明确确认空名单也会形成已确认的上下文。参与者提交授权时携带同一修订号，过期请求返回 `409` 并提示刷新。

授权用途采用 `INTERNAL_SHARE` 和 `PUBLIC_POST`。允许活动内查看缩略图属于成员访问权限。发布候选必须有已确认的名单，且名单内每个人都对该版本、用途和修订明确同意。拒绝或未确认时排除照片，不公开是谁拒绝。实际导出接口开发时还要在交付文件之前复核最新授权。

## 推荐开发起点

推荐模块负责选片和结果解释。应用层先校验访问范围，为每个可见照片版本取得授权结论，再把带有结论的候选版本、成员偏好和目标数量交给纯 Java 推荐模块。`CandidateFilter` 在过滤候选时一并统计排除原因，对当前用途已拒绝或待确认的版本计入 `AUTHORIZATION_REQUIRED`。候选不足时，过滤结果给出可用候选和数量缺口。对外只展示汇总原因和经确认的指标。输入输出与校验规则见[推荐输入输出契约](api/recommendation-dto.md)。

推荐算法使用完整的模拟评分比较贪心方案与总分排序。尝试优先改善最低个人效用，同分比较总效用，再用版本 ID 稳定排序。未评分处理、效用定义和目标权重由推荐模块维护者提案，经团队评审确认。正式用例见 [选片接口草案](api/openapi.yaml)。

## 数据实体与授权接口

下图对应 Flyway V1、V2。多处使用包含活动 ID 的复合外键防止跨活动引用。

```mermaid
erDiagram
  events ||--o{ event_members : contains
  events ||--o{ photos : contains
  photos ||--|{ photo_versions : versions
  photo_versions ||--o{ photo_subjects : roster
  event_members ||--o{ photo_subjects : appears
  photo_versions ||--o{ photo_preferences : rated
  event_members ||--o{ photo_preferences : rates
  photo_subjects ||--o{ photo_consents : decides
```

| 操作与写入方 | 可验证的 API 示例与可见范围 |
|---|---|
| 活动／成员模块分别维护 events、event_members | `createEvent` 创建发起人成员关系，`listEventMembers` 仅供本活动成员 |
| 照片模块维护 photos、photo_versions、photo_subjects | `confirmPhotoSubjects` 仅发起人可写正式名单，提交旧修订返回 409；纠错建议入口属候选 |
| 偏好模块维护 photo_preferences | `setMyPreference` 只写自己的版本评分，0 和缺少记录分别表示中性、未评 |
| 授权模块维护 photo_consents | `getMyPublicationConsent` 读取本人状态，`setMyPublicationConsent` 限当次名单中的本人；同一版本、用途和修订分别判定 |
| 推荐模块消费结论 | `selectPhotos` 为规划端点。发起人拟看可用与需处理的照片，公开解释仅给汇总。本人可见自己的具体决定，普通成员和管理员均无逐人决定清单 |

接口变更在 [#7](https://github.com/Jdeciderz23/frame-and-say/issues/7) 跟踪。可用两场活动、两名成员、两个用途和一次名单变更走查 US-04、06、07、09，再记录审阅人、问题、结论和对应 PR。

**接口走查样例（合成数据）**　以本活动成员 B 的令牌调用 `PUT /api/events/{E}/photos/{P}/consents/me`，其中 E、P 换成已创建的活动和照片 ID，V 换成实际版本 UUID。请求体为 `{"photoVersionId":"V","subjectRevision":2,"purpose":"INTERNAL_SHARE","decision":"ALLOW"}`。B 在修订 2 的名单中时预期返回 204。发起人随后修改名单，B 用修订 2 再次提交应返回 409。读取 `GET /api/events/{E}/photos/{P}/consents/me?photoVersionId=V&purpose=PUBLIC_POST` 只能取得当前用户在公开用途下的状态。逐步操作见[本机 API 联调](api/local-walkthrough.md)。
