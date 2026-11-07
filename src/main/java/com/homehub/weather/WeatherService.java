package com.homehub.weather;

import static com.homehub.homeassistant.JsonValues.asList;
import static com.homehub.homeassistant.JsonValues.asMap;
import static com.homehub.homeassistant.JsonValues.text;

import com.homehub.homeassistant.DeviceNames;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the weather from Home Assistant's weather entity ({@code homehub.weather.entity}, normally
 * {@code weather.home}, fed by met.no): the current state, and the daily forecast through the
 * {@code weather.get_forecasts} action.
 * <p>
 * Weather isn't stored as events. The result is kept for {@code homehub.weather.refresh} (10 minutes). If Home
 * Assistant can't be reached, the last result is returned marked as stale and Kudil tries again after a minute.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);
    private static final int FORECAST_DAYS = 3;

    private final RestClient client;
    private final boolean enabled;
    private final String entity;
    private final Duration refresh;
    private final Clock clock;
    private Weather cached;
    private Instant nextFetch = Instant.MIN;

    @Autowired
    public WeatherService(HomeAssistantProperties homeAssistant,
                          @Value("${homehub.weather.entity:weather.home}") String entity,
                          @Value("${homehub.weather.refresh:10m}") Duration refresh) {
        this(RestClient.builder().requestFactory(timeouts()), homeAssistant, entity, refresh, Clock.systemUTC());
    }

    /** Lets tests supply a builder bound to a mock server and their own clock. */
    WeatherService(RestClient.Builder builder, HomeAssistantProperties homeAssistant, String entity, Duration refresh,
                   Clock clock) {
        this.entity = entity == null ? "" : entity.trim();
        this.refresh = refresh;
        this.clock = clock;
        boolean validEntity = this.entity.startsWith("weather.") && DeviceNames.isSafeEntityId(this.entity);
        if (!this.entity.isEmpty() && !validEntity) {
            log.warn("Ignoring homehub.weather.entity '{}': expected weather.<name>", this.entity);
        }
        this.enabled = validEntity && homeAssistant.isConfigured();
        if (enabled) {
            String token = homeAssistant.token().trim();
            builder.baseUrl(homeAssistant.url().trim().replaceAll("/+$", "") + "/api")
                    .defaultHeaders(h -> h.setBearerAuth(token));
        }
        this.client = builder.build();
    }

    /** The weather, from cache when it's recent; empty when weather is switched off or was never available. */
    public synchronized Optional<Weather> current() {
        if (!enabled) return Optional.empty();
        Instant now = clock.instant();
        if (now.isBefore(nextFetch)) return Optional.ofNullable(cached);
        try {
            cached = fetch(now);
            nextFetch = now.plus(refresh);
        } catch (RestClientException ex) {
            log.warn("Weather: Home Assistant can't be reached ({}); showing the last result", ex.getClass().getSimpleName());
            if (cached != null) cached = cached.asStale();
            nextFetch = now.plus(RETRY_AFTER_FAILURE);
        }
        return Optional.ofNullable(cached);
    }

    private Weather fetch(Instant now) {
        Map<String, Object> state = client.get().uri("/states/{id}", entity).retrieve().body(JSON);
        Map<String, Object> attrs = asMap(state == null ? null : state.get("attributes"));
        return new Weather(
                text(state == null ? null : state.get("state")),
                number(attrs.get("temperature")), text(attrs.get("temperature_unit")),
                number(attrs.get("humidity")),
                number(attrs.get("wind_speed")), text(attrs.get("wind_speed_unit")),
                text(attrs.get("precipitation_unit")),
                text(attrs.get("attribution")),
                now, false, forecast());
    }

    /** Today and the next days; empty if the forecast can't be read (the current weather is still shown). */
    private List<Weather.Day> forecast() {
        try {
            Map<String, Object> response = client.post().uri("/services/weather/get_forecasts?return_response")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("entity_id", entity, "type", "daily"))
                    .retrieve().body(JSON);
            List<?> days = asList(asMap(asMap(asMap(response).get("service_response")).get(entity)).get("forecast"));
            return days.stream().limit(FORECAST_DAYS).map(d -> {
                Map<String, Object> day = asMap(d);
                return new Weather.Day(text(day.get("datetime")), text(day.get("condition")),
                        number(day.get("temperature")), number(day.get("templow")), number(day.get("precipitation")));
            }).toList();
        } catch (RestClientException ex) {
            log.debug("Weather: no forecast ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return value == null ? null : Double.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static JdkClientHttpRequestFactory timeouts() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
