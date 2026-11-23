package com.groupphoto.consensus.recommendation;

import com.groupphoto.consensus.authorization.Publications;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 选片输入的结构校验。只检查输入是否自洽，不判断算法是否更好，也不重新计算授权。
 *
 * <p>用法：应用用例在把输入交给算法前调用 {@link #requireValid(SelectionInput)}；算法内部可调用
 * {@link #validate(SelectionInput)} 取得全部问题而不抛出。
 */
public final class SelectionInputValidator {
    public static final int MIN_REQUESTED_COUNT = 1;
    public static final int MAX_REQUESTED_COUNT = 100;
    public static final int MIN_SCORE = -1;
    public static final int MAX_SCORE = 1;

    private static final char KEY_SEPARATOR = '\u0000';

    private SelectionInputValidator() {}

    public static List<InputProblem> validate(SelectionInput input) {
        Objects.requireNonNull(input, "input");
        List<InputProblem> problems = new ArrayList<>();

        if (input.eventId().isBlank()) {
            problems.add(new InputProblem(InputProblemCode.BLANK_EVENT_ID, "eventId"));
        }
        if (input.requestedCount() < MIN_REQUESTED_COUNT || input.requestedCount() > MAX_REQUESTED_COUNT) {
            problems.add(new InputProblem(InputProblemCode.INVALID_REQUESTED_COUNT,
                    "requestedCount=" + input.requestedCount()));
        }

        Set<String> memberIds = new LinkedHashSet<>();
        for (int index = 0; index < input.members().size(); index++) {
            String memberId = input.members().get(index).memberId();
            if (memberId.isBlank()) {
                problems.add(new InputProblem(InputProblemCode.BLANK_MEMBER_ID, "members[" + index + "]"));
            } else if (!memberIds.add(memberId)) {
                problems.add(new InputProblem(InputProblemCode.DUPLICATE_MEMBER_ID, memberId));
            }
        }

        Set<String> photoIds = new HashSet<>();
        Set<String> photoVersionIds = new LinkedHashSet<>();
        for (int index = 0; index < input.candidates().size(); index++) {
            SelectionInput.Candidate candidate = input.candidates().get(index);
            if (candidate.photoId().isBlank()) {
                problems.add(new InputProblem(InputProblemCode.BLANK_PHOTO_ID, "candidates[" + index + "]"));
            } else if (!photoIds.add(candidate.photoId())) {
                problems.add(new InputProblem(InputProblemCode.DUPLICATE_PHOTO_ID, candidate.photoId()));
            }
            if (candidate.photoVersionId().isBlank()) {
                problems.add(new InputProblem(InputProblemCode.BLANK_PHOTO_VERSION_ID,
                        "candidates[" + index + "]"));
            } else if (!photoVersionIds.add(candidate.photoVersionId())) {
                problems.add(new InputProblem(InputProblemCode.DUPLICATE_PHOTO_VERSION_ID,
                        candidate.photoVersionId()));
            }
            if (candidate.subjectRevision() < 0) {
                problems.add(new InputProblem(InputProblemCode.NEGATIVE_SUBJECT_REVISION,
                        candidate.photoVersionId()));
            }
            Publications.Eligibility authorization = candidate.authorization();
            if (authorization.reason() == null) {
                problems.add(new InputProblem(InputProblemCode.MISSING_ELIGIBILITY_REASON,
                        candidate.photoVersionId()));
            } else if (authorization.eligible() != (authorization.reason() == Publications.Reason.ELIGIBLE)) {
                problems.add(new InputProblem(InputProblemCode.INCONSISTENT_ELIGIBILITY,
                        candidate.photoVersionId()));
            }
        }

        Set<String> preferenceKeys = new HashSet<>();
        for (int index = 0; index < input.preferences().size(); index++) {
            SelectionInput.Preference preference = input.preferences().get(index);
            String target = preference.memberId() + "/" + preference.photoVersionId();
            boolean unknownMember = preference.memberId().isBlank() || !memberIds.contains(preference.memberId());
            boolean unknownVersion = preference.photoVersionId().isBlank()
                    || !photoVersionIds.contains(preference.photoVersionId());
            if (preference.memberId().isBlank()) {
                problems.add(new InputProblem(InputProblemCode.BLANK_PREFERENCE_MEMBER, "preferences[" + index + "]"));
            } else if (unknownMember) {
                problems.add(new InputProblem(InputProblemCode.UNKNOWN_PREFERENCE_MEMBER, target));
            }
            if (preference.photoVersionId().isBlank()) {
                problems.add(new InputProblem(InputProblemCode.BLANK_PREFERENCE_VERSION,
                        "preferences[" + index + "]"));
            } else if (unknownVersion) {
                problems.add(new InputProblem(InputProblemCode.UNKNOWN_PREFERENCE_CANDIDATE, target));
            }
            if (preference.score() != null
                    && (preference.score() < MIN_SCORE || preference.score() > MAX_SCORE)) {
                problems.add(new InputProblem(InputProblemCode.INVALID_SCORE, target));
            }
            if (!preferenceKeys.add(preference.memberId() + KEY_SEPARATOR + preference.photoVersionId())) {
                problems.add(new InputProblem(InputProblemCode.DUPLICATE_PREFERENCE, target));
            }
        }

        // 每位成员对每个候选版本都必须有一条记录；未评分用 score = null 表达，不能靠缺省。
        for (String memberId : memberIds) {
            for (String photoVersionId : photoVersionIds) {
                if (!preferenceKeys.contains(memberId + KEY_SEPARATOR + photoVersionId)) {
                    problems.add(new InputProblem(InputProblemCode.MISSING_PREFERENCE,
                            memberId + "/" + photoVersionId));
                }
            }
        }

        return List.copyOf(problems);
    }

    /** 校验并在有问题时抛出，异常携带全部问题，便于接口层转成 400 响应。 */
    public static void requireValid(SelectionInput input) {
        List<InputProblem> problems = validate(input);
        if (!problems.isEmpty()) {
            throw new InvalidSelectionInputException(problems);
        }
    }

    public enum InputProblemCode {
        BLANK_EVENT_ID,
        INVALID_REQUESTED_COUNT,
        BLANK_MEMBER_ID,
        DUPLICATE_MEMBER_ID,
        BLANK_PHOTO_ID,
        DUPLICATE_PHOTO_ID,
        BLANK_PHOTO_VERSION_ID,
        DUPLICATE_PHOTO_VERSION_ID,
        NEGATIVE_SUBJECT_REVISION,
        MISSING_ELIGIBILITY_REASON,
        INCONSISTENT_ELIGIBILITY,
        BLANK_PREFERENCE_MEMBER,
        BLANK_PREFERENCE_VERSION,
        UNKNOWN_PREFERENCE_MEMBER,
        UNKNOWN_PREFERENCE_CANDIDATE,
        INVALID_SCORE,
        DUPLICATE_PREFERENCE,
        MISSING_PREFERENCE
    }

    /**
     * 一条输入问题。{@code target} 指向具体成员、版本或字段，便于日志定位。
     */
    public record InputProblem(InputProblemCode code, String target) {
        public InputProblem {
            if (code == null || target == null) {
                throw new NullPointerException("code and target are required");
            }
        }
    }

    /** 校验失败时携带全部问题，避免逐条试错。 */
    public static final class InvalidSelectionInputException extends IllegalArgumentException {
        private final List<InputProblem> problems;

        public InvalidSelectionInputException(List<InputProblem> problems) {
            super("Invalid selection input: " + problems);
            this.problems = List.copyOf(problems);
        }

        public List<InputProblem> problems() {
            return problems;
        }
    }
}
