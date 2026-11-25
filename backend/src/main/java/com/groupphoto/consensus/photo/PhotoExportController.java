package com.groupphoto.consensus.photo;

import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.identity.CurrentActor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/confirmed-selections")
class PhotoExportController {
    private final CurrentActor actor;
    private final PhotoExportService exports;

    PhotoExportController(CurrentActor actor, PhotoExportService exports) {
        this.actor = actor;
        this.exports = exports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PhotoExportService.Selection confirm(@PathVariable String eventId, @Valid @RequestBody ConfirmSelection request) {
        return exports.confirm(eventId, actor.requireId(), request.purpose(), request.items().stream()
                .map(item -> new PhotoExportService.Item(item.photoId(), item.photoVersionId(), item.subjectRevision()))
                .toList());
    }

    @GetMapping("/{selectionId}/export")
    void download(@PathVariable String eventId, @PathVariable UUID selectionId, HttpServletResponse response)
            throws IOException {
        try (var archive = exports.prepare(eventId, selectionId, actor.requireId())) {
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("photos-" + selectionId + ".zip").build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            archive.copyTo(response.getOutputStream());
        }
    }

    record ConfirmSelection(@NotNull Purpose purpose,
                            @NotEmpty @Size(max = 100) List<@NotNull @Valid ReviewedPhoto> items) {}

    record ReviewedPhoto(@NotNull UUID photoId, @NotNull UUID photoVersionId,
                         @NotNull @PositiveOrZero Long subjectRevision) {}
}
