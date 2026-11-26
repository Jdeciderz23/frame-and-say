package com.groupphoto.consensus.authorization;

import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.identity.CurrentActor;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ConsentProgressController {
    private final CurrentActor actor;
    private final ConsentService consents;

    ConsentProgressController(CurrentActor actor, ConsentService consents) {
        this.actor = actor;
        this.consents = consents;
    }

    @GetMapping("/api/events/{eventId}/consent-progress")
    List<ConsentService.PhotoProgress> progress(@PathVariable String eventId, @RequestParam Purpose purpose) {
        return consents.progress(eventId, actor.requireId(), purpose);
    }
}
