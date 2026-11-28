package com.groupphoto.consensus.photo;

import com.groupphoto.consensus.identity.CurrentActor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}")
class PhotoDeletionController {
    private final CurrentActor actor;
    private final PhotoDeletionService deletions;

    PhotoDeletionController(CurrentActor actor, PhotoDeletionService deletions) {
        this.actor = actor;
        this.deletions = deletions;
    }

    @PostMapping("/photos/{photoId}/deletion-request")
    PhotoDeletionService.DeletionRequest request(@PathVariable String eventId, @PathVariable String photoId) {
        return deletions.request(eventId, photoId, actor.requireId());
    }

    @GetMapping("/photos/{photoId}/deletion-request")
    PhotoDeletionService.DeletionRequest read(@PathVariable String eventId, @PathVariable String photoId) {
        return deletions.read(eventId, photoId, actor.requireId());
    }

    @PutMapping("/photos/{photoId}/deletion-request/approval")
    PhotoDeletionService.DeletionRequest approve(@PathVariable String eventId, @PathVariable String photoId) {
        return deletions.approve(eventId, photoId, actor.requireId());
    }

    @GetMapping("/photo-deletion-requests")
    List<PhotoDeletionService.DeletionRequest> list(@PathVariable String eventId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return deletions.list(eventId, actor.requireId(), limit, offset);
    }
}
