CREATE TABLE events (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    desired_photo_count INTEGER,
    CONSTRAINT ck_event_photo_count CHECK (desired_photo_count BETWEEN 1 AND 100)
);

CREATE TABLE event_members (
    event_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (event_id, user_id),
    CONSTRAINT fk_event_members_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT ck_event_members_role CHECK (role IN ('ORGANIZER', 'PARTICIPANT'))
);

CREATE INDEX idx_event_members_user ON event_members (user_id, event_id);
