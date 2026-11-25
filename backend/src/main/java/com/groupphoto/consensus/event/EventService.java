package com.groupphoto.consensus.event;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.groupphoto.consensus.membership.EventMembership;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EventService {
    private final JdbcTemplate jdbc;
    private final EventMembership members;

    EventService(JdbcTemplate jdbc, EventMembership members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    @Transactional
    public Event create(String actorId, String name, Integer desiredPhotoCount, UUID requestId) {
        String newId = UUID.randomUUID().toString();
        String displayName = name.strip();
        String eventId = requestId == null ? newId
                : claimCreation(actorId, requestId, newId, displayName, desiredPhotoCount);
        if (eventId.equals(newId)) {
            jdbc.update("INSERT INTO events (id, name, desired_photo_count) VALUES (?, ?, ?)",
                    eventId, displayName, desiredPhotoCount);
            members.addOrganizer(eventId, actorId);
        }
        return new Event(eventId, displayName, desiredPhotoCount);
    }

    private String claimCreation(String actorId, UUID requestId, String newId, String name, Integer target) {
        // The unique actor/key row serializes retries, even while the first creation is uncommitted.
        jdbc.update("""
                INSERT INTO event_creation_requests (actor_id, request_key, event_id, name, desired_photo_count)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE request_key = request_key
                """, actorId, requestId.toString(), newId, name, target);
        return jdbc.queryForObject("""
                SELECT event_id, name, desired_photo_count FROM event_creation_requests
                WHERE actor_id = ? AND request_key = ? FOR UPDATE
                """, (row, index) -> {
                    if (!name.equals(row.getString("name"))
                            || !Objects.equals(target, row.getObject("desired_photo_count", Integer.class))) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Creation key already used for different input");
                    }
                    return row.getString("event_id");
                }, actorId, requestId.toString());
    }

    public List<Event> list(String actorId) {
        return jdbc.query("""
                SELECT e.id, e.name, e.desired_photo_count FROM events e
                JOIN event_members m ON e.id = m.event_id
                WHERE m.user_id = ? ORDER BY e.id
                """, (row, index) -> new Event(row.getString("id"), row.getString("name"),
                row.getObject("desired_photo_count", Integer.class)), actorId);
    }

    public record Event(String id, String name, Integer desiredPhotoCount) {}
}
