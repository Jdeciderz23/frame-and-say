CREATE TABLE event_creation_requests (
    actor_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    name VARCHAR(80) NOT NULL,
    desired_photo_count INTEGER,
    PRIMARY KEY (actor_id, request_key)
);
