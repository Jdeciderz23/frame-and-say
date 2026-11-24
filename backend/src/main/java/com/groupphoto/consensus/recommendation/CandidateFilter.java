package com.groupphoto.consensus.recommendation;

import com.groupphoto.consensus.authorization.Publications;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 硬约束过滤：只应用授权策略给出的结论，不做评分排序、效用计算或目标数量选择，那些见 #12。
 *
 * <p>拒绝、未确认、名单未确认的照片一律不进入候选。只有 {@code eligible = true} 且 {@code reason = ELIGIBLE}
 * 的结论才视为可选；结论不完整（{@code reason} 为空）或不一致（如 {@code eligible = true} 却带拒绝原因）时，
 * 抛出 {@link IllegalArgumentException} 作为明确的输入错误，而不是静默放行或抛出 {@link NullPointerException}。
 * 过滤结果只按原因计数，不记录具体拒绝了谁。
 */
public final class CandidateFilter {
    private CandidateFilter() {}

    public static FilteredCandidates filter(SelectionInput input) {
        Objects.requireNonNull(input, "input");
        List<SelectionInput.Candidate> eligible = new ArrayList<>();
        Map<Publications.Reason, Integer> excluded = new EnumMap<>(Publications.Reason.class);
        for (SelectionInput.Candidate candidate : input.candidates()) {
            Publications.Eligibility authorization = candidate.authorization();
            if (authorization.eligible() && authorization.reason() == Publications.Reason.ELIGIBLE) {
                eligible.add(candidate);
            } else if (!authorization.eligible()
                    && authorization.reason() != null
                    && authorization.reason() != Publications.Reason.ELIGIBLE) {
                excluded.merge(authorization.reason(), 1, Integer::sum);
            } else {
                throw new IllegalArgumentException(
                        "Invalid authorization conclusion for candidate " + candidate.photoVersionId()
                                + ": " + authorization);
            }
        }
        int shortfall = Math.max(0, input.requestedCount() - eligible.size());
        return new FilteredCandidates(eligible, excluded, shortfall);
    }

    /**
     * 过滤结果。
     *
     * @param eligible          可按原顺序参与后续选择的候选
     * @param excludedByReason  被排除的候选数量，按授权策略的原因分类
     * @param shortfall         距目标数量的缺口；0 表示候选足够
     */
    public record FilteredCandidates(
            List<SelectionInput.Candidate> eligible,
            Map<Publications.Reason, Integer> excludedByReason,
            int shortfall) {

        public FilteredCandidates {
            eligible = List.copyOf(eligible);
            excludedByReason = Map.copyOf(excludedByReason);
            if (shortfall < 0) {
                throw new IllegalArgumentException("shortfall must not be negative");
            }
        }

        public boolean insufficient() {
            return shortfall > 0;
        }

        /**
         * 转成对外原因码。拒绝和未确认合并为 {@code AUTHORIZATION_REQUIRED}，不区分是谁拒绝。
         */
        public List<SelectionResult.ReasonCount> toReasonCounts() {
            List<SelectionResult.ReasonCount> reasons = new ArrayList<>();
            int unconfirmed = excludedByReason.getOrDefault(Publications.Reason.ROSTER_UNCONFIRMED, 0);
            int unauthorized = excludedByReason.getOrDefault(Publications.Reason.CONSENT_DENIED, 0)
                    + excludedByReason.getOrDefault(Publications.Reason.CONSENT_PENDING, 0);
            if (unconfirmed > 0) {
                reasons.add(new SelectionResult.ReasonCount(SelectionResult.ReasonCode.ROSTER_UNCONFIRMED, unconfirmed));
            }
            if (unauthorized > 0) {
                reasons.add(new SelectionResult.ReasonCount(
                        SelectionResult.ReasonCode.AUTHORIZATION_REQUIRED, unauthorized));
            }
            if (shortfall > 0) {
                reasons.add(new SelectionResult.ReasonCount(
                        SelectionResult.ReasonCode.INSUFFICIENT_CANDIDATES, shortfall));
            }
            return List.copyOf(reasons);
        }
    }
}
