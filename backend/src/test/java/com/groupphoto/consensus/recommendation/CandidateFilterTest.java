package com.groupphoto.consensus.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.groupphoto.consensus.authorization.Publications;
import com.groupphoto.consensus.authorization.Publications.Eligibility;
import com.groupphoto.consensus.authorization.Publications.Reason;
import com.groupphoto.consensus.recommendation.CandidateFilter.FilteredCandidates;
import com.groupphoto.consensus.recommendation.SelectionInput.Candidate;
import com.groupphoto.consensus.recommendation.SelectionResult.ReasonCode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateFilterTest {

    @Test
    void deniedPendingAndUnconfirmedCandidatesAreExcluded() {
        FilteredCandidates filtered = CandidateFilter.filter(SyntheticScenario.sample());

        assertThat(filtered.eligible()).extracting(Candidate::photoVersionId)
                .containsExactly(SyntheticScenario.VERSION_1, SyntheticScenario.VERSION_2,
                        SyntheticScenario.VERSION_3, SyntheticScenario.VERSION_7);
        assertThat(filtered.excludedByReason())
                .containsEntry(Publications.Reason.CONSENT_DENIED, 1)
                .containsEntry(Publications.Reason.CONSENT_PENDING, 1)
                .containsEntry(Publications.Reason.ROSTER_UNCONFIRMED, 1)
                .doesNotContainKey(Publications.Reason.ELIGIBLE);
        assertThat(filtered.insufficient()).isFalse();
        assertThat(filtered.shortfall()).isZero();
    }

    @Test
    void unratedCandidateStaysEligible() {
        FilteredCandidates filtered = CandidateFilter.filter(SyntheticScenario.sample());

        assertThat(filtered.eligible()).extracting(Candidate::photoVersionId)
                .contains(SyntheticScenario.VERSION_7);
    }

    @Test
    void authorizationReasonCountsDoNotRevealIndividuals() {
        FilteredCandidates filtered = CandidateFilter.filter(SyntheticScenario.sample());

        assertThat(filtered.toReasonCounts())
                .extracting(SelectionResult.ReasonCount::code, SelectionResult.ReasonCount::photoCount)
                .containsExactlyInAnyOrder(
                        tuple(ReasonCode.ROSTER_UNCONFIRMED, 1),
                        tuple(ReasonCode.AUTHORIZATION_REQUIRED, 2));
    }

    @Test
    void shortfallIsReportedWhenEligibleIsFewerThanRequested() {
        SelectionInput sample = SyntheticScenario.sample();
        SelectionInput askingTen = new SelectionInput(sample.eventId(), sample.purpose(), 10,
                sample.members(), sample.candidates(), sample.preferences());

        FilteredCandidates filtered = CandidateFilter.filter(askingTen);

        assertThat(filtered.eligible()).hasSize(4);
        assertThat(filtered.shortfall()).isEqualTo(6);
        assertThat(filtered.insufficient()).isTrue();
        assertThat(filtered.toReasonCounts())
                .extracting(SelectionResult.ReasonCount::code, SelectionResult.ReasonCount::photoCount)
                .contains(tuple(ReasonCode.INSUFFICIENT_CANDIDATES, 6));
    }

    @Test
    void filteringTheSameInputRepeatedlyIsDeterministic() {
        SelectionInput sample = SyntheticScenario.sample();

        FilteredCandidates first = CandidateFilter.filter(sample);
        FilteredCandidates second = CandidateFilter.filter(sample);

        assertThat(second.eligible()).isEqualTo(first.eligible());
        assertThat(second.excludedByReason()).isEqualTo(first.excludedByReason());
        assertThat(second.shortfall()).isEqualTo(first.shortfall());
        assertThat(second.toReasonCounts()).isEqualTo(first.toReasonCounts());
    }

    @Test
    void emptyCandidateListIsHandled() {
        SelectionInput sample = SyntheticScenario.sample();
        SelectionInput empty = new SelectionInput(sample.eventId(), sample.purpose(), sample.requestedCount(),
                sample.members(), List.of(), List.of());

        FilteredCandidates filtered = CandidateFilter.filter(empty);

        assertThat(filtered.eligible()).isEmpty();
        assertThat(filtered.shortfall()).isEqualTo(sample.requestedCount());
        assertThat(filtered.toReasonCounts())
                .containsExactly(new SelectionResult.ReasonCount(ReasonCode.INSUFFICIENT_CANDIDATES,
                        sample.requestedCount()));
    }

    @Test
    void inconsistentConclusionIsRejectedNotCountedAsEligible() {
        SelectionInput sample = SyntheticScenario.sample();
        List<Candidate> candidates = new ArrayList<>(sample.candidates());
        candidates.set(0, SyntheticScenario.candidate(SyntheticScenario.PHOTO_1, SyntheticScenario.VERSION_1, 3,
                new Eligibility(true, Reason.CONSENT_DENIED)));

        SelectionInput input = new SelectionInput(sample.eventId(), sample.purpose(), sample.requestedCount(),
                sample.members(), candidates, sample.preferences());

        assertThatThrownBy(() -> CandidateFilter.filter(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SyntheticScenario.VERSION_1);
    }

    @Test
    void missingReasonConclusionThrowsClearErrorInsteadOfNullPointer() {
        SelectionInput sample = SyntheticScenario.sample();
        List<Candidate> candidates = new ArrayList<>(sample.candidates());
        candidates.set(0, SyntheticScenario.candidate(SyntheticScenario.PHOTO_1, SyntheticScenario.VERSION_1, 3,
                new Eligibility(false, null)));

        SelectionInput input = new SelectionInput(sample.eventId(), sample.purpose(), sample.requestedCount(),
                sample.members(), candidates, sample.preferences());

        assertThatThrownBy(() -> CandidateFilter.filter(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorization");
    }
}
