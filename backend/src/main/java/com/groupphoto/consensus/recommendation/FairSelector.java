package com.groupphoto.consensus.recommendation;

import com.groupphoto.consensus.recommendation.CandidateFilter.FilteredCandidates;
import com.groupphoto.consensus.recommendation.SelectionInput.Candidate;
import com.groupphoto.consensus.recommendation.SelectionInput.EventMember;
import com.groupphoto.consensus.recommendation.SelectionInput.Preference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 确定性公平选择算法（贪心原型），对应 #12。
 *
 * <p>执行顺序：结构校验 → 硬约束过滤（{@link CandidateFilter}）→ 贪心 max-min 选择 → 汇总指标与对外原因。
 * 纯 Java，不访问数据库，也不重新计算授权；{@link SelectionInput.Candidate#authorization()} 只读取。
 *
 * <p>选择规则：每一步在剩余可选候选中选一个，使「最小成员效用」最大；并列时比较总效用；
 * 仍并列则按 {@code photoVersionId} 字典序取最小者，保证结果确定、可重复。成员效用 = 该成员对已入选照片的
 * 评分之和；{@code null}（未评分）按 0 计入，与评分为 0 同等参与求和。
 *
 * <p>这是启发式贪心，不保证全局最优（leximin 公平解）：它只看当前一步的最小效用，不做回溯或穷举。
 * 是否替换为精确求解由团队在 #12 评审时决定；本类只保证确定性与上述贪心口径。
 */
public final class FairSelector {

    private FairSelector() {}

    /**
     * 执行完整选片，返回与 OpenAPI {@code Selection} 一致的 {@link SelectionResult}。
     *
     * @param input 已由应用用例从成员、照片版本、偏好和授权结论构造的输入；结构不合法时抛
     *              {@link SelectionInputValidator.InvalidSelectionInputException}
     */
    public static SelectionResult select(SelectionInput input) {
        Objects.requireNonNull(input, "input");
        SelectionInputValidator.requireValid(input);
        FilteredCandidates filtered = CandidateFilter.filter(input);
        List<Candidate> eligible = filtered.eligible();

        List<String> memberIds = input.members().stream().map(EventMember::memberId).toList();
        Map<ScoredVersion, Integer> scores = scoresByMemberVersion(input.preferences());

        int selectedCount = Math.min(input.requestedCount(), eligible.size());
        List<Candidate> remaining = new ArrayList<>(eligible);
        List<Candidate> selected = new ArrayList<>();
        int[] utilities = new int[memberIds.size()];

        for (int step = 0; step < selectedCount; step++) {
            Candidate best = pickBest(remaining, memberIds, utilities, scores);
            selected.add(best);
            remaining.remove(best);
            addScores(utilities, memberIds, best.photoVersionId(), scores);
        }

        boolean complete = selected.size() == input.requestedCount();
        List<String> selectedIds = selected.stream().map(Candidate::photoVersionId).toList();
        return new SelectionResult(selectedIds, input.requestedCount(), complete,
                filtered.toReasonCounts(), metrics(eligible.size(), memberIds, utilities));
    }

    private static Candidate pickBest(List<Candidate> candidates, List<String> memberIds,
                                      int[] utilities, Map<ScoredVersion, Integer> scores) {
        Candidate best = null;
        long bestMin = Long.MIN_VALUE;
        long bestTotal = Long.MIN_VALUE;
        String bestVersionId = "";
        for (Candidate candidate : candidates) {
            long min = Long.MAX_VALUE;
            long total = 0;
            for (int member = 0; member < memberIds.size(); member++) {
                long utility = utilities[member] + scoreOf(memberIds.get(member), candidate.photoVersionId(), scores);
                min = Math.min(min, utility);
                total += utility;
            }
            if (best == null
                    || min > bestMin
                    || (min == bestMin && total > bestTotal)
                    || (min == bestMin && total == bestTotal
                            && candidate.photoVersionId().compareTo(bestVersionId) < 0)) {
                best = candidate;
                bestMin = min;
                bestTotal = total;
                bestVersionId = candidate.photoVersionId();
            }
        }
        return best;
    }

    private static Map<ScoredVersion, Integer> scoresByMemberVersion(List<Preference> preferences) {
        Map<ScoredVersion, Integer> scores = new HashMap<>();
        for (Preference preference : preferences) {
            scores.put(new ScoredVersion(preference.memberId(), preference.photoVersionId()),
                    preference.score() == null ? 0 : preference.score());
        }
        return scores;
    }

    private static int scoreOf(String memberId, String photoVersionId, Map<ScoredVersion, Integer> scores) {
        return scores.getOrDefault(new ScoredVersion(memberId, photoVersionId), 0);
    }

    private static void addScores(int[] utilities, List<String> memberIds, String photoVersionId,
                                  Map<ScoredVersion, Integer> scores) {
        for (int member = 0; member < memberIds.size(); member++) {
            utilities[member] += scoreOf(memberIds.get(member), photoVersionId, scores);
        }
    }

    private static SelectionResult.Metrics metrics(int eligibleCount, List<String> memberIds, int[] utilities) {
        if (memberIds.isEmpty()) {
            return new SelectionResult.Metrics(eligibleCount, null, 0.0);
        }
        int min = Integer.MAX_VALUE;
        int total = 0;
        for (int utility : utilities) {
            min = Math.min(min, utility);
            total += utility;
        }
        return new SelectionResult.Metrics(eligibleCount, (double) min, (double) total);
    }

    /** 成员 × 版本 的评分键；用于把评分矩阵建成 O(1) 查询。 */
    private record ScoredVersion(String memberId, String photoVersionId) {}
}
