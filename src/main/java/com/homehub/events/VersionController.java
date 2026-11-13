package com.homehub.events;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identifies the running server, so open dashboards can notice a deploy or restart and reload themselves.
 * {@code started} changes on every start; {@code version} is the jar's version ("dev" when run from source).
 */
@RestController
@Tag(name = "Version", description = "Which release is running and since when")
public class VersionController {

    private final Instant started = Instant.now();
    private final String version = buildVersion();

    /** The jar's version, set by the release build ("dev" when run from source). The API docs show the same. */
    public static String buildVersion() {
        String v = VersionController.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    @GetMapping("/api/version")
    @Operation(summary = "Running version and start time",
            description = "Open dashboards reload themselves when either changes (a deploy or a restart).")
    public Map<String, String> version() {
        return Map.of("version", version, "started", started.toString());
    }
}
