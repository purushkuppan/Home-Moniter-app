package com.homehub.events;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/events")
@Tag(name = "Events", description = "Device readings, commands and alerts as they are stored, and the live feed")
public class EventController {

    private final EventRepository repository;
    private final EventStream stream;

    public EventController(EventRepository repository, EventStream stream) {
        this.repository = repository;
        this.stream = stream;
    }

    /** Live feed of newly saved events (Server-Sent Events, event name "event", JSON data). */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live feed of new events (Server-Sent Events)",
            description = "Stays open and sends each newly saved event as it happens (event name \"event\", the event "
                    + "as JSON data). Swagger UI can't play a stream: open it in the browser or with `curl -N`.")
    @ApiResponse(responseCode = "200", description = "A text/event-stream of events",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(implementation = Event.class)))
    public SseEmitter stream() {
        return stream.subscribe();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Store an event", description = "device and type are required; 400 without them.")
    public Event add(@RequestBody Event event) {
        if (event.device() == null || event.device().isBlank() || event.type() == null || event.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device and type are required");
        }
        return repository.add(event.device(), event.type(), event.value());
    }

    @GetMapping
    @Operation(summary = "The 50 most recent events")
    public List<Event> latest() {
        return repository.latest(50);
    }

    /** Current state: the last value of every device reading, however old (used by the status cards). */
    @GetMapping("/latest")
    @Operation(summary = "Current state: the last value of every device reading, however old")
    public List<Event> latestPerReading() {
        return repository.latestPerReading();
    }
}
