package com.homehub.events;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identifies the running server, so open dashboards can notice a deploy or restart and reload themselves.
 * {@code started} changes on every start; {@code version} is the jar's version ("dev" when run from source).
 */
@RestController
public class VersionController {

    private final Instant started = Instant.now();
    private final String version;

    public VersionController() {
        String v = VersionController.class.getPackage().getImplementationVersion();
        this.version = v == null ? "dev" : v;
    }

    @GetMapping("/api/version")
    public Map<String, String> version() {
        return Map.of("version", version, "started", started.toString());
    }
}
