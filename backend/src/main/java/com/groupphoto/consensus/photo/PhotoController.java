package com.groupphoto.consensus.photo;

import com.groupphoto.consensus.identity.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/events/{eventId}/photos")
class PhotoController {
    private final CurrentActor actor;
    private final PhotoService photos;
    private final PhotoCatalog catalog;

    PhotoController(CurrentActor actor, PhotoService photos, PhotoCatalog catalog) {
        this.actor = actor;
        this.photos = photos;
        this.catalog = catalog;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PhotoMetadata upload(@PathVariable String eventId, @RequestPart("file") MultipartFile file) {
        return PhotoMetadata.from(photos.upload(eventId, actor.requireId(), file));
    }

    @GetMapping
    List<PhotoMetadata> list(@PathVariable String eventId) {
        return photos.list(eventId, actor.requireId()).stream().map(PhotoMetadata::from).toList();
    }

    @GetMapping("/{photoId}/versions")
    VersionHistory history(@PathVariable String eventId, @PathVariable String photoId) {
        var history = photos.history(eventId, photoId, actor.requireId());
        return new VersionHistory(history.canCreateVersion(), history.versions().stream().map(VersionEntry::from).toList());
    }

    @GetMapping("/versions/{photoVersionId}/metadata")
    VersionEntry resolveVersion(@PathVariable String eventId, @PathVariable String photoVersionId) {
        return VersionEntry.from(photos.resolveVersion(eventId, photoVersionId, actor.requireId()));
    }

    @PostMapping(value = "/{photoId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    VersionEntry uploadVersion(@PathVariable String eventId, @PathVariable String photoId,
                               @RequestParam UUID baseVersionId, @RequestPart("file") MultipartFile file) {
        return VersionEntry.from(photos.uploadVersion(eventId, photoId, baseVersionId.toString(), actor.requireId(), file));
    }

    @GetMapping(value = "/{photoId}/versions/{photoVersionId}/thumbnail", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> thumbnail(@PathVariable String eventId, @PathVariable String photoId,
                                    @PathVariable String photoVersionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(photos.thumbnail(eventId, photoId, photoVersionId, actor.requireId()));
    }

    @GetMapping("/{photoId}/versions/{photoVersionId}/subjects")
    SubjectRoster getSubjects(@PathVariable String eventId, @PathVariable String photoId,
                              @PathVariable String photoVersionId) {
        var version = photos.requireRoster(eventId, photoId, photoVersionId, actor.requireId());
        return new SubjectRoster(version.photoVersionId(), version.subjectRevision(),
                version.rosterConfirmed(), catalog.subjectIds(version));
    }

    @PutMapping("/{photoId}/versions/{photoVersionId}/subjects")
    PhotoMetadata setSubjects(@PathVariable String eventId, @PathVariable String photoId,
                              @PathVariable String photoVersionId, @Valid @RequestBody ChangeSubjects request) {
        return PhotoMetadata.from(photos.setSubjects(eventId, photoId, photoVersionId, actor.requireId(),
                request.subjectRevision(), request.userIds()));
    }

    record ChangeSubjects(@NotNull @PositiveOrZero Long subjectRevision,
                          @NotNull @Size(max = 10) List<@NotBlank @Size(max = 64) String> userIds) {}

    record SubjectRoster(String photoVersionId, long subjectRevision, boolean rosterConfirmed, List<String> userIds) {}

    record VersionHistory(boolean canCreateVersion, List<VersionEntry> versions) {}

    record VersionEntry(int versionNumber, PhotoMetadata photo) {
        static VersionEntry from(PhotoCatalog.Version version) {
            return new VersionEntry(version.versionNumber(), PhotoMetadata.from(version));
        }
    }

    record PhotoMetadata(String photoId, String photoVersionId, String eventId, boolean thumbnailAvailable,
                         String thumbnailUrl, long subjectRevision, boolean rosterConfirmed) {
        static PhotoMetadata from(PhotoCatalog.Version version) {
            String url = "/api/events/%s/photos/%s/versions/%s/thumbnail".formatted(
                    version.eventId(), version.photoId(), version.photoVersionId());
            return new PhotoMetadata(version.photoId(), version.photoVersionId(), version.eventId(), true,
                    url, version.subjectRevision(), version.rosterConfirmed());
        }
    }
}
