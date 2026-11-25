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
        String eventId = UUID.randomUUID().toString();
        String displayName = name.strip();
        if (requestId != null) {
            // The unique actor/key row serializes competing transactions, including an uncommitted first POST.
            jdbc.update("""
                    INSERT INTO event_creation_requests (actor_id, request_key, event_id, name, desired_photo_count)
                    VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE request_key = request_key
                    """, actorId, requestId.toString(), eventId, displayName, desiredPhotoCount);
            Event first = jdbc.queryForObject("""
                    SELECT event_id, name, desired_photo_count FROM event_creation_requests
                    WHERE actor_id = ? AND request_key = ? FOR UPDATE
                    """, (row, index) -> new Event(row.getString(1), row.getString(2), row.getObject(3, Integer.class)),
                    actorId, requestId.toString());
            if (!displayName.equals(first.name()) || !Objects.equals(desiredPhotoCount, first.desiredPhotoCount())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Creation key already used for different input");
            }
            if (!eventId.equals(first.id())) {
                return first;
            }
        }
        jdbc.update("INSERT INTO events (id, name, desired_photo_count) VALUES (?, ?, ?)",
                eventId, displayName, desiredPhotoCount);
        members.addOrganizer(eventId, actorId);
        return new Event(eventId, displayName, desiredPhotoCount);
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
