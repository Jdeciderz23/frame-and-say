package com.groupphoto.consensus.photo;

import com.groupphoto.consensus.authorization.ConsentService;
import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.membership.EventMembership;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class PhotoExportService {
    record Item(UUID photoId, UUID photoVersionId, long subjectRevision) {}

    record Selection(UUID selectionId, String eventId, Purpose purpose, List<Item> items) {}

    record PreparedArchive(Path path) implements AutoCloseable {
        void copyTo(OutputStream output) throws IOException {
            Files.copy(path, output);
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    private final EventMembership membership;
    private final ConsentService consents;
    private final PhotoStorage storage;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PhotoExportService(EventMembership membership, ConsentService consents, PhotoStorage storage, JdbcClient jdbc,
                       PlatformTransactionManager manager) {
        this.membership = membership;
        this.consents = consents;
        this.storage = storage;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    Selection confirm(String eventId, String actor, Purpose purpose, List<Item> items) {
        membership.requireOrganizer(eventId, actor);
        if (items.stream().map(Item::photoId).distinct().count() != items.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A selection can include only one version per photo");
        }
        var selection = new Selection(UUID.randomUUID(), eventId, purpose, List.copyOf(items));
        return transactions.execute(status -> {
            validate(selection, actor, false);
            String id = selection.selectionId().toString();
            jdbc.sql("INSERT INTO confirmed_selections (id, event_id, purpose) VALUES (?, ?, ?)")
                    .params(id, eventId, purpose.name()).update();
            for (int index = 0; index < items.size(); index++) {
                Item item = items.get(index);
                jdbc.sql("""
                        INSERT INTO confirmed_selection_items
                            (selection_id, sort_order, photo_id, photo_version_id, subject_revision)
                        VALUES (?, ?, ?, ?, ?)
                        """).params(id, index, item.photoId().toString(), item.photoVersionId().toString(),
                                item.subjectRevision()).update();
            }
            return selection;
        });
    }

    PreparedArchive prepare(String eventId, UUID selectionId, String actor) {
        membership.requireOrganizer(eventId, actor);
        Selection selection = load(eventId, selectionId);
        Map<UUID, PhotoCatalog.Version> versions = transactions.execute(status -> validate(selection, actor, true));
        Path staged = null;
        try {
            staged = Files.createTempFile("frame-say-export-", ".zip");
            writeArchive(staged, selection, versions);
            // Recheck after slow file I/O, under the same photo locks used by roster and consent writes.
            transactions.executeWithoutResult(status -> validate(selection, actor, true));
            return new PreparedArchive(staged);
        } catch (IOException error) {
            removeStagingFile(staged, error);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo export is unavailable", error);
        } catch (RuntimeException error) {
            removeStagingFile(staged, error);
            throw error;
        }
    }

    private Selection load(String eventId, UUID selectionId) {
        Purpose purpose = jdbc.sql("SELECT purpose FROM confirmed_selections WHERE id = ? AND event_id = ?")
                .params(selectionId.toString(), eventId).query(String.class).optional().map(Purpose::valueOf)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Confirmed selection not found"));
        List<Item> items = jdbc.sql("""
                SELECT photo_id, photo_version_id, subject_revision FROM confirmed_selection_items
                WHERE selection_id = ? ORDER BY sort_order
                """).param(selectionId.toString()).query((row, index) -> new Item(
                        UUID.fromString(row.getString("photo_id")), UUID.fromString(row.getString("photo_version_id")),
                        row.getLong("subject_revision"))).list();
        if (items.isEmpty()) throw invalidated();
        return new Selection(selectionId, eventId, purpose, items);
    }

    private Map<UUID, PhotoCatalog.Version> validate(Selection selection, String actor, boolean existing) {
        // Acquire locks in the same order for overlapping exports regardless of the reviewed order.
        Map<UUID, PhotoCatalog.Version> versions = new HashMap<>();
        for (Item item : selection.items().stream().sorted(Comparator.comparing(Item::photoId)).toList()) {
            try {
                var version = consents.requireEligibleForDelivery(selection.eventId(), item.photoId().toString(),
                        item.photoVersionId().toString(), actor, selection.purpose(), item.subjectRevision());
                versions.put(item.photoVersionId(), version);
            } catch (ResponseStatusException error) {
                if (existing && (error.getStatusCode() == HttpStatus.NOT_FOUND || error.getStatusCode() == HttpStatus.CONFLICT)) {
                    throw invalidated();
                }
                throw error;
            }
        }
        return versions;
    }

    private void writeArchive(Path staged, Selection selection, Map<UUID, PhotoCatalog.Version> versions) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(staged))) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (Item item : selection.items()) {
                UUID versionId = item.photoVersionId();
                String extension = switch (versions.get(versionId).mediaType()) {
                    case "image/jpeg" -> ".jpg";
                    case "image/png" -> ".png";
                    default -> throw new IOException("Unsupported stored media type");
                };
                try (var original = storage.openOriginal(versionId)) {
                    zip.putNextEntry(new ZipEntry(versionId + extension));
                    original.transferTo(zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private static ResponseStatusException invalidated() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The confirmed selection is no longer valid; review it again");
    }

    private static void removeStagingFile(Path staged, Exception originalError) {
        if (staged == null) return;
        try {
            Files.deleteIfExists(staged);
        } catch (IOException cleanupError) {
            originalError.addSuppressed(cleanupError);
        }
    }
}
