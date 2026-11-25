-- Older photos have no recorded uploader. Their organizer can still add a version.
ALTER TABLE photos ADD COLUMN uploader_id VARCHAR(64);
