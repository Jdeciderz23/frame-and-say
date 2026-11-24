package com.groupphoto.consensus.support;

/** Synthetic credentials shared only by automated tests; never use them in a deployed environment. */
public final class SyntheticActors {
    public static final String ORGANIZER_ID = "organizer";
    public static final String PARTICIPANT_ID = "participant";
    public static final String ORGANIZER_TOKEN = "organizer_integration_test_only_token";
    public static final String PARTICIPANT_TOKEN = "participant_integration_test_only_token";
    public static final String ORGANIZER = "Bearer " + ORGANIZER_TOKEN;
    public static final String PARTICIPANT = "Bearer " + PARTICIPANT_TOKEN;
    public static final String ORGANIZER_PROPERTY = "app.bootstrap.organizer-token=" + ORGANIZER_TOKEN;
    public static final String PARTICIPANT_PROPERTY = "app.bootstrap.participant-token=" + PARTICIPANT_TOKEN;

    private SyntheticActors() {}
}
