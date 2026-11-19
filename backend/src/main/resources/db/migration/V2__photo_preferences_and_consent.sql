CREATE TABLE photos (
    id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    CONSTRAINT uq_photo_event UNIQUE (id, event_id),
    CONSTRAINT fk_photo_event FOREIGN KEY (event_id) REFERENCES events (id)
);

CREATE TABLE photo_versions (
    id VARCHAR(36) PRIMARY KEY,
    photo_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    media_type VARCHAR(32) NOT NULL,
    subject_revision BIGINT NOT NULL DEFAULT 0,
    roster_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_photo_version_number UNIQUE (photo_id, version_number),
    CONSTRAINT uq_photo_version_event UNIQUE (id, event_id),
    CONSTRAINT fk_version_photo_event FOREIGN KEY (photo_id, event_id) REFERENCES photos (id, event_id)
);

CREATE TABLE photo_subjects (
    photo_version_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    subject_revision BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (photo_version_id, subject_revision, user_id),
    CONSTRAINT fk_subject_version_event FOREIGN KEY (photo_version_id, event_id) REFERENCES photo_versions (id, event_id),
    CONSTRAINT fk_subject_member FOREIGN KEY (event_id, user_id) REFERENCES event_members (event_id, user_id)
);

CREATE TABLE photo_preferences (
    photo_version_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    preference_value SMALLINT NOT NULL CHECK (preference_value BETWEEN -1 AND 1),
    PRIMARY KEY (photo_version_id, user_id),
    CONSTRAINT fk_preference_version_event FOREIGN KEY (photo_version_id, event_id) REFERENCES photo_versions (id, event_id),
    CONSTRAINT fk_preference_member FOREIGN KEY (event_id, user_id) REFERENCES event_members (event_id, user_id)
);

CREATE TABLE photo_consents (
    photo_version_id VARCHAR(36) NOT NULL,
    subject_revision BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('INTERNAL_SHARE', 'PUBLIC_POST')),
    decision VARCHAR(8) NOT NULL CHECK (decision IN ('ALLOW', 'DENY')),
    PRIMARY KEY (photo_version_id, subject_revision, user_id, purpose),
    CONSTRAINT fk_consent_subject FOREIGN KEY (photo_version_id, subject_revision, user_id)
        REFERENCES photo_subjects (photo_version_id, subject_revision, user_id)
);
