CREATE TABLE confirmed_selections (
    id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('INTERNAL_SHARE', 'PUBLIC_POST')),
    CONSTRAINT fk_confirmed_selection_event FOREIGN KEY (event_id) REFERENCES events (id)
);

-- Keep reviewed IDs if a photo is later removed. Its absence invalidates the selection.
CREATE TABLE confirmed_selection_items (
    selection_id VARCHAR(36) NOT NULL,
    sort_order INTEGER NOT NULL,
    photo_id VARCHAR(36) NOT NULL,
    photo_version_id VARCHAR(36) NOT NULL,
    subject_revision BIGINT NOT NULL CHECK (subject_revision >= 0),
    PRIMARY KEY (selection_id, sort_order),
    CONSTRAINT uq_confirmed_selection_photo UNIQUE (selection_id, photo_id),
    CONSTRAINT fk_confirmed_selection_item FOREIGN KEY (selection_id) REFERENCES confirmed_selections (id)
);
