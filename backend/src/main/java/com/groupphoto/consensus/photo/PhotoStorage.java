package com.groupphoto.consensus.photo;

import java.io.IOException;
import java.util.UUID;

interface PhotoStorage {
    void save(UUID versionId, byte[] image, byte[] thumbnail) throws IOException;

    byte[] readThumbnail(UUID versionId) throws IOException;

    void delete(UUID versionId) throws IOException;
}
