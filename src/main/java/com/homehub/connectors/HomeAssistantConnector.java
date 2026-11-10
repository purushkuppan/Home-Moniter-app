package com.homehub.connectors;

import static com.homehub.homeassistant.JsonValues.asMap;
import static com.homehub.homeassistant.JsonValues.firstNonBlank;
import static com.homehub.homeassistant.JsonValues.text;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.DeviceNames;
import com.homehub.homeassistant.EntityPatterns;
import com.homehub.homeassistant.HomeAssistantProperties;
import com.homehub.homeassistant.HomeAssistantStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads entity states from Home Assistant.
 * <ul>
 *   <li><b>Live</b>: subscribes to {@code state_changed} over the WebSocket API ({@code /api/websocket}) and saves every
 *       change the moment Home Assistant reports it, with Home Assistant's own change time.</li>
 *   <li><b>Poll</b>: {@code GET /api/states} to record the current state at startup, on the scheduler's interval
 *       while the live connection is down, and only every {@code poll-while-live} (5 minutes) as a safety net while it
 *       works.</li>
 * </ul>
 * Only entities whose id matches one of the include patterns are used. Each entity becomes an event named after its
 * Home Assistant device (for example "Refrigerator/Fridge", type "contact", value "closed"), and an event is only
 * recorded when the value changed. The URL and token come from {@link HomeAssistantProperties}; the token is never logged.
 */
@Component
@ConditionalOnProperty(name = "homehub.connectors.homeassistant.enabled", havingValue = "true")
public class HomeAssistantConnector implements DeviceConnector, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantConnector.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> STATES = new ParameterizedTypeReference<>() {};
    private static final Set<String> CONTACT_CLASSES = Set.of("door", "window", "opening", "garage_door");
    static final String DEFAULT_INCLUDE =
            "binary_sensor.*_door,sensor.*_temperature,sensor.*_machine_state,sensor.*_job_state,sensor.*_battery,lock.*";
    private static final int MAX_BACKOFF_SECONDS = 60;
    private static final int AUTH_FAILED_RETRY_SECONDS = 300;

    private final RestClient client;
    private final boolean configured;
    private final EntityPatterns include;
    private final JsonMapper json = JsonMapper.builder().build();
    /** Last value seen per entity id, shared by the live and poll paths. */
    private final Map<String, String> lastValues = new HashMap<>();
    /** Device name per entity id (from Home Assistant's device registry). */
    private final Map<String, String> deviceNames = new HashMap<>();
    private boolean missingConfigWarned;
    private Instant lastPollAt;
    /** While live updates work, poll this seldom (a safety net); while they don't, on every scheduler run. */
    private final Duration pollWhileLive;
    private final Clock clock;

    // Live updates
    private final Consumer<Event> liveSink;   // null = live updates off
    private final URI webSocketUri;
    private final String token;               // only ever sent to Home Assistant, never logged
    private final AtomicBoolean reconnectPending = new AtomicBoolean();
    private volatile boolean running;
    private volatile WebSocket socket;
    private HttpClient webSocketClient;
    private ScheduledExecutorService reconnector;
    private int backoffSeconds = 1;

    // Connection status: reachable while the last poll worked or the live connection is up.
    private final Consumer<HomeAssistantStatus> statusSink;
    private volatile boolean liveConnected;
    private boolean lastPollOk;
    private Boolean reportedReachable;   // null until the first report

    @Autowired
    public HomeAssistantConnector(
            HomeAssistantProperties properties,
            @Value("${homehub.connectors.homeassistant.include:" + DEFAULT_INCLUDE + "}") String include,
            @Value("${homehub.connectors.homeassistant.live:true}") boolean live,
            @Value("${homehub.connectors.homeassistant.poll-while-live:5m}") Duration pollWhileLive,
            EventRepository repository,
            ApplicationEventPublisher publisher) {
        this(RestClient.builder(), properties, include, live ? repository::saveIfChanged : null, publisher::publishEvent,
                pollWhileLive, Clock.systemUTC());
    }

    /** Lets tests supply a builder bound to a mock server and their own sink for live events. */
    HomeAssistantConnector(RestClient.Builder builder, HomeAssistantProperties properties, String include,
                           Consumer<Event> liveSink) {
        this(builder, properties, include, liveSink, status -> { });
    }

    /** Also lets tests see the connection status reports. Polls on every call, like before live updates. */
    HomeAssistantConnector(RestClient.Builder builder, HomeAssistantProperties properties, String include,
                           Consumer<Event> liveSink, Consumer<HomeAssistantStatus> statusSink) {
        this(builder, properties, include, liveSink, statusSink, Duration.ZERO, Clock.systemUTC());
    }

    /** Also lets tests set how often to poll while live updates work, and the clock. */
    HomeAssistantConnector(RestClient.Builder builder, HomeAssistantProperties properties, String include,
                           Consumer<Event> liveSink, Consumer<HomeAssistantStatus> statusSink,
                           Duration pollWhileLive, Clock clock) {
        this.configured = properties.isConfigured();
        this.include = EntityPatterns.parse(include);
        this.liveSink = liveSink;
        this.statusSink = statusSink;
        this.pollWhileLive = pollWhileLive == null ? Duration.ZERO : pollWhileLive;
        this.clock = clock;
        if (configured) {
            String base = properties.url().trim().replaceAll("/+$", "");
            this.token = properties.token().trim();
            this.webSocketUri = URI.create(base.replaceFirst("^http", "ws") + "/api/websocket");
            builder.baseUrl(base + "/api").defaultHeaders(h -> h.setBearerAuth(token));
        } else {
            this.token = "";
            this.webSocketUri = null;
        }
        this.client = builder.build();
    }

    @Override
    public String name() {
        return "homeassistant";
    }

    // ---------------------------------------------------------------- polling

    @Override
    public synchronized List<Event> poll() {
        if (!configured) {
            if (!missingConfigWarned) {
                log.warn("Home Assistant connector is enabled but homeassistant.url or homeassistant.token is not set; "
                        + "nothing will be polled. Set them in ~/.homehub/secrets.properties.");
                missingConfigWarned = true;
            }
            return List.of();
        }
        Instant pollStarted = clock.instant();
        if (liveConnected && lastPollAt != null && pollStarted.isBefore(lastPollAt.plus(pollWhileLive))) {
            // Live updates already bring every change the moment it happens; polling is only the safety net.
            return List.of();
        }
        boolean ok = false;
        try {
            List<Map<String, Object>> states = client.get().uri("/states").retrieve().body(STATES);
            List<Map<String, Object>> wanted = (states == null ? List.<Map<String, Object>>of() : states).stream()
                    .filter(s -> isIncluded(text(s.get("entity_id"))))
                    .toList();
            resolveDeviceNames(wanted.stream().map(s -> text(s.get("entity_id"))).toList());

            List<Event> changed = new ArrayList<>();
            for (Map<String, Object> state : wanted) {
                // A change since the previous poll keeps Home Assistant's change time. Anything else (the first poll,
                // or a value we just haven't seen) is stamped "now", so old Home Assistant times don't look like news.
                Instant changedAt = parseTime(state.get("last_changed"));
                Instant time = lastPollAt != null && changedAt != null && changedAt.isAfter(lastPollAt) ? changedAt : pollStarted;
                Event event = toEvent(state, time);
                if (!event.value().equals(lastValues.put(text(state.get("entity_id")), event.value()))) {
                    changed.add(event);
                }
            }
            lastPollAt = pollStarted;
            ok = true;
            return changed;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                log.warn("Home Assistant rejected the token (HTTP 401). Check homeassistant.token "
                        + "(Home Assistant: Profile > Security > Long-lived access tokens).");
            } else {
                log.warn("Home Assistant request failed: HTTP {}", ex.getStatusCode().value());
            }
        } catch (RuntimeException ex) {
            log.warn("Home Assistant is not reachable: {}", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            lastPollOk = ok;
            reportStatus();
        }
        return List.of();
    }

    /** Publishes a {@link HomeAssistantStatus} when reachability changed (and once at the start). */
    private synchronized void reportStatus() {
        boolean reachable = lastPollOk || liveConnected;
        if (reportedReachable != null && reportedReachable == reachable) return;
        reportedReachable = reachable;
        if (!reachable) log.warn("Home Assistant can't be reached (polling and live updates both failing)");
        statusSink.accept(new HomeAssistantStatus(reachable, Instant.now()));
    }

    /** The live connection went up or down. */
    private void liveConnection(boolean connected) {
        liveConnected = connected;
        if (running) reportStatus();   // not while shutting down
    }

    // ---------------------------------------------------------------- live updates (WebSocket)

    @Override
    public void start() {
        if (!configured || liveSink == null) return;
        running = true;
        webSocketClient = HttpClient.newHttpClient();
        reconnector = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "homeassistant-live");
            t.setDaemon(true);
            return t;
        });
        reconnector.execute(this::connect);
    }

    @Override
    public void stop() {
        running = false;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        if (reconnector != null) reconnector.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void connect() {
        if (!running) return;
        reconnectPending.set(false);
        webSocketClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(webSocketUri, new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        liveConnection(false);
                        log.warn("Home Assistant live updates: cannot connect ({}); retrying in {}s",
                                rootMessage(error), backoffSeconds);
                        scheduleReconnect(nextBackoff());
                    } else {
                        socket = ws;
                    }
                });
    }

    private int nextBackoff() {
        int delay = backoffSeconds;
        backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
        return delay;
    }

    private void scheduleReconnect(int delaySeconds) {
        if (!running || !reconnectPending.compareAndSet(false, true)) return;
        socket = null;
        reconnector.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

    /** Handles one complete WebSocket message. Package-private for tests. */
    void handleMessage(WebSocket ws, String text) {
        Map<String, Object> msg;
        try {
            msg = asMap(json.readValue(text, Map.class));
        } catch (RuntimeException ex) {
            log.debug("Home Assistant live updates: ignored a message that is not JSON");
            return;
        }
        switch (text(msg.get("type"))) {
            case "auth_required" -> ws.sendText(json.writeValueAsString(Map.of("type", "auth", "access_token", token)), true);
            case "auth_ok" -> {
                backoffSeconds = 1;
                ws.sendText(json.writeValueAsString(
                        Map.of("id", 1, "type", "subscribe_events", "event_type", "state_changed")), true);
                log.info("Home Assistant live updates connected");
                liveConnection(true);
            }
            case "auth_invalid" -> {
                log.warn("Home Assistant rejected the token for live updates. Check homeassistant.token; "
                        + "retrying in {} minutes.", AUTH_FAILED_RETRY_SECONDS / 60);
                liveConnection(false);
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "auth failed");
                scheduleReconnect(AUTH_FAILED_RETRY_SECONDS);
            }
            case "result" -> {
                if (Boolean.FALSE.equals(msg.get("success"))) {
                    log.warn("Home Assistant refused the live-updates subscription: {}",
                            text(asMap(msg.get("error")).get("message")));
                }
            }
            case "event" -> {
                Map<String, Object> data = asMap(asMap(msg.get("event")).get("data"));
                Map<String, Object> newState = asMap(data.get("new_state"));
                if (!newState.isEmpty() && isIncluded(text(data.get("entity_id")))) {
                    onStateChanged(newState);
                }
            }
            default -> { }
        }
    }

    private synchronized void onStateChanged(Map<String, Object> state) {
        String entityId = text(state.get("entity_id"));
        try {
            resolveDeviceNames(List.of(entityId));
        } catch (RuntimeException ex) {
            deviceNames.putIfAbsent(entityId, "");   // fall back to the friendly name; don't drop the change
        }
        Instant changedAt = parseTime(state.get("last_changed"));
        Event event = toEvent(state, changedAt != null ? changedAt : Instant.now());
        if (!event.value().equals(lastValues.put(entityId, event.value()))) {
            log.debug("Live: {} {} = {}", event.device(), event.type(), event.value());
            liveSink.accept(event);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handleMessage(ws, message);
                } catch (RuntimeException ex) {
                    log.warn("Home Assistant live updates: failed to handle a message ({})", ex.getClass().getSimpleName());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            liveConnection(false);
            if (running && !reconnectPending.get()) {
                log.warn("Home Assistant live updates disconnected (code {}); reconnecting in {}s", statusCode, backoffSeconds);
                scheduleReconnect(nextBackoff());
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            liveConnection(false);
            if (running && !reconnectPending.get()) {
                log.warn("Home Assistant live updates error ({}); reconnecting in {}s", rootMessage(error), backoffSeconds);
                scheduleReconnect(nextBackoff());
            }
        }
    }

    // ---------------------------------------------------------------- mapping

    private boolean isIncluded(String entityId) {
        return include.matches(entityId);
    }

    /**
     * Looks up the device name of entities we have not seen before, in one template request.
     * If that fails, events fall back to the entity's friendly name.
     */
    private void resolveDeviceNames(List<String> entityIds) {
        List<String> unknown = entityIds.stream().filter(id -> !deviceNames.containsKey(id)).toList();
        if (!unknown.isEmpty()) deviceNames.putAll(DeviceNames.lookup(client, unknown));
    }

    private Event toEvent(Map<String, Object> state, Instant time) {
        String entityId = text(state.get("entity_id"));
        String domain = entityId.substring(0, Math.max(0, entityId.indexOf('.')));
        Map<String, Object> attrs = asMap(state.get("attributes"));
        String deviceClass = text(attrs.get("device_class"));
        String friendly = firstNonBlank(text(attrs.get("friendly_name")), entityId);
        String device = deviceNames.getOrDefault(entityId, "");

        // "Refrigerator Fridge door" on device "Refrigerator" -> part "Fridge door"
        String part = !device.isEmpty() && friendly.toLowerCase(Locale.ROOT).startsWith(device.toLowerCase(Locale.ROOT))
                ? friendly.substring(device.length()).trim()
                : (device.isEmpty() ? "" : friendly);
        if (device.isEmpty()) device = friendly;

        String type = typeFor(domain, deviceClass, part);
        part = stripWords(part, deviceClass, type);   // "Fridge door" (contact) -> "Fridge"; "Machine state" -> ""
        String deviceLabel = part.isEmpty() ? device : device + "/" + part;

        return new Event(null, deviceLabel, type, valueFor(state, domain, deviceClass, attrs), time);
    }

    private static String typeFor(String domain, String deviceClass, String part) {
        if (CONTACT_CLASSES.contains(deviceClass)) return "contact";
        if ("temperature".equals(deviceClass)) return "temperature";
        if ("lock".equals(domain)) return "lock";
        String camel = toCamel(part);
        if (!camel.isEmpty()) return camel;
        return deviceClass.isEmpty() ? domain : deviceClass;
    }

    private static String valueFor(Map<String, Object> state, String domain, String deviceClass, Map<String, Object> attrs) {
        String value = text(state.get("state"));
        if ("binary_sensor".equals(domain) && CONTACT_CLASSES.contains(deviceClass)) {
            if ("on".equals(value)) return "open";
            if ("off".equals(value)) return "closed";
        }
        String unit = text(attrs.get("unit_of_measurement")).replace("°", "").trim();
        return unit.isEmpty() || "unavailable".equals(value) || "unknown".equals(value) ? value : value + " " + unit;
    }

    /** Removes words that only repeat the event type, e.g. "door" from "Fridge door" when the type is contact. */
    private static String stripWords(String part, String deviceClass, String type) {
        Set<String> redundant = new LinkedHashSet<>(List.of(deviceClass.replace('_', ' ').split(" ")));
        redundant.addAll(List.of(type.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split(" ")));
        return Arrays.stream(part.split("\\s+"))
                .filter(w -> !w.isBlank() && !redundant.contains(w.toLowerCase(Locale.ROOT)))
                .collect(Collectors.joining(" "))
                .trim();
    }

    /** "Machine state" -> "machineState" */
    private static String toCamel(String words) {
        StringBuilder sb = new StringBuilder();
        for (String p : words.trim().toLowerCase(Locale.ROOT).split("[\\s_]+")) {
            if (p.isEmpty()) continue;
            sb.append(sb.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        return sb.toString();
    }

    private static Instant parseTime(Object value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value.toString()).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
