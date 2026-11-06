package com.homehub.homeassistant;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Looks up the Home Assistant device each entity belongs to ("Refrigerator", "Washer", ...). */
public final class DeviceNames {

    private static final Pattern SAFE_ENTITY_ID = Pattern.compile("[a-z0-9_]+\\.[a-z0-9_]+");

    private DeviceNames() {
    }

    /** True for a well-formed entity id such as {@code sensor.kitchen_fridge_temperature}. */
    public static boolean isSafeEntityId(String entityId) {
        return entityId != null && SAFE_ENTITY_ID.matcher(entityId).matches();
    }

    /**
     * Device name per entity id, in one template request. Entities without a device (or if the lookup fails)
     * map to "". A rejected token (HTTP 401) is rethrown so callers can report it.
     *
     * @param client a client whose base URL is Home Assistant's {@code /api}, with the token set
     */
    public static Map<String, String> lookup(RestClient client, Collection<String> entityIds) {
        List<String> ids = entityIds.stream().filter(DeviceNames::isSafeEntityId).distinct().toList();
        Map<String, String> names = new HashMap<>();
        entityIds.forEach(id -> names.put(id, ""));
        if (ids.isEmpty()) return names;

        String template = "{% for e in [" + ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","))
                + "] %}{{ e }}|{{ device_attr(e, 'name_by_user') or device_attr(e, 'name') or '' }}\n{% endfor %}";
        try {
            String body = client.post().uri("/template")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("template", template))
                    .retrieve().body(String.class);
            for (String line : (body == null ? "" : body).split("\n")) {
                int bar = line.indexOf('|');
                if (bar > 0) names.put(line.substring(0, bar).trim(), line.substring(bar + 1).trim());
            }
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) throw ex;
            // Other failures: fall back to friendly names.
        }
        return names;
    }

    /** "Refrigerator Fridge door" on device "Refrigerator" -> "Fridge door"; "" when the name is just the device. */
    public static String partName(String friendlyName, String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) return friendlyName;
        return friendlyName.toLowerCase(Locale.ROOT).startsWith(deviceName.toLowerCase(Locale.ROOT))
                ? friendlyName.substring(deviceName.length()).trim()
                : friendlyName;
    }
}
