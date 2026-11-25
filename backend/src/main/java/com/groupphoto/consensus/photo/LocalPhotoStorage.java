package com.groupphoto.consensus.photo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class LocalPhotoStorage implements PhotoStorage {
    private final Path root;

    LocalPhotoStorage(@Value("${app.photo.root:${APP_PHOTO_DIR:./data/photos}}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void save(UUID versionId, byte[] image, byte[] thumbnail) throws IOException {
        Files.createDirectories(root);
        Path directory = directory(versionId);
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(directory);
        }
        try {
            Files.write(directory.resolve("original"), image, StandardOpenOption.CREATE_NEW);
            Files.write(directory.resolve("thumbnail.png"), thumbnail, StandardOpenOption.CREATE_NEW);
        } catch (IOException | RuntimeException e) {
            try {
                delete(versionId);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    @Override
    public byte[] readThumbnail(UUID versionId) throws IOException {
        return Files.readAllBytes(directory(versionId).resolve("thumbnail.png"));
    }

    @Override
    public InputStream openOriginal(UUID versionId) throws IOException {
        return Files.newInputStream(directory(versionId).resolve("original"));
    }

    @Override
    public void delete(UUID versionId) throws IOException {
        Path directory = directory(versionId);
        Files.deleteIfExists(directory.resolve("thumbnail.png"));
        Files.deleteIfExists(directory.resolve("original"));
        Files.deleteIfExists(directory);
    }

    private Path directory(UUID versionId) {
        return root.resolve(versionId.toString());
    }
}
