package com.groupphoto.consensus.authorization;

import com.groupphoto.consensus.authorization.Publications.Decision;
import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.identity.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/photos/{photoId}/consents/me")
class ConsentController {
    private final CurrentActor actor;
    private final ConsentService consents;

    ConsentController(CurrentActor actor, ConsentService consents) {
        this.actor = actor;
        this.consents = consents;
    }

    @GetMapping
    ConsentService.MyConsent get(@PathVariable String eventId, @PathVariable String photoId,
                                 @RequestParam String photoVersionId, @RequestParam Purpose purpose) {
        return consents.read(eventId, photoId, photoVersionId, actor.requireId(), purpose);
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void put(@PathVariable String eventId, @PathVariable String photoId, @Valid @RequestBody ChangeConsent request) {
        consents.write(eventId, photoId, request.photoVersionId(), actor.requireId(),
                request.subjectRevision(), request.purpose(), request.decision());
    }

    record ChangeConsent(@NotBlank @Size(max = 36) String photoVersionId,
                         @NotNull @PositiveOrZero Long subjectRevision,
                         @NotNull Purpose purpose, @NotNull Decision decision) {}
}
