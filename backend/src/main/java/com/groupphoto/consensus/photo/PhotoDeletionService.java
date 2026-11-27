package com.groupphoto.consensus.photo;

import com.groupphoto.consensus.membership.EventMembership;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PhotoDeletionService {
    private static final String REQUEST_COLUMNS = """
            SELECT photo_id, event_id, requested_by, requested_at, processed_by, processed_at
            FROM photo_deletion_requests
            """;

    enum Status { PENDING, APPROVED }

    record DeletionRequest(String photoId, String eventId, Status status, String requestedBy, Instant requestedAt,
                           String processedBy, Instant processedAt) {}

    private final JdbcClient jdbc;
    private final EventMembership membership;
    private final PhotoCatalog photos;

    PhotoDeletionService(JdbcClient jdbc, EventMembership membership, PhotoCatalog photos) {
        this.jdbc = jdbc;
        this.membership = membership;
        this.photos = photos;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeletionRequest request(String eventId, String photoId, String actorId) {
        membership.requireMember(eventId, actorId);
        var owner = photos.lockPhoto(eventId, photoId);
        if (!actorId.equals(owner.uploaderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the original uploader can request deletion");
        }
        return find(eventId, photoId).orElseGet(() -> {
            jdbc.sql("""
                    INSERT INTO photo_deletion_requests (photo_id, event_id, requested_by, requested_at)
                    VALUES (?, ?, ?, ?)
                    """).params(photoId, eventId, actorId, Timestamp.from(Instant.now())).update();
            return requireRequest(eventId, photoId);
        });
    }

    DeletionRequest read(String eventId, String photoId, String actorId) {
        membership.requireMember(eventId, actorId);
        return find(eventId, photoId)
                .filter(item -> actorId.equals(item.requestedBy()) || membership.isOrganizer(eventId, actorId))
                .orElseThrow(PhotoDeletionService::notFound);
    }

    List<DeletionRequest> list(String eventId, String actorId, int limit, int offset) {
        membership.requireOrganizer(eventId, actorId);
        return jdbc.sql(REQUEST_COLUMNS + """
                WHERE event_id = ? ORDER BY requested_at DESC, photo_id
                LIMIT ? OFFSET ?
                """).params(eventId, limit, offset).query(PhotoDeletionService::map).list();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeletionRequest approve(String eventId, String photoId, String actorId) {
        membership.requireOrganizer(eventId, actorId);
        photos.lockPhoto(eventId, photoId);
        var request = requireRequest(eventId, photoId);
        if (request.status() == Status.APPROVED) return request;

        Timestamp timestamp = Timestamp.from(Instant.now());
        jdbc.sql("UPDATE photos SET deleted_at = ? WHERE event_id = ? AND id = ?")
                .params(timestamp, eventId, photoId).update();
        jdbc.sql("UPDATE photo_deletion_requests SET processed_by = ?, processed_at = ? WHERE event_id = ? AND photo_id = ?")
                .params(actorId, timestamp, eventId, photoId).update();
        return requireRequest(eventId, photoId);
    }

    private Optional<DeletionRequest> find(String eventId, String photoId) {
        return jdbc.sql(REQUEST_COLUMNS + "WHERE event_id = ? AND photo_id = ?")
                .params(eventId, photoId).query(PhotoDeletionService::map).optional();
    }

    private DeletionRequest requireRequest(String eventId, String photoId) {
        return find(eventId, photoId).orElseThrow(PhotoDeletionService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo deletion request not found");
    }

    private static DeletionRequest map(ResultSet row, int index) throws SQLException {
        Timestamp processedAt = row.getTimestamp("processed_at");
        return new DeletionRequest(row.getString("photo_id"), row.getString("event_id"),
                processedAt == null ? Status.PENDING : Status.APPROVED, row.getString("requested_by"),
                row.getTimestamp("requested_at").toInstant(), row.getString("processed_by"),
                processedAt == null ? null : processedAt.toInstant());
    }
}
