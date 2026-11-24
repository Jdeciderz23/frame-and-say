package com.groupphoto.consensus.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPhotoStorageTest {
    @TempDir
    Path temporary;

    @Test
    void storesOnlyUnderVersionKeysAndDeletesOnlyTheRequestedVersion() throws Exception {
        Path root = temporary.resolve("private-photos");
        var storage = new LocalPhotoStorage(root.toString());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        byte[] original = SyntheticImages.image("jpeg", 32, 16);
        byte[] thumbnail = SyntheticImages.image("png", 32, 16);
        storage.save(first, original, thumbnail);
        storage.save(second, original, thumbnail);

        assertThat(Files.readAllBytes(root.resolve(first.toString()).resolve("original"))).isEqualTo(original);
        assertThat(storage.readThumbnail(first)).isEqualTo(thumbnail);
        try (var files = Files.list(root.resolve(first.toString()))) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder("original", "thumbnail.png");
        }
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(root.resolve(first.toString())))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }

        storage.delete(first);
        storage.delete(first); // Repeated cleanup must be safe.
        assertThat(root.resolve(first.toString())).doesNotExist();
        assertThatIOException().isThrownBy(() -> storage.readThumbnail(first));
        assertThat(storage.readThumbnail(second)).isEqualTo(thumbnail);
        storage.delete(second);
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void refusesToOverwriteAnExistingVersion() throws Exception {
        var storage = new LocalPhotoStorage(temporary.toString());
        UUID version = UUID.randomUUID();
        byte[] original = SyntheticImages.image("jpeg", 32, 16);
        byte[] thumbnail = SyntheticImages.image("png", 32, 16);
        storage.save(version, original, thumbnail);

        assertThatIOException().isThrownBy(() -> storage.save(version, new byte[]{1}, new byte[]{2}));
        assertThat(Files.readAllBytes(temporary.resolve(version.toString()).resolve("original"))).isEqualTo(original);
        assertThat(storage.readThumbnail(version)).isEqualTo(thumbnail);
    }
}
