package com.groupphoto.consensus.event;

import java.util.List;
import java.util.UUID;

import com.groupphoto.consensus.membership.EventMembership;
import org.springframework.jdbc.core.JdbcTemplate;
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
    public Event create(String actorId, String name, Integer desiredPhotoCount) {
        String eventId = UUID.randomUUID().toString();
        String displayName = name.strip();
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
