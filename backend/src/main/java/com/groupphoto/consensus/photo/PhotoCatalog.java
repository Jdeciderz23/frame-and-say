package com.groupphoto.consensus.photo;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class PhotoCatalog {
    private static final String VERSION_COLUMNS = """
            SELECT photo_id, id AS photo_version_id, event_id, version_number,
                   subject_revision, roster_confirmed
            FROM photo_versions
            """;

    public record Version(String photoId, String photoVersionId, String eventId, int versionNumber,
                          long subjectRevision, boolean rosterConfirmed) {}

    record PhotoOwner(String uploaderId) {}

    private final JdbcClient jdbc;

    PhotoCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    PhotoOwner requirePhotoOwner(String eventId, String photoId) {
        return jdbc.sql("SELECT uploader_id FROM photos WHERE event_id = :eventId AND id = :photoId")
                .param("eventId", eventId).param("photoId", photoId).query(PhotoOwner.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
    }

    List<Version> versions(String eventId, String photoId) {
        return jdbc.sql(VERSION_COLUMNS + """
                WHERE event_id = :eventId AND photo_id = :photoId
                ORDER BY version_number DESC
                """).param("eventId", eventId).param("photoId", photoId).query(Version.class).list();
    }

    public Version requireVersion(String eventId, String photoId, String photoVersionId) {
        return jdbc.sql(VERSION_COLUMNS + """
                WHERE event_id = :eventId AND photo_id = :photoId AND id = :versionId
                """).param("eventId", eventId).param("photoId", photoId).param("versionId", photoVersionId)
                .query(Version.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo version not found"));
    }

    public Version requireCurrentVersion(String eventId, String photoId, String photoVersionId) {
        Version version = requireVersion(eventId, photoId, photoVersionId);
        int latest = jdbc.sql("""
                SELECT MAX(version_number) FROM photo_versions WHERE event_id = :eventId AND photo_id = :photoId
                """).param("eventId", eventId).param("photoId", photoId).query(Integer.class).single();
        if (latest != version.versionNumber()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Photo version changed; refresh before proceeding");
        }
        return version;
    }

    // Releases at transaction end. New versions must take the same photo lock.
    @Transactional(propagation = Propagation.MANDATORY)
    public Version lockCurrentVersion(String eventId, String photoId, String photoVersionId) {
        jdbc.sql("SELECT id FROM photos WHERE event_id = :eventId AND id = :photoId FOR UPDATE")
                .param("eventId", eventId).param("photoId", photoId)
                .query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
        return requireCurrentVersion(eventId, photoId, photoVersionId);
    }

    public List<String> subjectIds(Version version) {
        return jdbc.sql("""
                SELECT user_id FROM photo_subjects
                WHERE photo_version_id = :versionId AND subject_revision = :revision
                ORDER BY user_id
                """).param("versionId", version.photoVersionId()).param("revision", version.subjectRevision())
                .query(String.class).list();
    }

    List<Version> currentVersions(String eventId) {
        return jdbc.sql("""
                SELECT v.photo_id, v.id AS photo_version_id, v.event_id, v.version_number,
                       v.subject_revision, v.roster_confirmed
                FROM photo_versions v
                WHERE v.event_id = :eventId AND NOT EXISTS (
                    SELECT 1 FROM photo_versions newer
                    WHERE newer.photo_id = v.photo_id AND newer.version_number > v.version_number
                )
                ORDER BY v.photo_id
                """).param("eventId", eventId).query(Version.class).list();
    }
}
