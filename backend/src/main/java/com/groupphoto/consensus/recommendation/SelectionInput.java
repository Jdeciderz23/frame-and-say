package com.groupphoto.consensus.recommendation;

import com.groupphoto.consensus.authorization.Publications;
import java.util.List;
import java.util.Objects;

/**
 * 纯算法接收的不可变输入。
 *
 * <p>应用用例负责读取成员、当前照片版本、偏好和授权结论，再构造本对象。算法本身不访问数据库，
 * 也不重新计算授权。
 *
 * <p>评分只有 {@code -1}、{@code 0}、{@code 1} 三档，与偏好表 {@code preference_value} 的取值一致。
 * {@code null} 表示“未评分”，和评分为 {@code 0} 不同。未评分必须显式出现，不能用缺少偏好条目表达。
 *
 * <p>{@link Candidate#authorization()} 只能来自 {@link Publications#evaluate} 的结论。算法只读取
 * {@code eligible} 与 {@code reason}，不能把拒绝、未确认或名单未确认的照片当作可选。
 */
public record SelectionInput(
        String eventId,
        Publications.Purpose purpose,
        int requestedCount,
        List<EventMember> members,
        List<Candidate> candidates,
        List<Preference> preferences) {

    public SelectionInput {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(purpose, "purpose");
        members = List.copyOf(members);
        candidates = List.copyOf(candidates);
        preferences = List.copyOf(preferences);
    }

    /** 参与本次选片的成员。角色不影响计数，发起人同样按成员效用计算。 */
    public record EventMember(String memberId) {
        public EventMember {
            Objects.requireNonNull(memberId, "memberId");
        }
    }

    /**
     * 一张确切照片版本及其出镜名单修订。
     *
     * @param subjectRevision 名单修订号，用于把授权结论绑定到具体版本
     * @param authorization   授权策略给出的结论，不可由算法修改
     */
    public record Candidate(
            String photoId,
            String photoVersionId,
            long subjectRevision,
            Publications.Eligibility authorization) {
        public Candidate {
            Objects.requireNonNull(photoId, "photoId");
            Objects.requireNonNull(photoVersionId, "photoVersionId");
            Objects.requireNonNull(authorization, "authorization");
        }
    }

    /**
     * 一位成员对一张确切照片版本的评分。
     *
     * @param score {@code -1}、{@code 0} 或 {@code 1}；{@code null} 表示明确未评分
     */
    public record Preference(String memberId, String photoVersionId, Integer score) {
        public Preference {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(photoVersionId, "photoVersionId");
        }
    }
}
