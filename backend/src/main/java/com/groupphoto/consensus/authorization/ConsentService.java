package com.groupphoto.consensus.authorization;

import com.groupphoto.consensus.authorization.Publications.Decision;
import com.groupphoto.consensus.authorization.Publications.Eligibility;
import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.authorization.Publications.Status;
import com.groupphoto.consensus.membership.EventMembership;
import com.groupphoto.consensus.photo.PhotoCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConsentService {
    private final JdbcClient jdbc;
    private final EventMembership membership;
    private final PhotoCatalog photos;

    ConsentService(JdbcClient jdbc, EventMembership membership, PhotoCatalog photos) {
        this.jdbc = jdbc;
        this.membership = membership;
        this.photos = photos;
    }

    record MyConsent(String photoVersionId, long subjectRevision, Purpose purpose, Status decision, boolean canDecide) {}

    MyConsent read(String eventId, String photoId, String versionId, String actorId, Purpose purpose) {
        membership.requireMember(eventId, actorId);
        var version = photos.requireCurrentVersion(eventId, photoId, versionId);
        boolean subject = version.rosterConfirmed() && photos.subjectIds(version).contains(actorId);
        Status decision = subject ? status(version, actorId, purpose) : Status.PENDING;
        return new MyConsent(versionId, version.subjectRevision(), purpose, decision, subject);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void write(String eventId, String photoId, String versionId, String actorId, long expectedRevision,
                      Purpose purpose, Decision decision) {
        membership.requireMember(eventId, actorId);
        var version = photos.lockCurrentVersion(eventId, photoId, versionId);
        if (!version.rosterConfirmed() || version.subjectRevision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The subject roster is unconfirmed or has changed; refresh before confirming");
        }
        if (!photos.subjectIds(version).contains(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a listed subject may decide");
        }
        String statement = status(version, actorId, purpose) == Status.PENDING
                ? """
                INSERT INTO photo_consents (photo_version_id, subject_revision, user_id, purpose, decision)
                VALUES (:versionId, :revision, :actorId, :purpose, :decision)
                """
                : """
                UPDATE photo_consents SET decision = :decision
                WHERE photo_version_id = :versionId AND subject_revision = :revision
                    AND user_id = :actorId AND purpose = :purpose
                """;
        jdbc.sql(statement).param("versionId", versionId).param("revision", expectedRevision)
                .param("actorId", actorId).param("purpose", purpose.name()).param("decision", decision.name())
                .update();
    }

    // This input is evaluated against the latest revision for the requested purpose.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Eligibility evaluateForRecommendation(String eventId, String photoId, String versionId,
                                                 String actorId, Purpose purpose) {
        membership.requireOrganizer(eventId, actorId);
        var version = photos.lockCurrentVersion(eventId, photoId, versionId);
        return evaluate(version, purpose);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PhotoCatalog.Version requireEligibleForDelivery(String eventId, String photoId, String versionId,
                                                           String actorId, Purpose purpose, long reviewedRevision) {
        membership.requireOrganizer(eventId, actorId);
        var version = photos.lockCurrentVersion(eventId, photoId, versionId);
        if (version.subjectRevision() != reviewedRevision || !evaluate(version, purpose).eligible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The confirmed selection changed; review it again");
        }
        return version;
    }

    private Eligibility evaluate(PhotoCatalog.Version version, Purpose purpose) {
        var required = jdbc.sql("""
                SELECT c.decision FROM photo_subjects s
                LEFT JOIN photo_consents c ON c.photo_version_id = s.photo_version_id
                    AND c.subject_revision = s.subject_revision AND c.user_id = s.user_id AND c.purpose = :purpose
                WHERE s.photo_version_id = :versionId AND s.subject_revision = :revision
                """).param("purpose", purpose.name()).param("versionId", version.photoVersionId())
                .param("revision", version.subjectRevision())
                .query((result, row) -> {
                    String decision = result.getString("decision");
                    return decision == null ? Status.PENDING : Status.valueOf(decision);
                }).list();
        return Publications.evaluate(version.rosterConfirmed(), required);
    }

    private Status status(PhotoCatalog.Version version, String actorId, Purpose purpose) {
        return jdbc.sql("""
                SELECT decision FROM photo_consents
                WHERE photo_version_id = :versionId AND subject_revision = :revision
                    AND user_id = :actorId AND purpose = :purpose
                """).param("versionId", version.photoVersionId()).param("revision", version.subjectRevision())
                .param("actorId", actorId).param("purpose", purpose.name())
                .query(String.class).optional().map(Status::valueOf).orElse(Status.PENDING);
    }
}
