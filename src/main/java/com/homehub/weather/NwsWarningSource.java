package com.homehub.weather;

import static com.homehub.homeassistant.JsonValues.asList;
import static com.homehub.homeassistant.JsonValues.asMap;
import static com.homehub.homeassistant.JsonValues.text;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Official warnings for US homes from the National Weather Service (#106): {@code GET api.weather.gov/alerts/active
 * ?point=lat,lon}. Free, no account or key. NWS asks for a User-Agent that names the app and, ideally, a contact; the
 * contact comes from {@code homehub.warnings.contact} and is empty unless the household sets one.
 */
@Component
public class NwsWarningSource implements WarningSource {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

    /** Life-threatening: they ring like a leak (#101). */
    static final Set<String> CRITICAL = Set.of("Tornado Warning", "Tornado Emergency", "Severe Thunderstorm Warning",
            "Flash Flood Warning", "Flash Flood Emergency", "Extreme Wind Warning", "Hurricane Warning", "Storm Surge Warning");

    private final RestClient client;

    @Autowired
    public NwsWarningSource(@Value("${homehub.warnings.contact:}") String contact) {
        this(RestClient.builder().requestFactory(timeouts()), contact);
    }

    NwsWarningSource(RestClient.Builder builder, String contact) {
        String who = contact == null || contact.isBlank() ? "" : ", " + contact.trim();
        this.client = builder.baseUrl("https://api.weather.gov")
                .defaultHeader("User-Agent", "(Kudil home monitor" + who + ")")
                .defaultHeader("Accept", "application/geo+json")
                .build();
    }

    @Override
    public String name() {
        return "US National Weather Service";
    }

    @Override
    public boolean covers(String country) {
        return "US".equalsIgnoreCase(country);
    }

    @Override
    public List<WeatherWarning> active(double latitude, double longitude) {
        String point = String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude);
        // Digits, a sign and a comma only: written as it is, so the comma isn't encoded.
        Map<String, Object> body = client.get().uri("/alerts/active?point=" + point).retrieve().body(JSON);
        String link = String.format(Locale.ROOT, "https://forecast.weather.gov/MapClick.php?lat=%.2f&lon=%.2f", latitude, longitude);
        List<WeatherWarning> warnings = new ArrayList<>();
        for (Object feature : asList(body == null ? null : body.get("features"))) {
            Map<String, Object> p = asMap(asMap(feature).get("properties"));
            // Real alerts only (not tests or exercises), and not the notice that one was cancelled.
            if (!"Actual".equals(text(p.get("status"))) || "Cancel".equals(text(p.get("messageType")))) continue;
            String event = text(p.get("event"));
            if (event.isEmpty()) continue;
            Instant ends = time(p.get("ends"));
            if (ends == null) ends = time(p.get("expires"));
            warnings.add(new WeatherWarning(event, levelOf(event), text(p.get("headline")), ends,
                    hail(asMap(p.get("parameters")).get("maxHailSize")), link));
        }
        return warnings;
    }

    /** Tornado, flash flood… critical; any other warning a warning; watches, advisories and statements a notice. */
    static WeatherWarning.Level levelOf(String event) {
        if (CRITICAL.contains(event)) return WeatherWarning.Level.CRITICAL;
        if (event.endsWith("Warning") || event.endsWith("Emergency")) return WeatherWarning.Level.WARNING;
        return WeatherWarning.Level.NOTICE;
    }

    private static Double hail(Object value) {
        try {
            List<?> values = asList(value);
            double inches = values.isEmpty() ? 0 : Double.parseDouble(text(values.get(0)));
            return inches > 0 ? inches : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Instant time(Object value) {
        try {
            String s = text(value);
            return s.isEmpty() ? null : OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static JdkClientHttpRequestFactory timeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }
}
