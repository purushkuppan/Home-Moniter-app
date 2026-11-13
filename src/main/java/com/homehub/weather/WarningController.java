package com.homehub.weather;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/warnings}: the official weather warnings in force for the home (#106), with the source's name, or a
 * note when there can't be any (no location set in Home Assistant, or no source for the home's country yet).
 */
@RestController
@RequestMapping("/api/warnings")
@Tag(name = "Weather", description = "Weather card, sunrise and sunset, official weather warnings")
public class WarningController {

    private final WarningService warnings;

    public WarningController(WarningService warnings) {
        this.warnings = warnings;
    }

    @GetMapping
    @Operation(summary = "Official weather warnings in force for the home",
            description = "With the source's name, or a note when there can't be any (no location set in Home "
                    + "Assistant, or no source for the home's country yet).")
    public WarningService.Status status() {
        return warnings.status();
    }
}
