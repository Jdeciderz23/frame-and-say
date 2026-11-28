package com.groupphoto.consensus.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.groupphoto.consensus.authorization.Publications.Eligibility;
import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.authorization.Publications.Reason;
import com.groupphoto.consensus.recommendation.SelectionInput.Candidate;
import com.groupphoto.consensus.recommendation.SelectionInput.EventMember;
import com.groupphoto.consensus.recommendation.SelectionInput.Preference;
import com.groupphoto.consensus.recommendation.SelectionResult.ReasonCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class FairSelectorTest {

    @Test
    void syntheticScenarioSelectsDeterministicallyAndRaisesMinimumUtility() {
        SelectionResult result = FairSelector.select(SyntheticScenario.sample());

        assertThat(result.selectedPhotoVersionIds())
                .containsExactly(SyntheticScenario.VERSION_3, SyntheticScenario.VERSION_7, SyntheticScenario.VERSION_2);
        assertThat(result.requestedCount()).isEqualTo(3);
        assertThat(result.complete()).isTrue();
        assertThat(result.metrics().eligiblePhotoCount()).isEqualTo(4);
        assertThat(result.metrics().minimumMemberUtility()).isEqualTo(1.0);
        assertThat(result.metrics().totalMemberUtility()).isEqualTo(7.0);
        assertThat(result.reasons())
                .extracting(SelectionResult.ReasonCount::code, SelectionResult.ReasonCount::photoCount)
                .containsExactlyInAnyOrder(
                        tuple(ReasonCode.ROSTER_UNCONFIRMED, 1),
                        tuple(ReasonCode.AUTHORIZATION_REQUIRED, 2));
    }

    @Test
    void rejectedPendingAndUnconfirmedAreNeverSelectedEvenWithHighScores() {
        SelectionResult result = FairSelector.select(SyntheticScenario.sample());

        assertThat(result.selectedPhotoVersionIds())
                .doesNotContain(SyntheticScenario.VERSION_4_DENIED,
                        SyntheticScenario.VERSION_5_PENDING, SyntheticScenario.VERSION_6_UNCONFIRMED);
    }

    @Test
    void repeatedRunsProduceIdenticalResults() {
        SelectionInput sample = SyntheticScenario.sample();

        assertThat(FairSelector.select(sample)).isEqualTo(FairSelector.select(sample));
    }

    @Test
    void emptyCandidateListYieldsEmptySelectionAndInsufficiency() {
        SelectionInput sample = SyntheticScenario.sample();
        SelectionInput empty = new SelectionInput(sample.eventId(), sample.purpose(), sample.requestedCount(),
                sample.members(), List.of(), List.of());

        SelectionResult result = FairSelector.select(empty);

        assertThat(result.selectedPhotoVersionIds()).isEmpty();
        assertThat(result.complete()).isFalse();
        assertThat(result.metrics().eligiblePhotoCount()).isZero();
        assertThat(result.metrics().minimumMemberUtility()).isEqualTo(0.0);
        assertThat(result.metrics().totalMemberUtility()).isEqualTo(0.0);
        assertThat(result.reasons())
                .extracting(SelectionResult.ReasonCount::code, SelectionResult.ReasonCount::photoCount)
                .containsExactly(tuple(ReasonCode.INSUFFICIENT_CANDIDATES, 3));
    }

    @Test
    void requestingMoreThanEligibleSelectsAllAndMarksIncomplete() {
        SelectionInput sample = SyntheticScenario.sample();
        SelectionInput askingTen = new SelectionInput(sample.eventId(), sample.purpose(), 10,
                sample.members(), sample.candidates(), sample.preferences());

        SelectionResult result = FairSelector.select(askingTen);

        assertThat(result.selectedPhotoVersionIds())
                .containsExactly(SyntheticScenario.VERSION_3, SyntheticScenario.VERSION_7,
                        SyntheticScenario.VERSION_2, SyntheticScenario.VERSION_1);
        assertThat(result.complete()).isFalse();
        assertThat(result.reasons())
                .extracting(SelectionResult.ReasonCount::code, SelectionResult.ReasonCount::photoCount)
                .contains(tuple(ReasonCode.INSUFFICIENT_CANDIDATES, 6));
    }

    @Test
    void tieOnMinimumAndTotalIsBrokenBySmallerVersionId() {
        SelectionInput input = input(1, List.of("a", "b"),
                new CandidateDef("v2", 1, 0),
                new CandidateDef("v1", 1, 0));

        SelectionResult result = FairSelector.select(input);

        assertThat(result.selectedPhotoVersionIds()).containsExactly("v1");
    }

    @Test
    void requestedCountBoundaryOneSelectsSinglePhoto() {
        SelectionInput sample = SyntheticScenario.sample();
        SelectionInput askingOne = new SelectionInput(sample.eventId(), sample.purpose(), 1,
                sample.members(), sample.candidates(), sample.preferences());

        SelectionResult result = FairSelector.select(askingOne);

        assertThat(result.selectedPhotoVersionIds()).containsExactly(SyntheticScenario.VERSION_3);
        assertThat(result.complete()).isTrue();
    }

    @Test
    void nullScoreCountsAsZeroInUtility() {
        SelectionInput input = input(1, List.of("a"), new CandidateDef("only", (Integer) null));

        SelectionResult result = FairSelector.select(input);

        assertThat(result.selectedPhotoVersionIds()).containsExactly("only");
        assertThat(result.metrics().minimumMemberUtility()).isEqualTo(0.0);
        assertThat(result.metrics().totalMemberUtility()).isEqualTo(0.0);
    }

    @Test
    void zeroMembersSelectByVersionIdWithoutUtility() {
        SelectionInput input = new SelectionInput("event-test", Purpose.INTERNAL_SHARE, 2, List.of(),
                List.of(
                        new Candidate("b", "b", 0, new Eligibility(true, Reason.ELIGIBLE)),
                        new Candidate("a", "a", 0, new Eligibility(true, Reason.ELIGIBLE))),
                List.of());

        SelectionResult result = FairSelector.select(input);

        assertThat(result.selectedPhotoVersionIds()).containsExactly("a", "b");
        assertThat(result.metrics().minimumMemberUtility()).isNull();
        assertThat(result.metrics().totalMemberUtility()).isEqualTo(0.0);
    }

    @Test
    void purposeIsRetainedSoInternalShareAndPublicPostResultsAreDistinguishable() {
        SelectionInput sample = SyntheticScenario.sample();
        SelectionInput publicPost = new SelectionInput(sample.eventId(), Purpose.PUBLIC_POST,
                sample.requestedCount(), sample.members(), sample.candidates(), sample.preferences());

        SelectionResult internal = FairSelector.select(sample);
        SelectionResult external = FairSelector.select(publicPost);

        // 同一套候选与评分，两种用途产出相同选片序列；但结果显式保留用途，调用方可区分归属。
        assertThat(internal.purpose()).isEqualTo(Purpose.INTERNAL_SHARE);
        assertThat(external.purpose()).isEqualTo(Purpose.PUBLIC_POST);
        assertThat(internal.selectedPhotoVersionIds()).isEqualTo(external.selectedPhotoVersionIds());
        assertThat(internal).isNotEqualTo(external);
    }

    /**
     * #35 验收场景：一个用途（INTERNAL_SHARE）、目标两张、四个候选版本。
     *
     * <p>两个已获全员许可（ELIGIBLE）、一个待确认（CONSENT_PENDING）、一个被拒且评分最高
     * （CONSENT_DENIED，其评分总和最高）。两次运行只选两张已获许可版本、顺序一致，
     * 被拒与待确认的高分图均被排除。
     */
    @Test
    void issue35TwoEligibleOfFourVersionsAreSelectedDeterministically() {
        List<String> members = List.of("member-a", "member-b", "member-c");
        SelectionInput input = new SelectionInput(
                "event-35", Purpose.INTERNAL_SHARE, 2,
                members.stream().map(EventMember::new).toList(),
                List.of(
                        new Candidate("photo-1", "v1", 3, new Eligibility(true, Reason.ELIGIBLE)),
                        new Candidate("photo-2", "v2", 3, new Eligibility(true, Reason.ELIGIBLE)),
                        new Candidate("photo-3", "v3", 3, new Eligibility(false, Reason.CONSENT_PENDING)),
                        new Candidate("photo-4", "v4", 3, new Eligibility(false, Reason.CONSENT_DENIED))),
                List.of(
                        // V1 全员许可 [1,0,0]
                        new Preference("member-a", "v1", 1),
                        new Preference("member-b", "v1", 0),
                        new Preference("member-c", "v1", 0),
                        // V2 全员许可 [0,1,0]
                        new Preference("member-a", "v2", 0),
                        new Preference("member-b", "v2", 1),
                        new Preference("member-c", "v2", 0),
                        // V3 待确认 [1,1,0]
                        new Preference("member-a", "v3", 1),
                        new Preference("member-b", "v3", 1),
                        new Preference("member-c", "v3", 0),
                        // V4 被拒且评分最高 [1,1,1]
                        new Preference("member-a", "v4", 1),
                        new Preference("member-b", "v4", 1),
                        new Preference("member-c", "v4", 1)));

        SelectionResult first = FairSelector.select(input);
        SelectionResult second = FairSelector.select(input);

        // 同样输入运行两次得到相同顺序（确定、可重复）
        assertThat(first).isEqualTo(second);

        // 只选两张已获许可版本，顺序一致
        assertThat(first.selectedPhotoVersionIds()).containsExactly("v1", "v2");
        assertThat(first.complete()).isTrue();

        // 被拒（评分最高）与待确认的高分图均被排除
        assertThat(first.selectedPhotoVersionIds()).doesNotContain("v3", "v4");

        // 汇总原因：待确认 + 被拒合并为 AUTHORIZATION_REQUIRED，只给数量、不暴露是谁
        assertThat(first.reasons())
                .extracting(SelectionResult.ReasonCount::code, SelectionResult.ReasonCount::photoCount)
                .containsExactly(tuple(ReasonCode.AUTHORIZATION_REQUIRED, 2));

        // 指标：两个可选候选，最低成员效用 0，总效用 2
        assertThat(first.metrics().eligiblePhotoCount()).isEqualTo(2);
        assertThat(first.metrics().minimumMemberUtility()).isEqualTo(0.0);
        assertThat(first.metrics().totalMemberUtility()).isEqualTo(2.0);
    }

    @Test
    void fairSelectionSacrificesTotalUtilityToRaiseTheMinimum() {
        List<String> members = List.of("a", "b", "c");
        SelectionInput input = input(2, members,
                new CandidateDef("common", 1, 1, 1),
                new CandidateDef("excluded", 1, 1, -1),
                new CandidateDef("neutral", 0, 0, 0));

        SelectionResult fair = FairSelector.select(input);
        List<String> totalScoreTop = naiveTotalScoreTop(input, 2);

        // 普通总分排序选总分最高的 common(3)、excluded(1)；公平选择选 common、neutral。
        assertThat(totalScoreTop).containsExactly("common", "excluded");
        assertThat(fair.selectedPhotoVersionIds()).containsExactly("common", "neutral");

        // 公平选择牺牲总效用（3 < 4），却把最低成员效用从 0 抬高到 1。
        assertThat(fair.metrics().minimumMemberUtility()).isGreaterThan(minUtility(input, totalScoreTop));
        assertThat(fair.metrics().totalMemberUtility()).isLessThan(totalUtility(input, totalScoreTop));
    }

    private record CandidateDef(String versionId, Integer... scores) {}

    private static SelectionInput input(int requested, List<String> memberIds, CandidateDef... defs) {
        List<EventMember> members = memberIds.stream().map(EventMember::new).toList();
        List<Candidate> candidates = new ArrayList<>();
        List<Preference> preferences = new ArrayList<>();
        for (CandidateDef def : defs) {
            candidates.add(new Candidate(def.versionId(), def.versionId(), 0,
                    new Eligibility(true, Reason.ELIGIBLE)));
            for (int member = 0; member < memberIds.size(); member++) {
                preferences.add(new Preference(memberIds.get(member), def.versionId(), def.scores()[member]));
            }
        }
        return new SelectionInput("event-test", Purpose.INTERNAL_SHARE, requested, members, candidates, preferences);
    }

    private static List<String> naiveTotalScoreTop(SelectionInput input, int count) {
        List<Candidate> sorted = new ArrayList<>(CandidateFilter.filter(input).eligible());
        sorted.sort(Comparator
                .comparingInt((Candidate candidate) -> -totalScore(input, candidate.photoVersionId()))
                .thenComparing(Candidate::photoVersionId));
        return sorted.stream().limit(count).map(Candidate::photoVersionId).toList();
    }

    private static int totalScore(SelectionInput input, String photoVersionId) {
        int total = 0;
        for (Preference preference : input.preferences()) {
            if (preference.photoVersionId().equals(photoVersionId) && preference.score() != null) {
                total += preference.score();
            }
        }
        return total;
    }

    private static List<Integer> memberUtilities(SelectionInput input, List<String> selectedVersionIds) {
        List<String> memberIds = input.members().stream().map(EventMember::memberId).toList();
        List<Integer> utilities = new ArrayList<>();
        for (String memberId : memberIds) {
            int utility = 0;
            for (Preference preference : input.preferences()) {
                if (preference.memberId().equals(memberId)
                        && selectedVersionIds.contains(preference.photoVersionId())
                        && preference.score() != null) {
                    utility += preference.score();
                }
            }
            utilities.add(utility);
        }
        return utilities;
    }

    private static double minUtility(SelectionInput input, List<String> selectedVersionIds) {
        return memberUtilities(input, selectedVersionIds).stream().mapToInt(Integer::intValue).min().orElse(0);
    }

    private static double totalUtility(SelectionInput input, List<String> selectedVersionIds) {
        return memberUtilities(input, selectedVersionIds).stream().mapToInt(Integer::intValue).sum();
    }
}
