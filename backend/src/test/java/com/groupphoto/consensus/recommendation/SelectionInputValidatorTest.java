package com.groupphoto.consensus.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupphoto.consensus.authorization.Publications.Eligibility;
import com.groupphoto.consensus.authorization.Publications.Reason;
import com.groupphoto.consensus.recommendation.SelectionInput.Candidate;
import com.groupphoto.consensus.recommendation.SelectionInput.EventMember;
import com.groupphoto.consensus.recommendation.SelectionInput.Preference;
import com.groupphoto.consensus.recommendation.SelectionInputValidator.InvalidSelectionInputException;
import com.groupphoto.consensus.recommendation.SelectionInputValidator.InputProblem;
import com.groupphoto.consensus.recommendation.SelectionInputValidator.InputProblemCode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionInputValidatorTest {

    @Test
    void sampleInputHasNoProblems() {
        assertThat(SelectionInputValidator.validate(SyntheticScenario.sample())).isEmpty();
        SelectionInputValidator.requireValid(SyntheticScenario.sample());
    }

    @Test
    void requestedCountMustStayWithinOneToHundred() {
        for (int count : new int[] {0, -1, 101}) {
            assertThat(problemCodes(withRequestedCount(count)))
                    .contains(InputProblemCode.INVALID_REQUESTED_COUNT);
        }
        assertThat(problemCodes(withRequestedCount(1))).doesNotContain(InputProblemCode.INVALID_REQUESTED_COUNT);
        assertThat(problemCodes(withRequestedCount(100))).doesNotContain(InputProblemCode.INVALID_REQUESTED_COUNT);
    }

    @Test
    void duplicateMemberIsRejected() {
        List<EventMember> members = new ArrayList<>(SyntheticScenario.members());
        members.add(new EventMember(SyntheticScenario.MEMBER_IDS.get(0)));

        assertThat(problemCodes(withMembers(members))).contains(InputProblemCode.DUPLICATE_MEMBER_ID);
    }

    @Test
    void duplicatePhotoAndVersionAreRejected() {
        SelectionInput sample = SyntheticScenario.sample();
        List<Candidate> candidates = new ArrayList<>(sample.candidates());
        candidates.add(SyntheticScenario.candidate(SyntheticScenario.PHOTO_1, SyntheticScenario.uuid(999), 3,
                new Eligibility(true, Reason.ELIGIBLE)));
        List<Preference> preferences = new ArrayList<>(sample.preferences());
        for (String memberId : SyntheticScenario.MEMBER_IDS) {
            preferences.add(new Preference(memberId, SyntheticScenario.uuid(999), 0));
        }

        assertThat(problemCodes(withCandidates(candidates, preferences)))
                .contains(InputProblemCode.DUPLICATE_PHOTO_ID);

        List<Candidate> duplicateVersion = new ArrayList<>(sample.candidates());
        duplicateVersion.add(SyntheticScenario.candidate(SyntheticScenario.uuid(998), SyntheticScenario.VERSION_1, 3,
                new Eligibility(true, Reason.ELIGIBLE)));
        assertThat(problemCodes(withCandidates(duplicateVersion, sample.preferences())))
                .contains(InputProblemCode.DUPLICATE_PHOTO_VERSION_ID);
    }

    @Test
    void everyMemberNeedsAnExplicitPreferenceEntry() {
        SelectionInput sample = SyntheticScenario.sample();
        List<Preference> preferences = new ArrayList<>(sample.preferences());
        preferences.removeIf(preference -> preference.memberId().equals("member-c")
                && preference.photoVersionId().equals(SyntheticScenario.VERSION_2));

        List<InputProblem> problems = SelectionInputValidator.validate(withPreferences(preferences));

        assertThat(problems)
                .anySatisfy(problem -> {
                    assertThat(problem.code()).isEqualTo(InputProblemCode.MISSING_PREFERENCE);
                    assertThat(problem.target()).isEqualTo("member-c/" + SyntheticScenario.VERSION_2);
                });
    }

    @Test
    void unratedScoreIsAcceptedButNotCoercedToZero() {
        SelectionInput sample = SyntheticScenario.sample();
        Preference unrated = sample.preferences().stream()
                .filter(preference -> preference.memberId().equals("member-b")
                        && preference.photoVersionId().equals(SyntheticScenario.VERSION_7))
                .findFirst()
                .orElseThrow();

        assertThat(unrated.score()).isNull();
        assertThat(SelectionInputValidator.validate(sample)).isEmpty();
    }

    @Test
    void duplicatePreferenceIsRejected() {
        SelectionInput sample = SyntheticScenario.sample();
        List<Preference> preferences = new ArrayList<>(sample.preferences());
        preferences.add(new Preference("member-a", SyntheticScenario.VERSION_1, 1));

        assertThat(problemCodes(withPreferences(preferences))).contains(InputProblemCode.DUPLICATE_PREFERENCE);
    }

    @Test
    void scoreMustStayWithinMinusOneAndOne() {
        for (int score : new int[] {-2, 2}) {
            SelectionInput sample = SyntheticScenario.sample();
            List<Preference> preferences = new ArrayList<>(sample.preferences());
            preferences.removeIf(preference -> preference.memberId().equals("member-a")
                    && preference.photoVersionId().equals(SyntheticScenario.VERSION_1));
            preferences.add(new Preference("member-a", SyntheticScenario.VERSION_1, score));

            assertThat(problemCodes(withPreferences(preferences))).contains(InputProblemCode.INVALID_SCORE);
        }
    }

    @Test
    void preferenceMustReferenceKnownMemberAndCandidate() {
        SelectionInput sample = SyntheticScenario.sample();
        List<Preference> unknownMember = new ArrayList<>(sample.preferences());
        unknownMember.removeIf(preference -> preference.memberId().equals("member-e")
                && preference.photoVersionId().equals(SyntheticScenario.VERSION_1));
        unknownMember.add(new Preference("member-z", SyntheticScenario.VERSION_1, 0));
        assertThat(problemCodes(withPreferences(unknownMember)))
                .contains(InputProblemCode.UNKNOWN_PREFERENCE_MEMBER);

        List<Preference> unknownCandidate = new ArrayList<>(sample.preferences());
        unknownCandidate.removeIf(preference -> preference.memberId().equals("member-a")
                && preference.photoVersionId().equals(SyntheticScenario.VERSION_1));
        unknownCandidate.add(new Preference("member-a", SyntheticScenario.uuid(777), 0));
        assertThat(problemCodes(withPreferences(unknownCandidate)))
                .contains(InputProblemCode.UNKNOWN_PREFERENCE_CANDIDATE);
    }

    @Test
    void blankIdentifiersAndNegativeRevisionAreRejected() {
        SelectionInput sample = SyntheticScenario.sample();
        SelectionInput blankEvent = new SelectionInput(" ", sample.purpose(), sample.requestedCount(),
                sample.members(), sample.candidates(), sample.preferences());
        assertThat(problemCodes(blankEvent)).contains(InputProblemCode.BLANK_EVENT_ID);

        List<EventMember> members = new ArrayList<>(sample.members());
        members.add(new EventMember(" "));
        assertThat(problemCodes(withMembers(members))).contains(InputProblemCode.BLANK_MEMBER_ID);

        List<Candidate> candidates = new ArrayList<>(sample.candidates());
        candidates.set(0, SyntheticScenario.candidate(SyntheticScenario.PHOTO_1, SyntheticScenario.VERSION_1, -1,
                new Eligibility(true, Reason.ELIGIBLE)));
        assertThat(problemCodes(withCandidates(candidates, sample.preferences())))
                .contains(InputProblemCode.NEGATIVE_SUBJECT_REVISION);
    }

    @Test
    void eligibilityMustMatchItsReason() {
        SelectionInput sample = SyntheticScenario.sample();
        List<Candidate> candidates = new ArrayList<>(sample.candidates());
        candidates.set(0, SyntheticScenario.candidate(SyntheticScenario.PHOTO_1, SyntheticScenario.VERSION_1, 3,
                new Eligibility(true, Reason.CONSENT_DENIED)));

        assertThat(problemCodes(withCandidates(candidates, sample.preferences())))
                .contains(InputProblemCode.INCONSISTENT_ELIGIBILITY);
    }

    @Test
    void requireValidReportsAllProblemsAtOnce() {
        SelectionInput sample = SyntheticScenario.sample();
        List<EventMember> members = new ArrayList<>(sample.members());
        members.add(new EventMember(SyntheticScenario.MEMBER_IDS.get(0)));
        SelectionInput invalid = new SelectionInput(sample.eventId(), sample.purpose(), 0,
                members, sample.candidates(), sample.preferences());

        assertThatThrownBy(() -> SelectionInputValidator.requireValid(invalid))
                .isInstanceOf(InvalidSelectionInputException.class)
                .satisfies(exception -> assertThat(((InvalidSelectionInputException) exception).problems())
                        .extracting(InputProblem::code)
                        .contains(InputProblemCode.INVALID_REQUESTED_COUNT, InputProblemCode.DUPLICATE_MEMBER_ID));
    }

    private static List<InputProblemCode> problemCodes(SelectionInput input) {
        return SelectionInputValidator.validate(input).stream().map(InputProblem::code).toList();
    }

    private static SelectionInput withRequestedCount(int requestedCount) {
        SelectionInput sample = SyntheticScenario.sample();
        return new SelectionInput(sample.eventId(), sample.purpose(), requestedCount,
                sample.members(), sample.candidates(), sample.preferences());
    }

    private static SelectionInput withMembers(List<EventMember> members) {
        SelectionInput sample = SyntheticScenario.sample();
        return new SelectionInput(sample.eventId(), sample.purpose(), sample.requestedCount(),
                members, sample.candidates(), sample.preferences());
    }

    private static SelectionInput withPreferences(List<Preference> preferences) {
        SelectionInput sample = SyntheticScenario.sample();
        return new SelectionInput(sample.eventId(), sample.purpose(), sample.requestedCount(),
                sample.members(), sample.candidates(), preferences);
    }

    private static SelectionInput withCandidates(List<Candidate> candidates, List<Preference> preferences) {
        SelectionInput sample = SyntheticScenario.sample();
        return new SelectionInput(sample.eventId(), sample.purpose(), sample.requestedCount(),
                sample.members(), candidates, preferences);
    }
}
