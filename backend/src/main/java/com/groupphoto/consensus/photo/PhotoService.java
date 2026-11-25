package com.groupphoto.consensus.photo;

import com.groupphoto.consensus.membership.EventMembership;
import java.io.IOException;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class PhotoService {
    private static final int MAX_EVENT_PHOTOS = 100;

    private final JdbcClient jdbc;
    private final EventMembership membership;
    private final PhotoCatalog catalog;
    private final PhotoStorage storage;
    private final TransactionTemplate transactions;
    private final PhotoImageProcessor images = new PhotoImageProcessor();

    record VersionHistory(boolean canCreateVersion, List<PhotoCatalog.Version> versions) {}

    PhotoService(JdbcClient jdbc, EventMembership membership, PhotoCatalog catalog,
                 PhotoStorage storage, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.membership = membership;
        this.catalog = catalog;
        this.storage = storage;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    PhotoCatalog.Version upload(String eventId, String actorId, MultipartFile file) {
        membership.requireMember(eventId, actorId);
        var processed = images.process(file);
        String photoId = UUID.randomUUID().toString();
        return storeVersion(processed, versionId -> {
            // Serialize event quota checks and inserts across concurrent uploads.
            membership.lockEvent(eventId);
            membership.requireMember(eventId, actorId);
            long count = jdbc.sql("SELECT COUNT(*) FROM photos WHERE event_id = :eventId AND deleted_at IS NULL")
                    .param("eventId", eventId).query(Long.class).single();
            if (count >= MAX_EVENT_PHOTOS) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The event already has 100 photos");
            }
            jdbc.sql("INSERT INTO photos (id, event_id, uploader_id) VALUES (:photoId, :eventId, :actorId)")
                    .param("photoId", photoId).param("eventId", eventId).param("actorId", actorId).update();
            return insertVersion(eventId, photoId, versionId, 1, processed.mediaType());
        });
    }

    VersionHistory history(String eventId, String photoId, String actorId) {
        membership.requireMember(eventId, actorId);
        return new VersionHistory(canCreateVersion(eventId, photoId, actorId), catalog.versions(eventId, photoId));
    }

    PhotoCatalog.Version uploadVersion(String eventId, String photoId, String baseVersionId, String actorId,
                                       MultipartFile file) {
        requireVersionWriter(eventId, photoId, actorId);
        catalog.requireCurrentVersion(eventId, photoId, baseVersionId);
        var processed = images.process(file);
        return storeVersion(processed, versionId -> {
            var base = catalog.lockCurrentVersion(eventId, photoId, baseVersionId);
            requireVersionWriter(eventId, photoId, actorId);
            // V2 starts with the schema's unconfirmed roster and has no subjects, preferences or consents.
            return insertVersion(eventId, photoId, versionId, base.versionNumber() + 1, processed.mediaType());
        });
    }

    private boolean canCreateVersion(String eventId, String photoId, String actorId) {
        var owner = catalog.requirePhotoOwner(eventId, photoId);
        return actorId.equals(owner.uploaderId()) || membership.isOrganizer(eventId, actorId);
    }

    private void requireVersionWriter(String eventId, String photoId, String actorId) {
        membership.requireMember(eventId, actorId);
        if (!canCreateVersion(eventId, photoId, actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Uploader or event organizer permission is required");
        }
    }

    private PhotoCatalog.Version insertVersion(String eventId, String photoId, String versionId, int number,
                                               String mediaType) {
        jdbc.sql("""
                INSERT INTO photo_versions (id, photo_id, event_id, version_number, media_type)
                VALUES (:versionId, :photoId, :eventId, :number, :mediaType)
                """).param("versionId", versionId).param("photoId", photoId).param("eventId", eventId)
                .param("number", number).param("mediaType", mediaType).update();
        return catalog.requireVersion(eventId, photoId, versionId);
    }

    private PhotoCatalog.Version storeVersion(PhotoImageProcessor.Processed processed,
                                              Function<String, PhotoCatalog.Version> insert) {
        UUID storageKey = UUID.randomUUID();
        try {
            storage.save(storageKey, processed.original(), processed.thumbnail());
        } catch (IOException error) {
            throw storageUnavailable(error);
        }
        try {
            return transactions.execute(status -> insert.apply(storageKey.toString()));
        } catch (RuntimeException error) {
            try {
                storage.delete(storageKey);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    List<PhotoCatalog.Version> list(String eventId, String actorId) {
        membership.requireMember(eventId, actorId);
        return catalog.currentVersions(eventId);
    }

    byte[] thumbnail(String eventId, String photoId, String versionId, String actorId) {
        membership.requireMember(eventId, actorId);
        PhotoCatalog.Version version = catalog.requireVersion(eventId, photoId, versionId);
        try {
            return storage.readThumbnail(UUID.fromString(version.photoVersionId()));
        } catch (IOException error) {
            throw storageUnavailable(error);
        }
    }

    PhotoCatalog.Version requireRoster(String eventId, String photoId, String versionId, String actorId) {
        membership.requireOrganizer(eventId, actorId);
        return catalog.requireCurrentVersion(eventId, photoId, versionId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PhotoCatalog.Version setSubjects(String eventId, String photoId, String versionId, String actorId,
                                            long expectedRevision, List<String> userIds) {
        membership.requireOrganizer(eventId, actorId);
        PhotoCatalog.Version version = catalog.lockCurrentVersion(eventId, photoId, versionId);
        if (version.subjectRevision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The subject roster changed; refresh before confirming");
        }
        var orderedUsers = new TreeSet<>(userIds);
        if (orderedUsers.size() != userIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The subject roster contains duplicate members");
        }
        for (String userId : orderedUsers) {
            if (!membership.isMember(eventId, userId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "All subjects must belong to this event");
            }
        }
        if (version.rosterConfirmed() && catalog.subjectIds(version).equals(List.copyOf(orderedUsers))) {
            return version;
        }

        long next = expectedRevision + 1;
        for (String userId : orderedUsers) {
            jdbc.sql("""
                    INSERT INTO photo_subjects (photo_version_id, event_id, subject_revision, user_id)
                    VALUES (:versionId, :eventId, :revision, :userId)
                    """).param("versionId", versionId).param("eventId", eventId)
                    .param("revision", next).param("userId", userId).update();
        }
        jdbc.sql("UPDATE photo_versions SET subject_revision = :revision, roster_confirmed = TRUE WHERE id = :versionId")
                .param("versionId", versionId).param("revision", next).update();
        return catalog.requireVersion(eventId, photoId, versionId);
    }

    private static ResponseStatusException storageUnavailable(IOException error) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is unavailable", error);
    }
}
