package com.homehub.connectors;

import com.homehub.events.Event;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads the fridge, washer and dryer from the SmartThings API.
 * The token comes from the SMARTTHINGS_TOKEN environment variable.
 * Only values that changed since the previous poll are returned.
 */
@Component
@ConditionalOnProperty(name = "homehub.connectors.smartthings.enabled", havingValue = "true")
public class SmartThingsConnector implements DeviceConnector {

    private static final Logger log = LoggerFactory.getLogger(SmartThingsConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};
    private static final Pattern WANTED = Pattern.compile("fridge|refrigerator|washer|washing|dryer", Pattern.CASE_INSENSITIVE);

    /** A capability attribute we turn into an event; the attribute name becomes the event type. */
    private record Reading(String capability, String attribute) {}

    private static final List<Reading> READINGS = List.of(
            new Reading("contactSensor", "contact"),
            new Reading("temperatureMeasurement", "temperature"),
            new Reading("washerOperatingState", "machineState"),
            new Reading("dryerOperatingState", "machineState"));

    private final RestClient client;
    private final boolean hasToken;
    /** Last value seen, keyed by deviceId/component/attribute (ids are unique, labels may not be). */
    private final Map<String, String> lastValues = new HashMap<>();
    private boolean missingTokenWarned;

    @Autowired
    public SmartThingsConnector(
            @Value("${SMARTTHINGS_TOKEN:}") String token,
            @Value("${homehub.connectors.smartthings.base-url:https://api.smartthings.com/v1}") String baseUrl) {
        this(RestClient.builder(), token, baseUrl);
    }

    /** Lets tests supply a builder bound to a mock server. */
    SmartThingsConnector(RestClient.Builder builder, String token, String baseUrl) {
        String trimmed = token == null ? "" : token.trim();
        this.hasToken = !trimmed.isEmpty();
        builder.baseUrl(baseUrl);
        if (hasToken) {
            builder.defaultHeaders(h -> h.setBearerAuth(trimmed));
        }
        this.client = builder.build();
    }

    @Override
    public String name() {
        return "smartthings";
    }

    @Override
    public synchronized List<Event> poll() {
        if (!hasToken) {
            if (!missingTokenWarned) {
                log.warn("SmartThings connector is enabled but SMARTTHINGS_TOKEN is not set; nothing will be polled.");
                missingTokenWarned = true;
            }
            return List.of();
        }
        List<Event> changed = new ArrayList<>();
        try {
            for (Object item : asList(get("/devices").get("items"))) {
                pollDevice(asMap(item), changed);
            }
        } catch (RestClientResponseException ex) {
            if (isUnauthorized(ex)) {
                log.warn("SmartThings rejected the token (HTTP 401). Check that SMARTTHINGS_TOKEN is valid and not expired.");
            } else {
                log.warn("SmartThings device list failed: HTTP {}", ex.getStatusCode().value());
            }
        } catch (RuntimeException ex) {
            log.warn("SmartThings request failed: {}", ex.toString());
        }
        return changed;
    }

    private void pollDevice(Map<String, Object> device, List<Event> out) {
        String id = text(device.get("deviceId"));
        String name = firstNonBlank(text(device.get("label")), text(device.get("name")), id);
        String searchText = name + " " + text(device.get("name")) + " " + text(device.get("deviceTypeName"));
        if (id.isEmpty() || !WANTED.matcher(searchText).find()) {
            return;
        }
        try {
            readStatus(id, name, get("/devices/{id}/status", id), out);
        } catch (RestClientResponseException ex) {
            if (isUnauthorized(ex)) throw ex;  // a rejected token affects every device; report it once
            log.warn("SmartThings: status for '{}' failed: HTTP {}", name, ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            log.warn("SmartThings: status for '{}' failed: {}", name, ex.toString());
        }
    }

    private void readStatus(String deviceId, String deviceName, Map<String, Object> status, List<Event> out) {
        Instant now = Instant.now();
        for (Map.Entry<String, Object> component : asMap(status.get("components")).entrySet()) {
            String componentId = component.getKey();
            String displayName = "main".equals(componentId) ? deviceName : deviceName + "/" + componentId;
            Map<String, Object> capabilities = asMap(component.getValue());
            for (Reading reading : READINGS) {
                Map<String, Object> attr = asMap(asMap(capabilities.get(reading.capability())).get(reading.attribute()));
                if (attr.get("value") == null) continue;
                String value = withUnit(text(attr.get("value")), text(attr.get("unit")));
                String key = deviceId + "/" + componentId + "/" + reading.attribute();
                if (!value.equals(lastValues.put(key, value))) {
                    out.add(new Event(null, displayName, reading.attribute(), value, now));
                }
            }
        }
    }

    private Map<String, Object> get(String path, Object... uriVars) {
        Map<String, Object> body = client.get().uri(path, uriVars).retrieve().body(JSON);
        return body == null ? Map.of() : body;
    }

    private static boolean isUnauthorized(RestClientResponseException ex) {
        return ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED);
    }

    private static String withUnit(String value, String unit) {
        return unit.isEmpty() ? value : value + " " + unit;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> asList(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
