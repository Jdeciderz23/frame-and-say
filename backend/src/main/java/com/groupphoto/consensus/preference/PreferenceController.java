package com.groupphoto.consensus.preference;

import com.groupphoto.consensus.identity.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/events/{eventId}/photos/{photoId}/preferences/me")
class PreferenceController {
    private final CurrentActor actor;
    private final PreferenceService preferences;

    PreferenceController(CurrentActor actor, PreferenceService preferences) {
        this.actor = actor;
        this.preferences = preferences;
    }

    @GetMapping
    MyPreference get(@PathVariable String eventId, @PathVariable String photoId,
                     @RequestParam String photoVersionId) {
        return new MyPreference(photoVersionId, preferences.read(eventId, photoId, photoVersionId, actor.requireId()));
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void put(@PathVariable String eventId, @PathVariable String photoId, @Valid @RequestBody ChangePreference request) {
        preferences.write(eventId, photoId, request.photoVersionId(), actor.requireId(), request.value());
    }

    record MyPreference(String photoVersionId, Integer value) {}

    record ChangePreference(@NotBlank @Size(max = 36) String photoVersionId,
                            @NotNull @Min(-1) @Max(1) Integer value) {}
}
