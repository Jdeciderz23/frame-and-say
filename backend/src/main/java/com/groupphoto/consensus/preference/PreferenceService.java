package com.groupphoto.consensus.preference;

import com.groupphoto.consensus.membership.EventMembership;
import com.groupphoto.consensus.photo.PhotoCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PreferenceService {
    private final JdbcClient jdbc;
    private final EventMembership membership;
    private final PhotoCatalog photos;

    PreferenceService(JdbcClient jdbc, EventMembership membership, PhotoCatalog photos) {
        this.jdbc = jdbc;
        this.membership = membership;
        this.photos = photos;
    }

    Integer read(String eventId, String photoId, String versionId, String actorId) {
        membership.requireMember(eventId, actorId);
        photos.requireCurrentVersion(eventId, photoId, versionId);
        return value(versionId, actorId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void write(String eventId, String photoId, String versionId, String actorId, int value) {
        membership.requireMember(eventId, actorId);
        photos.lockCurrentVersion(eventId, photoId, versionId);
        if (value(versionId, actorId) == null) {
            jdbc.sql("""
                    INSERT INTO photo_preferences (photo_version_id, event_id, user_id, preference_value)
                    VALUES (:versionId, :eventId, :actorId, :value)
                    """).param("versionId", versionId).param("eventId", eventId).param("actorId", actorId)
                    .param("value", value).update();
        } else {
            jdbc.sql("""
                    UPDATE photo_preferences SET preference_value = :value
                    WHERE photo_version_id = :versionId AND user_id = :actorId
                    """).param("versionId", versionId).param("actorId", actorId).param("value", value).update();
        }
    }

    private Integer value(String versionId, String actorId) {
        return jdbc.sql("""
                SELECT preference_value FROM photo_preferences
                WHERE photo_version_id = :versionId AND user_id = :actorId
                """).param("versionId", versionId).param("actorId", actorId)
                .query(Integer.class).optional().orElse(null);
    }
}
