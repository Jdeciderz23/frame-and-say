package com.groupphoto.consensus.recommendation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * 选片结果契约，与 OpenAPI 中 planned 的 {@code Selection} 响应一致。
 *
 * <p>当前只定义结构；确定性选择和效用计算见 #12。原因码是公开汇总，不暴露具体是哪位成员拒绝。
 */
public record SelectionResult(
        List<String> selectedPhotoVersionIds,
        int requestedCount,
        boolean complete,
        List<ReasonCount> reasons,
        Metrics metrics) {

    public SelectionResult {
        selectedPhotoVersionIds = List.copyOf(selectedPhotoVersionIds);
        reasons = List.copyOf(reasons);
        Objects.requireNonNull(metrics, "metrics");
    }

    /** 对外的汇总原因码，与 OpenAPI {@code Selection.reasons[].code} 保持一致。 */
    public enum ReasonCode {
        ROSTER_UNCONFIRMED,
        AUTHORIZATION_REQUIRED,
        INSUFFICIENT_CANDIDATES
    }

    public record ReasonCount(ReasonCode code, int photoCount) {
        public ReasonCount {
            Objects.requireNonNull(code, "code");
            if (photoCount < 0) {
                throw new IllegalArgumentException("photoCount must not be negative");
            }
        }
    }

    /**
     * 汇总指标。算法尚未实现时，效用字段可以为 {@code null}，表示本轮未计算，而不是 0。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metrics(Integer eligiblePhotoCount, Double minimumMemberUtility, Double totalMemberUtility) {}
}
