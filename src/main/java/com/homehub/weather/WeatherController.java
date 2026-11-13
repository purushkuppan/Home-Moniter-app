package com.homehub.weather;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/weather}: the weather for the dashboard card, or 204 No Content when there is none. */
@RestController
@Tag(name = "Weather", description = "Weather card, sunrise and sunset, official weather warnings")
public class WeatherController {

    private final WeatherService weather;

    public WeatherController(WeatherService weather) {
        this.weather = weather;
    }

    @GetMapping("/api/weather")
    @Operation(summary = "Weather for the dashboard card")
    @ApiResponse(responseCode = "200", description = "The current weather and forecast")
    @ApiResponse(responseCode = "204", description = "No weather (no weather entity set, or Home Assistant unreachable)")
    public ResponseEntity<Weather> weather() {
        return weather.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** {@code GET /api/sun}: whether the sun is up at home, for the "Sunset to sunrise" appearance; 204 when unknown. */
    @GetMapping("/api/sun")
    @Operation(summary = "Whether the sun is up at home", description = "For the \"Sunset to sunrise\" appearance.")
    @ApiResponse(responseCode = "200", description = "Sun up or down, with the next sunrise and sunset")
    @ApiResponse(responseCode = "204", description = "Unknown")
    public ResponseEntity<Sun> sun() {
        return weather.sun().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
