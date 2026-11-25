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
