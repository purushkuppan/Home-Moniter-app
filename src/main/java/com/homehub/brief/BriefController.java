package com.homehub.brief;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/brief}: today's Daily Home Brief so far (#21), and when it goes out. */
@RestController
@RequestMapping("/api/brief")
@Tag(name = "Daily brief", description = "The evening summary of the day")
public class BriefController {

    private final BriefService brief;

    public BriefController(BriefService brief) {
        this.brief = brief;
    }

    @GetMapping
    @Operation(summary = "Today's brief so far, when it goes out, and whether it is on")
    public Map<String, Object> today() {
        return Map.of("text", brief.preview(), "at", brief.at().toString(), "enabled", brief.enabled());
    }
}
