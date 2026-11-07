package com.homehub.weather;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/weather}: the weather for the dashboard card, or 204 No Content when there is none. */
@RestController
public class WeatherController {

    private final WeatherService weather;

    public WeatherController(WeatherService weather) {
        this.weather = weather;
    }

    @GetMapping("/api/weather")
    public ResponseEntity<Weather> weather() {
        return weather.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
