package com.homehub.controls;

import static com.homehub.homeassistant.JsonValues.asList;
import static com.homehub.homeassistant.JsonValues.asMap;
import static com.homehub.homeassistant.JsonValues.text;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.DeviceNames;
import com.homehub.homeassistant.EntityPatterns;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Lists and changes the devices HomeHub may control, through Home Assistant.
 * <p>
 * Only entities matching {@code homehub.controls.entities} (comma-separated, {@code *} wildcard) in a supported
 * domain can be controlled. Every value is checked against what Home Assistant reports (range and step for numbers,
 * the option list for selects) before anything is sent, and every successful command is recorded as an event.
 */
@Service
@ConditionalOnProperty(name = "homehub.controls.enabled", havingValue = "true")
public class ControlService {

    private static final Logger log = LoggerFactory.getLogger(ControlService.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> STATES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> STATE = new ParameterizedTypeReference<>() {};
    private static final Set<String> DOMAINS = Set.of("number", "switch", "select", "button");
    private static final Set<String> CYCLE_OPTIONS = Set.of("run", "pause", "stop");

    private final RestClient client;
    private final boolean configured;
    private final EntityPatterns allowed;
    private final Consumer<Event> audit;
    private final Map<String, String> deviceNames = new HashMap<>();

    @Autowired
    public ControlService(HomeAssistantProperties properties,
                          @Value("${homehub.controls.entities:}") String entities,
                          EventRepository repository) {
        this(RestClient.builder(), properties, entities, repository::save);
    }

    /** Lets tests supply a builder bound to a mock server and their own audit sink. */
    ControlService(RestClient.Builder builder, HomeAssistantProperties properties, String entities, Consumer<Event> audit) {
        this.configured = properties.isConfigured();
        this.allowed = EntityPatterns.parse(entities);
        this.audit = audit;
        if (configured) {
            String token = properties.token().trim();
            builder.baseUrl(properties.url().trim().replaceAll("/+$", "") + "/api")
                    .defaultHeaders(h -> h.setBearerAuth(token));
        }
        this.client = builder.build();
    }

    /** True if {@code entityId} may be controlled by HomeHub at all. */
    public boolean isAllowed(String entityId) {
        return DeviceNames.isSafeEntityId(entityId)
                && DOMAINS.contains(domainOf(entityId))
                && allowed.matches(entityId);
    }

    /** All controls the dashboard may show, grouped by device and sorted by label. */
    public List<Control> list() {
        requireConfigured();
        List<Map<String, Object>> states = call(() -> client.get().uri("/states").retrieve().body(STATES));
        if (states == null) states = List.of();
        Map<String, Map<String, Object>> byId = new HashMap<>();
        states.forEach(s -> byId.put(text(s.get("entity_id")), s));

        List<Map<String, Object>> wanted = states.stream().filter(s -> isAllowed(text(s.get("entity_id")))).toList();
        resolveDeviceNames(wanted.stream().map(s -> text(s.get("entity_id"))).toList());
        return wanted.stream()
                .map(s -> toControl(s, byId))
                .sorted(Comparator.comparing(Control::device).thenComparing(Control::label))
                .toList();
    }

    /**
     * Changes one control.
     *
     * @param value the new value: a number, "on"/"off", one of the options, or ignored for buttons
     * @return a short description of what was done, e.g. "Fridge temperature set to 38 °F"
     */
    public String apply(String entityId, String value) {
        requireConfigured();
        if (!isAllowed(entityId)) {
            throw new ControlException(HttpStatus.FORBIDDEN, "This device can't be controlled from HomeHub.");
        }
        Map<String, Object> state = call(() -> client.get().uri("/states/{id}", entityId).retrieve().body(STATE));
        if (state == null || state.isEmpty()) {
            throw new ControlException(HttpStatus.NOT_FOUND, "Home Assistant doesn't know this device.");
        }
        resolveDeviceNames(List.of(entityId));
        Control control = toControl(state, Map.of());
        String v = value == null ? "" : value.trim();

        String service;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entity_id", entityId);
        String description;
        switch (domainOf(entityId)) {
            case "number" -> {
                BigDecimal number = checkNumber(control, v);
                service = "number/set_value";
                body.put("value", number.stripTrailingZeros().scale() <= 0 ? number.intValueExact() : number.doubleValue());
                description = control.label() + " set to " + number.stripTrailingZeros().toPlainString()
                        + (control.unit() == null ? "" : " " + control.unit());
            }
            case "switch" -> {
                if (!v.equals("on") && !v.equals("off")) throw invalid("Choose on or off.");
                service = "switch/turn_" + v;
                description = control.label() + " turned " + v;
            }
            case "select" -> {
                if (!control.options().contains(v)) throw invalid("Choose one of: " + String.join(", ", control.options()) + ".");
                service = "select/select_option";
                body.put("option", v);
                description = control.label() + " set to " + v.replace('_', ' ');
            }
            case "button" -> {
                service = "button/press";
                description = control.label() + " pressed";
            }
            default -> throw new ControlException(HttpStatus.FORBIDDEN, "This device can't be controlled from HomeHub.");
        }

        call(() -> client.post().uri("/services/" + service)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity());
        log.info("Control: {} / {}", control.device(), description);
        audit.accept(new Event(null, control.device(), "command", description, Instant.now()));
        return description;
    }

    private BigDecimal checkNumber(Control control, String value) {
        BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw invalid("Enter a number.");
        }
        if (control.min() == null || control.max() == null) throw invalid("Home Assistant reports no range for this control.");
        BigDecimal min = BigDecimal.valueOf(control.min()), max = BigDecimal.valueOf(control.max());
        if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
            throw invalid("Choose a value from " + plain(min) + " to " + plain(max) + ".");
        }
        BigDecimal step = BigDecimal.valueOf(control.step() == null || control.step() <= 0 ? 1 : control.step());
        if (number.subtract(min).remainder(step).compareTo(BigDecimal.ZERO) != 0) {
            throw invalid("Use steps of " + plain(step) + ".");
        }
        return number;
    }

    private Control toControl(Map<String, Object> state, Map<String, Map<String, Object>> allStates) {
        String id = text(state.get("entity_id"));
        String domain = domainOf(id);
        Map<String, Object> attrs = asMap(state.get("attributes"));
        String friendly = text(attrs.get("friendly_name")).isBlank() ? id : text(attrs.get("friendly_name"));
        String device = deviceNames.getOrDefault(id, "");
        String part = DeviceNames.partName(friendly, device);
        if (device.isEmpty()) device = friendly;

        List<String> options = asList(attrs.get("options")).stream().map(String::valueOf).toList();
        boolean cycle = "select".equals(domain) && !options.isEmpty() && CYCLE_OPTIONS.containsAll(options);
        String kind = cycle ? "cycle" : domain;
        // A switch that is the whole device (a smart plug) turns its power on and off: "Power turned on".
        String label = !part.isEmpty() ? capitalize(part)
                : cycle ? "Cycle" : "switch".equals(domain) ? "Power" : capitalize(domain);

        String note = null;
        if (cycle) {
            // Samsung washers and dryers only accept a remote start while "Remote Control" is on at the machine.
            Map<String, Object> remote = allStates.get("binary_sensor." + id.substring(id.indexOf('.') + 1) + "_remote_control");
            if (remote != null && "off".equals(text(remote.get("state")))) {
                note = "To start remotely, turn on Remote Control at the machine. Pause and stop work anyway.";
            }
        }
        return new Control(id, device, label, kind, text(state.get("state")),
                number(attrs.get("min")), number(attrs.get("max")), number(attrs.get("step")),
                attrs.get("unit_of_measurement") == null ? null : text(attrs.get("unit_of_measurement")),
                options, note);
    }

    private void resolveDeviceNames(List<String> ids) {
        List<String> unknown = ids.stream().filter(id -> !deviceNames.containsKey(id)).toList();
        if (!unknown.isEmpty()) deviceNames.putAll(call(() -> DeviceNames.lookup(client, unknown)));
    }

    private void requireConfigured() {
        if (!configured) {
            throw new ControlException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Home Assistant is not configured (homeassistant.url and homeassistant.token).");
        }
    }

    /** Runs a Home Assistant request, turning failures into messages the user can act on. */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                throw new ControlException(HttpStatus.BAD_GATEWAY, "Home Assistant rejected HomeHub's token.");
            }
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw new ControlException(HttpStatus.NOT_FOUND, "Home Assistant doesn't know this device.");
            }
            String detail = haMessage(ex.getResponseBodyAsString());
            throw new ControlException(HttpStatus.BAD_GATEWAY,
                    "The device refused the command" + (detail.isEmpty() ? "." : ": " + detail));
        } catch (RestClientException ex) {
            throw new ControlException(HttpStatus.BAD_GATEWAY, "Home Assistant is not reachable.");
        }
    }

    /** Home Assistant errors look like {"message": "..."}; keep just the message, shortened. */
    private static String haMessage(String body) {
        if (body == null) return "";
        var m = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]{1,200})").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private static ControlException invalid(String message) {
        return new ControlException(HttpStatus.BAD_REQUEST, message);
    }

    private static String domainOf(String entityId) {
        int dot = entityId.indexOf('.');
        return dot < 0 ? "" : entityId.substring(0, dot);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    private static Double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? null : Double.valueOf(o.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

}
