# 推荐输入输出契约

对应代码：`backend/src/main/java/com/groupphoto/consensus/recommendation/`，纯 Java，不依赖 Spring，也不访问数据库。

当前状态：**输入校验、候选过滤与确定性公平选择算法已实现**。选片接口 [`POST /api/events/{eventId}/selections`](openapi.yaml) 仍为 `planned`，薄接口接线在契约确认后接入。

本文件定义“应用用例交给算法什么”和“算法能返回什么”：先列当前可用的能力，再给出输入/输出契约、待确认的规则与验证方法。

## 当前可用的能力

| 能力 | 入口 | 说明 |
|---|---|---|
| 输入契约 | `SelectionInput`、`Candidate`、`EventMember`、`Preference` | 不可变 DTO，定义应用用例交给算法的全部字段 |
| 结构校验 | `SelectionInputValidator.validate` / `requireValid` | 一次返回全部问题；`requireValid` 在有问题时抛 `InvalidSelectionInputException` |
| 候选过滤 | `CandidateFilter.filter` | 只放行 `eligible = true` 且 `reason = ELIGIBLE`，按原因计数并给出缺口 |
| 对外原因码 | `FilteredCandidates.toReasonCounts` | 汇总 `ROSTER_UNCONFIRMED` / `AUTHORIZATION_REQUIRED` / `INSUFFICIENT_CANDIDATES` |
| 结果结构 | `SelectionResult` | 与 OpenAPI `Selection` 一致 |
| 公平选择 | `FairSelector.select` | 贪心 max-min 选择，输出确定选片、效用指标与不足原因（#12） |

## 输入

| 类型 | 字段 | 说明 |
|---|---|---|
| `SelectionInput` | `eventId` | 活动 ID，用于日志和审计，不参与计算 |
| | `purpose` | 授权用途，复用 `Publications.Purpose`：`INTERNAL_SHARE` 或 `PUBLIC_POST` |
| | `requestedCount` | 目标数量，取值 1–100 |
| | `members` | 参与本次选片的成员 |
| | `candidates` | 确切照片版本及其授权结论 |
| | `preferences` | 每位成员对每个候选版本的评分 |
| `EventMember` | `memberId` | 成员 ID。角色不影响计数，发起人同样计入成员效用 |
| `Candidate` | `photoId` | 照片 ID |
| | `photoVersionId` | 确切照片版本 ID；算法只处理传入的版本，不自行取“最新版本” |
| | `subjectRevision` | 出镜名单修订号，用于追溯授权结论绑定的修订 |
| | `authorization` | 授权策略给出的 `Publications.Eligibility`（`eligible` + `reason`） |
| `Preference` | `memberId` / `photoVersionId` | 评分对象 |
| | `score` | `-1`、`0`、`1`，或 `null`（明确未评分） |

## 评分语义

- 三档评分与偏好表 `preference_value` 的 `CHECK (preference_value BETWEEN -1 AND 1)` 一致，不另设档位。
- `null` 表示**未评分**，与评分为 `0` 不同。未评分必须显式写一条 `Preference` 记录，**不能靠缺少条目表达**。
- 输入要求“完整矩阵”：每位成员 × 每个候选版本都必须有一条记录。缺少条目视为调用方构造错误，由校验报出 `MISSING_PREFERENCE`，而不是悄悄当成 0 或中性。

## 授权语义（不可放宽）

- `Candidate.authorization` 只能来自授权模块的 `Publications.evaluate(...)` 结论。推荐算法**只读取**它，不调用数据库、不重新计算、不修改。
- `eligible = false`（拒绝、未确认、名单未确认）的候选一律不进入可选集合，评分再高也不能入选。
- 只有 `eligible = true` 且 `reason = ELIGIBLE` 的结论才进入可选集合；`reason` 为空、或 `eligible` 与 `reason` 不匹配的结论被校验标记，`CandidateFilter` 则抛出明确的输入错误，而不是静默放行。
- 校验只检查结论结构，不判断授权是否正确；授权判定仍由 `authorization` 模块唯一负责。

## 结构校验

`SelectionInputValidator.validate(input)` 返回全部问题，`requireValid(input)` 在有问题时抛出 `InvalidSelectionInputException`，便于接口层统一转成 `400`。

| 代码 | 触发条件 |
|---|---|
| `BLANK_EVENT_ID` | 活动 ID 为空白 |
| `INVALID_REQUESTED_COUNT` | 目标数量不在 1–100 |
| `BLANK_MEMBER_ID` / `BLANK_PHOTO_ID` / `BLANK_PHOTO_VERSION_ID` | 标识为空白 |
| `DUPLICATE_MEMBER_ID` | 成员 ID 重复 |
| `DUPLICATE_PHOTO_ID` | 同一张照片出现两次（即使版本不同） |
| `DUPLICATE_PHOTO_VERSION_ID` | 照片版本 ID 重复 |
| `NEGATIVE_SUBJECT_REVISION` | 名单修订号为负 |
| `INCONSISTENT_ELIGIBILITY` | `eligible` 与 `reason` 互相矛盾 |
| `MISSING_ELIGIBILITY_REASON` | 授权结论缺少 `reason`（为 `null`） |
| `BLANK_PREFERENCE_MEMBER` / `BLANK_PREFERENCE_VERSION` | 评分记录里的标识为空白 |
| `UNKNOWN_PREFERENCE_MEMBER` / `UNKNOWN_PREFERENCE_CANDIDATE` | 评分指向输入中不存在的成员或候选 |
| `INVALID_SCORE` | 评分不在 -1–1（`null` 合法） |
| `DUPLICATE_PREFERENCE` | 同一成员对同一版本有多条评分 |
| `MISSING_PREFERENCE` | 缺少某位成员对某个候选版本的记录 |

## 过滤结果与对外原因码

`CandidateFilter.filter(input)` 只应用授权结论，输出：

- `eligible`：只有 `eligible = true` 且 `reason = ELIGIBLE` 的候选，按输入顺序参与后续选择；结论不完整或不一致时抛 `IllegalArgumentException`；
- `excludedByReason`：按 `Publications.Reason` 统计的排除数量；
- `shortfall`：`目标数量 - 有效候选数`，小于 0 时记 0；
- `toReasonCounts()`：转成公开原因码，拒绝与未确认合并为 `AUTHORIZATION_REQUIRED`，只给数量，**不暴露是谁拒绝**。

| 公开原因码 | 来源 |
|---|---|
| `ROSTER_UNCONFIRMED` | 名单未确认的照片数量 |
| `AUTHORIZATION_REQUIRED` | `CONSENT_DENIED` + `CONSENT_PENDING` 的照片数量 |
| `INSUFFICIENT_CANDIDATES` | `shortfall` |

`SelectionResult` 与 OpenAPI 的 `Selection` 结构一致：`selectedPhotoVersionIds`、`requestedCount`、`complete`、`reasons`、`metrics`。当前只定义结果结构。JSON 响应始终包含 `metrics` 对象。未计算的效用在 Java 中为 `null`，序列化时省略对应字段。

## 模拟数据

`backend/src/test/java/com/groupphoto/consensus/recommendation/SyntheticScenario.java` 是人工构造的固定样例：

- 5 位合成成员 `member-a` 至 `member-e`，7 个合成照片版本（固定 UUID 字符串）；
- 完整评分矩阵，含一条明确未评分记录；
- 覆盖入选、拒绝、未确认、名单未确认四种结论。

样例不含真实照片、真实用户或伪造的真实偏好，可通过 JUnit 重复运行，结果一致（`CandidateFilterTest` 有确定性断言）。

## 公平选择算法（#12）

`FairSelector.select(input)` 是唯一入口：结构校验 → 硬约束过滤 → 贪心 max-min 选择 → 汇总指标与原因，返回 `SelectionResult`。

- **成员效用** = 该成员对已入选照片的评分之和；未评分（`null`）按 0 计入，与评分为 0 同等参与求和。
- **贪心 max-min**：每步在剩余可选候选中选一个，使「最小成员效用」最大；并列比较总效用；仍并列按 `photoVersionId` 字典序取最小者。结果确定、可重复。
- **指标**：`eligiblePhotoCount` 为可选候选数；`minimumMemberUtility` / `totalMemberUtility` 由算法计算。成员为空时 `minimumMemberUtility` 为 `null`（无成员可求最小值）。
- **完整度**：凑够 `requestedCount` 时 `complete = true`；否则选中全部可选候选并 `complete = false`，`reasons` 由 `FilteredCandidates.toReasonCounts()` 给出（含 `INSUFFICIENT_CANDIDATES` 缺口）。

这是启发式贪心，**不保证全局最优（leximin 公平解）**：它只看当前一步的最小效用，不做回溯。`FairSelectorTest` 的 `fairSelectionSacrificesTotalUtilityToRaiseTheMinimum` 构造了偏好分歧案例，说明公平选择会牺牲总效用、抬高最低效用，并与普通总分排序对比。

## 待团队确认的技术问题

以下条目仍需连同团队评审后写入正式契约：

1. 评分档位维持 `-1 / 0 / 1` 三档。
2. 目标数量缺省取活动的 `desiredPhotoCount`。

## 运行

```sh
./scripts/maven.sh -B test -Dtest='SelectionInputValidatorTest,CandidateFilterTest,SelectionResultSerializationTest'
./scripts/check.sh
```
