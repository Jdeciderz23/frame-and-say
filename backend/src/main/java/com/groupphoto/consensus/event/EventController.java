package com.groupphoto.consensus.event;

import java.util.List;

import com.groupphoto.consensus.identity.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
class EventController {
    private final EventService events;
    private final CurrentActor actor;

    EventController(EventService events, CurrentActor actor) {
        this.events = events;
        this.actor = actor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventService.Event create(@Valid @RequestBody CreateEvent input) {
        return events.create(actor.requireId(), input.name(), input.desiredPhotoCount());
    }

    @GetMapping
    public List<EventService.Event> list() {
        return events.list(actor.requireId());
    }

    record CreateEvent(@NotBlank @Size(max = 80) String name, @Min(1) @Max(100) Integer desiredPhotoCount) {}
}
