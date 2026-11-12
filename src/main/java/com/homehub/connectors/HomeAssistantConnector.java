package com.homehub.connectors;

import static com.homehub.homeassistant.JsonValues.asList;
import static com.homehub.homeassistant.JsonValues.asMap;
import static com.homehub.homeassistant.JsonValues.firstNonBlank;
import static com.homehub.homeassistant.JsonValues.text;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.devices.DeviceCatalog;
import com.homehub.homeassistant.DeviceNames;
import com.homehub.homeassistant.EntityDirectory;
import com.homehub.homeassistant.EntityNames;
import com.homehub.homeassistant.EntityPatterns;
import com.homehub.homeassistant.HomeAssistantProperties;
import com.homehub.homeassistant.HomeAssistantStatus;
import com.homehub.homeassistant.JsonValues;
import com.homehub.homeassistant.NotificationAction;
import com.homehub.homeassistant.RegistrySnapshot;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
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
 * recorded when the value changed. "Unavailable" is held back for {@code unavailable-grace} (2 minutes), so a SmartThings
 * reload that drops every entity for a few seconds leaves nothing behind. The URL and token come from {@link HomeAssistantProperties}; the token is never logged.
 */
@Component
@ConditionalOnProperty(name = "homehub.connectors.homeassistant.enabled", havingValue = "true")
public class HomeAssistantConnector implements DeviceConnector, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantConnector.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> STATES = new ParameterizedTypeReference<>() {};
    private static final Set<String> CONTACT_CLASSES = Set.of("door", "window", "opening", "garage_door");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)");
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
    /**
     * How long an "unavailable" (or "unknown") reading is held back before it is stored. A SmartThings reload makes every
     * Samsung entity unavailable for a few seconds; a reading that comes back within this time is never recorded.
     */
    private final Duration unavailableGrace;
    /** Unavailable readings waiting out the grace period, per entity id (insertion order = oldest first). */
    private final Map<String, Held> held = new LinkedHashMap<>();

    private record Held(Event event, Instant since) { }

    /** Dashboard names configured for single entities; they win over the Home Assistant device name. */
    private final EntityNames names;
    /**
     * Power (watts) is only stored when it changed by at least this much, or went to or from zero, so a plug whose load
     * wobbles by a watt or two doesn't fill the activity list.
     */
    static final double POWER_STEP_WATTS = 5;

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
    /** Taps on phone notification buttons; published as Spring events in the running app. */
    private Consumer<NotificationAction> actionSink = action -> { };
    static final String NOTIFICATION_ACTION = "mobile_app_notification_action";

    // Devices from Home Assistant's registries (#99)
    private volatile EntityDirectory directory = EntityDirectory.NONE;
    static final List<String> REGISTRY_UPDATES =
            List.of("area_registry_updated", "device_registry_updated", "entity_registry_updated");
    private static final List<String> REGISTRY_LISTS =
            List.of("config/area_registry/list", "config/device_registry/list", "config/entity_registry/list");
    /** Message ids must increase on each connection; ids 1 and 2 are the first two subscriptions. */
    private final AtomicInteger messageIds = new AtomicInteger();
    /** A registry read in progress: the answers to the three list commands, by command. */
    private final Map<Integer, String> registryRequests = new HashMap<>();
    private final Map<String, List<Map<String, Object>>> registryAnswers = new HashMap<>();
    private final AtomicBoolean registryReadPending = new AtomicBoolean();
    static final int REGISTRY_READ_DELAY_SECONDS = 5;   // Home Assistant sends a burst of updates while pairing
    private volatile boolean liveConnected;
    private boolean lastPollOk;
    private Boolean reportedReachable;   // null until the first report

    @Autowired
    public HomeAssistantConnector(
            HomeAssistantProperties properties,
            @Value("${homehub.connectors.homeassistant.include:" + DEFAULT_INCLUDE + "}") String include,
            @Value("${homehub.connectors.homeassistant.live:true}") boolean live,
            @Value("${homehub.connectors.homeassistant.poll-while-live:5m}") Duration pollWhileLive,
            @Value("${homehub.connectors.homeassistant.unavailable-grace:2m}") Duration unavailableGrace,
            EntityNames names,
            EntityDirectory directory,
            EventRepository repository,
            ApplicationEventPublisher publisher) {
        this(RestClient.builder(), properties, include, live ? repository::saveIfChanged : null, publisher::publishEvent,
                pollWhileLive, Clock.systemUTC(), unavailableGrace, names);
        this.actionSink = publisher::publishEvent;
        this.directory = directory;
    }

    /** Lets tests use a device directory (#99). */
    void useDirectory(EntityDirectory directory) {
        this.directory = directory;
    }

    /** The household confirmed or hid a device: read every state again on the next scheduler run (#99). */
    @EventListener
    public synchronized void onDevicesChanged(DeviceCatalog.DevicesChanged changed) {
        lastPollAt = null;
    }

    /** Lets tests see taps on phone notification buttons. */
    void onNotificationAction(Consumer<NotificationAction> sink) {
        this.actionSink = sink;
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

    /** Also lets tests set how often to poll while live updates work, and the clock. Unavailable is stored at once. */
    HomeAssistantConnector(RestClient.Builder builder, HomeAssistantProperties properties, String include,
                           Consumer<Event> liveSink, Consumer<HomeAssistantStatus> statusSink,
                           Duration pollWhileLive, Clock clock) {
        this(builder, properties, include, liveSink, statusSink, pollWhileLive, clock, Duration.ZERO);
    }

    /** Also lets tests set how long "unavailable" is held back. */
    HomeAssistantConnector(RestClient.Builder builder, HomeAssistantProperties properties, String include,
                           Consumer<Event> liveSink, Consumer<HomeAssistantStatus> statusSink,
                           Duration pollWhileLive, Clock clock, Duration unavailableGrace) {
        this(builder, properties, include, liveSink, statusSink, pollWhileLive, clock, unavailableGrace, EntityNames.none());
    }

    /** Also lets tests set dashboard names for single entities. */
    HomeAssistantConnector(RestClient.Builder builder, HomeAssistantProperties properties, String include,
                           Consumer<Event> liveSink, Consumer<HomeAssistantStatus> statusSink,
                           Duration pollWhileLive, Clock clock, Duration unavailableGrace, EntityNames names) {
        this.names = names == null ? EntityNames.none() : names;
        this.configured = properties.isConfigured();
        this.include = EntityPatterns.parse(include);
        this.liveSink = liveSink;
        this.statusSink = statusSink;
        this.pollWhileLive = pollWhileLive == null ? Duration.ZERO : pollWhileLive;
        this.clock = clock;
        this.unavailableGrace = unavailableGrace == null || unavailableGrace.isNegative() ? Duration.ZERO : unavailableGrace;
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
        List<Event> due = releaseHeld(pollStarted);   // readings that stayed unavailable past the grace period
        if (liveConnected && lastPollAt != null && pollStarted.isBefore(lastPollAt.plus(pollWhileLive))) {
            // Live updates already bring every change the moment it happens; polling is only the safety net.
            return due;
        }
        boolean ok = false;
        try {
            List<Map<String, Object>> states = client.get().uri("/states").retrieve().body(STATES);
            List<Map<String, Object>> wanted = (states == null ? List.<Map<String, Object>>of() : states).stream()
                    .filter(s -> isIncluded(text(s.get("entity_id"))))
                    .toList();
            resolveDeviceNames(wanted.stream().map(s -> text(s.get("entity_id"))).toList());

            List<Event> changed = new ArrayList<>(due);
            for (Map<String, Object> state : wanted) {
                // A change since the previous poll keeps Home Assistant's change time. Anything else (the first poll,
                // or a value we just haven't seen) is stamped "now", so old Home Assistant times don't look like news.
                Instant changedAt = parseTime(state.get("last_changed"));
                Instant time = lastPollAt != null && changedAt != null && changedAt.isAfter(lastPollAt) ? changedAt : pollStarted;
                Event event = toEvent(state, time);
                if (record(text(state.get("entity_id")), event, pollStarted)) changed.add(event);
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
        return due;
    }

    /**
     * Remembers a reading and says whether to store it now. An "unavailable" or "unknown" reading is held for the grace
     * period first: if a real value comes back in time it is dropped, and a value equal to the one before is no change.
     */
    private boolean record(String entityId, Event event, Instant now) {
        if (!unavailableGrace.isZero() && isUnavailable(event.value())) {
            if (!event.value().equals(lastValues.get(entityId))) held.putIfAbsent(entityId, new Held(event, now));
            return false;
        }
        if (held.remove(entityId) != null) log.debug("{} was briefly unavailable; not recorded", event.device());
        String previous = lastValues.get(entityId);
        if (event.value().equals(previous)) return false;
        if ("power".equals(event.type()) && smallPowerChange(previous, event.value())) return false;   // keep the old reference
        lastValues.put(entityId, event.value());
        return true;
    }

    /** The held readings whose grace period is over: they are stored with the time they became unavailable. */
    private List<Event> releaseHeld(Instant now) {
        List<Event> due = new ArrayList<>();
        for (Iterator<Map.Entry<String, Held>> it = held.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Held> entry = it.next();
            Held h = entry.getValue();
            if (now.isBefore(h.since().plus(unavailableGrace))) continue;
            it.remove();
            if (!h.event().value().equals(lastValues.put(entry.getKey(), h.event().value()))) due.add(h.event());
        }
        return due;
    }

    private static boolean isUnavailable(String value) {
        return "unavailable".equals(value) || "unknown".equals(value);
    }

    /** "12.4 W" after "10 W": too small to store. Anything that isn't two numbers is a real change. */
    private static boolean smallPowerChange(String previous, String next) {
        Double a = leadingNumber(previous), b = leadingNumber(next);
        if (a == null || b == null) return false;
        if ((a == 0) != (b == 0)) return false;   // switched on or off
        return Math.abs(a - b) < POWER_STEP_WATTS;
    }

    private static Double leadingNumber(String value) {
        if (value == null) return null;
        Matcher m = LEADING_NUMBER.matcher(value);
        return m.find() ? Double.valueOf(m.group(1)) : null;
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
                messageIds.set(0);
                send(ws, Map.of("id", messageIds.incrementAndGet(), "type", "subscribe_events", "event_type", "state_changed"));
                // Buttons tapped on phone notifications, e.g. "I'm on it" on a critical alert (#101).
                send(ws, Map.of("id", messageIds.incrementAndGet(), "type", "subscribe_events", "event_type", NOTIFICATION_ACTION));
                if (directory != EntityDirectory.NONE) {
                    // Devices, areas and entities (#99): read now, and again whenever one of them changes.
                    for (String update : REGISTRY_UPDATES) {
                        send(ws, Map.of("id", messageIds.incrementAndGet(), "type", "subscribe_events", "event_type", update));
                    }
                    readRegistries(ws);
                }
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
                Object id = msg.get("id");
                String list = id instanceof Number n ? registryRequest(n.intValue()) : null;
                if (Boolean.FALSE.equals(msg.get("success"))) {
                    log.warn("Home Assistant refused {}: {}", list != null ? list : "the live-updates subscription",
                            text(asMap(msg.get("error")).get("message")));
                    if (list != null) registryFailed();
                } else if (list != null) {
                    registryAnswer(list, msg.get("result"));
                }
            }
            case "event" -> {
                Map<String, Object> event = asMap(msg.get("event"));
                Map<String, Object> data = asMap(event.get("data"));
                String eventType = text(event.get("event_type"));
                if (NOTIFICATION_ACTION.equals(eventType)) {
                    String action = text(data.get("action"));
                    if (!action.isEmpty()) actionSink.accept(new NotificationAction(action, Instant.now()));
                    return;
                }
                if (REGISTRY_UPDATES.contains(eventType)) {
                    registryChanged(ws);
                    return;
                }
                Map<String, Object> newState = asMap(data.get("new_state"));
                if (!newState.isEmpty() && isIncluded(text(data.get("entity_id")))) {
                    onStateChanged(newState);
                }
            }
            default -> { }
        }
    }

    /** Sends one message, after the previous one went out (a WebSocket sends one message at a time). */
    private void send(WebSocket ws, Map<String, Object> message) {
        String text = json.writeValueAsString(message);
        synchronized (sendLock) {
            lastSend = lastSend.handle((done, error) -> null).thenCompose(x -> {
                CompletableFuture<WebSocket> sent = ws.sendText(text, true);
                return sent != null ? sent : CompletableFuture.completedFuture(ws);
            });
        }
    }

    private final Object sendLock = new Object();
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);

    // ---------------------------------------------------------------- device registries (#99)

    /** Asks for the area, device and entity registries; {@link #registryAnswer} collects the three answers. */
    private void readRegistries(WebSocket ws) {
        synchronized (registryRequests) {
            registryRequests.clear();
            registryAnswers.clear();
            for (String list : REGISTRY_LISTS) {
                int id = messageIds.incrementAndGet();
                registryRequests.put(id, list);
                send(ws, Map.of("id", id, "type", list));
            }
        }
    }

    /** The registry list a result answers, or null for anything else (such as a subscription). */
    private String registryRequest(int id) {
        synchronized (registryRequests) {
            return registryRequests.remove(id);
        }
    }

    private void registryFailed() {
        synchronized (registryRequests) {
            registryRequests.clear();
            registryAnswers.clear();
        }
    }

    private void registryAnswer(String list, Object result) {
        List<Map<String, Object>> rows = asList(result).stream().map(JsonValues::asMap).toList();
        Map<String, List<Map<String, Object>>> complete = null;
        synchronized (registryRequests) {
            registryAnswers.put(list, rows);
            if (registryAnswers.size() == REGISTRY_LISTS.size()) {
                complete = new HashMap<>(registryAnswers);
                registryAnswers.clear();
            }
        }
        if (complete != null) {
            Map<String, List<Map<String, Object>>> lists = complete;
            inBackground(() -> syncDevices(lists));   // not on the WebSocket's thread: it reads the states too
        }
    }

    /** With the three registries in, adds the states (for each entity's device class) and hands it all over. */
    private void syncDevices(Map<String, List<Map<String, Object>>> lists) {
        try {
            List<Map<String, Object>> states = client.get().uri("/states").retrieve().body(STATES);
            directory.sync(new RegistrySnapshot(lists.get(REGISTRY_LISTS.get(0)), lists.get(REGISTRY_LISTS.get(1)),
                    lists.get(REGISTRY_LISTS.get(2)), states == null ? List.of() : states));
        } catch (RuntimeException ex) {
            log.warn("Home Assistant devices: could not read them ({}); trying again at the next change or reconnect",
                    ex.getClass().getSimpleName());
        }
    }

    /** A device, area or entity changed in Home Assistant: read the registries again, once the burst is over. */
    private void registryChanged(WebSocket ws) {
        if (directory == EntityDirectory.NONE || !registryReadPending.compareAndSet(false, true)) return;
        Runnable read = () -> {
            registryReadPending.set(false);
            WebSocket current = socket != null ? socket : ws;   // after a reconnect, the new connection
            if (running || reconnector == null) readRegistries(current);
        };
        if (reconnector == null) read.run();   // tests
        else reconnector.schedule(read, REGISTRY_READ_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void inBackground(Runnable task) {
        if (reconnector == null) task.run();   // tests
        else reconnector.execute(task);
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
        if (record(entityId, event, clock.instant())) {
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

    /** Recorded: matched by an include pattern or a reading of a confirmed device (#99), and not of a hidden device. */
    private boolean isIncluded(String entityId) {
        return !directory.hidden(entityId) && (include.matches(entityId) || directory.records(entityId));
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
        String device = deviceNames.getOrDefault(entityId, "");   // Home Assistant's device name

        // "Refrigerator Fridge door" on device "Refrigerator" -> part "Fridge door"
        String part = !device.isEmpty() && friendly.toLowerCase(Locale.ROOT).startsWith(device.toLowerCase(Locale.ROOT))
                ? friendly.substring(device.length()).trim()
                : (device.isEmpty() ? "" : friendly);
        if (device.isEmpty()) device = friendly;

        String type = typeFor(domain, deviceClass, part);
        part = stripWords(part, deviceClass, type);   // "Fridge door" (contact) -> "Fridge"; "Machine state" -> ""
        // A device Kudil knows (#99) is recorded under Kudil's name, and its only reading of a kind needs no part:
        // "Laundry leak sensor", not "Laundry leak sensor/Water detected".
        device = directory.deviceName(entityId).orElse(device);
        if (directory.onlyReadingOfItsKind(entityId)) part = "";
        String deviceLabel = part.isEmpty() ? device : device + "/" + part;
        String configured = names.nameOf(entityId);   // e.g. "Back door" instead of "Backdoor openclose"
        if (configured != null) deviceLabel = configured;

        return new Event(null, deviceLabel, type, valueFor(state, domain, deviceClass, attrs), time);
    }

    private static String typeFor(String domain, String deviceClass, String part) {
        if (CONTACT_CLASSES.contains(deviceClass)) return "contact";
        if ("temperature".equals(deviceClass)) return "temperature";
        if ("moisture".equals(deviceClass)) return "leak";   // water leak sensors: wet / dry
        if ("power".equals(deviceClass)) return "power";     // watts, e.g. a smart plug's load
        if ("battery".equals(deviceClass)) return "battery"; // "Battery level" (Z-Wave) as well as "Battery"
        if ("lock".equals(domain)) return "lock";
        if ("switch".equals(domain)) return "switch";        // on / off, e.g. a smart plug
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
        if ("binary_sensor".equals(domain) && "moisture".equals(deviceClass)) {
            if ("on".equals(value)) return "wet";
            if ("off".equals(value)) return "dry";
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
