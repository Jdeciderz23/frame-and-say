package com.groupphoto.consensus.membership;

import java.util.List;

import com.groupphoto.consensus.identity.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/members")
class MembershipController {
    private final EventMembership memberships;
    private final CurrentActor actor;

    MembershipController(EventMembership memberships, CurrentActor actor) {
        this.memberships = memberships;
        this.actor = actor;
    }

    @GetMapping
    public List<EventMembership.Member> members(@PathVariable String eventId) {
        return memberships.list(eventId, actor.requireId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@PathVariable String eventId, @Valid @RequestBody AddMember request) {
        memberships.addParticipant(eventId, actor.requireId(), request.userId());
    }

    record AddMember(@NotBlank @Size(max = 64) String userId) {}
}
