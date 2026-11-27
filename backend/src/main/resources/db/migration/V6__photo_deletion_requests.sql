ALTER TABLE photos ADD COLUMN deleted_at TIMESTAMP(6);

CREATE TABLE photo_deletion_requests (
    photo_id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    processed_by VARCHAR(64),
    processed_at TIMESTAMP(6),
    CONSTRAINT fk_photo_deletion_scope FOREIGN KEY (photo_id, event_id) REFERENCES photos (id, event_id),
    CONSTRAINT ck_photo_deletion_processed CHECK (
        (processed_by IS NULL AND processed_at IS NULL)
        OR (processed_by IS NOT NULL AND processed_at IS NOT NULL)
    )
);

CREATE INDEX idx_photo_deletion_requests_event ON photo_deletion_requests (event_id, requested_at, photo_id);
