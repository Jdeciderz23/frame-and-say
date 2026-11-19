package com.groupphoto.consensus.membership;

import java.util.List;
import java.util.Optional;

import com.groupphoto.consensus.identity.BootstrapActors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventMembership {
    private static final String ORGANIZER = "ORGANIZER";
    private static final String PARTICIPANT = "PARTICIPANT";
    private final JdbcTemplate jdbc;
    private final BootstrapActors actors;

    public EventMembership(JdbcTemplate jdbc, BootstrapActors actors) {
        this.jdbc = jdbc;
        this.actors = actors;
    }

    public boolean isMember(String eventId, String actorId) {
        return role(eventId, actorId).isPresent();
    }

    public void requireMember(String eventId, String actorId) {
        if (!isMember(eventId, actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Event membership is required");
        }
    }

    public void requireOrganizer(String eventId, String actorId) {
        if (role(eventId, actorId).filter(ORGANIZER::equals).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Event organizer permission is required");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void addOrganizer(String eventId, String actorId) {
        insert(eventId, actorId, ORGANIZER);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockEvent(String eventId) {
        List<String> found = jdbc.queryForList("SELECT id FROM events WHERE id = ? FOR UPDATE", String.class, eventId);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
    }

    public List<Member> list(String eventId, String actorId) {
        requireMember(eventId, actorId);
        return jdbc.query("""
                SELECT user_id, role FROM event_members WHERE event_id = ?
                ORDER BY role, user_id
                """, (row, index) -> {
            String userId = row.getString("user_id");
            return new Member(userId, userId, row.getString("role"));
        }, eventId);
    }

    public void addParticipant(String eventId, String actorId, String targetId) {
        requireOrganizer(eventId, actorId);
        if (!actors.isProvisioned(targetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        try {
            insert(eventId, targetId, PARTICIPANT);
        } catch (DuplicateKeyException ignored) {
            // Repeated adds are idempotent and never change a member's role.
        }
    }

    private Optional<String> role(String eventId, String actorId) {
        return jdbc.queryForList("""
                SELECT role FROM event_members WHERE event_id = ? AND user_id = ?
                """, String.class, eventId, actorId).stream().findFirst();
    }

    private void insert(String eventId, String userId, String role) {
        jdbc.update("INSERT INTO event_members (event_id, user_id, role) VALUES (?, ?, ?)", eventId, userId, role);
    }

    public record Member(String userId, String displayName, String role) {}
}
