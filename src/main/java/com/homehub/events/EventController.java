package com.homehub.events;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventRepository repository;

    public EventController(EventRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Event add(@RequestBody Event event) {
        if (event.device() == null || event.device().isBlank() || event.type() == null || event.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device and type are required");
        }
        return repository.add(event.device(), event.type(), event.value());
    }

    @GetMapping
    public List<Event> latest() {
        return repository.latest(50);
    }
}
