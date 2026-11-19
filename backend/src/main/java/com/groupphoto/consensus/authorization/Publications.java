package com.groupphoto.consensus.authorization;

import java.util.List;

public final class Publications {
    private Publications() {}

    public enum Purpose { INTERNAL_SHARE, PUBLIC_POST }

    public enum Decision { ALLOW, DENY }

    public enum Status { PENDING, ALLOW, DENY }

    public enum Reason { ELIGIBLE, ROSTER_UNCONFIRMED, CONSENT_DENIED, CONSENT_PENDING }

    public record Eligibility(boolean eligible, Reason reason) {}

    public static Eligibility evaluate(boolean rosterConfirmed, List<Status> requiredConsents) {
        if (!rosterConfirmed) {
            return new Eligibility(false, Reason.ROSTER_UNCONFIRMED);
        }
        if (requiredConsents.contains(Status.DENY)) {
            return new Eligibility(false, Reason.CONSENT_DENIED);
        }
        if (requiredConsents.stream().anyMatch(status -> status != Status.ALLOW)) {
            return new Eligibility(false, Reason.CONSENT_PENDING);
        }
        return new Eligibility(true, Reason.ELIGIBLE);
    }
}
