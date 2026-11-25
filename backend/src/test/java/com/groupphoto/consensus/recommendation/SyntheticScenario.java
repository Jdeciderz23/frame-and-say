package com.groupphoto.consensus.recommendation;

import com.groupphoto.consensus.authorization.Publications;
import com.groupphoto.consensus.authorization.Publications.Eligibility;
import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.authorization.Publications.Reason;
import com.groupphoto.consensus.recommendation.SelectionInput.Candidate;
import com.groupphoto.consensus.recommendation.SelectionInput.EventMember;
import com.groupphoto.consensus.recommendation.SelectionInput.Preference;
import java.util.ArrayList;
import java.util.List;

/**
 * 人工模拟数据，只用于测试。不是真实照片、真实用户或真实偏好，也不代表任何真实授权结论。
 *
 * <p>固定 ID 和固定评分保证样例可以重复运行、结果可比。评分取 {@code -1}/{@code 0}/{@code 1}，
 * {@code member-b} 对 {@code VERSION_7} 明确未评分（{@code null}），用来区分“未评分”和“评分为 0”。
 */
final class SyntheticScenario {
    static final String EVENT_ID = uuid(0);
    static final List<String> MEMBER_IDS =
            List.of("member-a", "member-b", "member-c", "member-d", "member-e");

    static final String PHOTO_1 = uuid(101);
    static final String VERSION_1 = uuid(201);
    static final String PHOTO_2 = uuid(102);
    static final String VERSION_2 = uuid(202);
    static final String PHOTO_3 = uuid(103);
    static final String VERSION_3 = uuid(203);
    static final String PHOTO_4_DENIED = uuid(104);
    static final String VERSION_4_DENIED = uuid(204);
    static final String PHOTO_5_PENDING = uuid(105);
    static final String VERSION_5_PENDING = uuid(205);
    static final String PHOTO_6_UNCONFIRMED = uuid(106);
    static final String VERSION_6_UNCONFIRMED = uuid(206);
    static final String PHOTO_7 = uuid(107);
    static final String VERSION_7 = uuid(207);

    static final int REQUESTED_COUNT = 3;

    private SyntheticScenario() {}

    static SelectionInput sample() {
        List<Candidate> candidates = List.of(
                candidate(PHOTO_1, VERSION_1, 3, new Eligibility(true, Reason.ELIGIBLE)),
                candidate(PHOTO_2, VERSION_2, 3, new Eligibility(true, Reason.ELIGIBLE)),
                candidate(PHOTO_3, VERSION_3, 4, new Eligibility(true, Reason.ELIGIBLE)),
                candidate(PHOTO_4_DENIED, VERSION_4_DENIED, 2, new Eligibility(false, Reason.CONSENT_DENIED)),
                candidate(PHOTO_5_PENDING, VERSION_5_PENDING, 2, new Eligibility(false, Reason.CONSENT_PENDING)),
                candidate(PHOTO_6_UNCONFIRMED, VERSION_6_UNCONFIRMED, 1,
                        new Eligibility(false, Reason.ROSTER_UNCONFIRMED)),
                candidate(PHOTO_7, VERSION_7, 5, new Eligibility(true, Reason.ELIGIBLE)));

        List<Preference> preferences = new ArrayList<>();
        addScores(preferences, VERSION_1, 1, 1, 0, -1, 1);
        addScores(preferences, VERSION_2, 0, 1, 1, 0, -1);
        addScores(preferences, VERSION_3, 1, 0, 0, 1, 1);
        addScores(preferences, VERSION_4_DENIED, 1, 1, 1, 1, 1);
        addScores(preferences, VERSION_5_PENDING, 0, 0, 0, 0, 0);
        addScores(preferences, VERSION_6_UNCONFIRMED, -1, 0, 1, 0, 0);
        addScores(preferences, VERSION_7, 1, null, 1, 0, 1);

        return new SelectionInput(EVENT_ID, Purpose.INTERNAL_SHARE, REQUESTED_COUNT,
                members(), candidates, preferences);
    }

    static List<EventMember> members() {
        return MEMBER_IDS.stream().map(EventMember::new).toList();
    }

    static Candidate candidate(String photoId, String photoVersionId, long revision, Eligibility authorization) {
        return new Candidate(photoId, photoVersionId, revision, authorization);
    }

    static String uuid(int suffix) {
        return String.format("00000000-0000-4000-8000-%012d", suffix);
    }

    private static void addScores(List<Preference> target, String versionId, Integer... scores) {
        for (int index = 0; index < MEMBER_IDS.size(); index++) {
            target.add(new Preference(MEMBER_IDS.get(index), versionId, scores[index]));
        }
    }
}
