package com.homehub.brief;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/brief}: today's Daily Home Brief so far (#21), and when it goes out. */
@RestController
@RequestMapping("/api/brief")
public class BriefController {

    private final BriefService brief;

    public BriefController(BriefService brief) {
        this.brief = brief;
    }

    @GetMapping
    public Map<String, Object> today() {
        return Map.of("text", brief.preview(), "at", brief.at().toString(), "enabled", brief.enabled());
    }
}
