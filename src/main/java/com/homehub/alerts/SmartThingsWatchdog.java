package com.homehub.alerts;

import static com.homehub.homeassistant.JsonValues.text;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.DeviceNames;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keeps the Samsung appliances' feed alive. Home Assistant's SmartThings integration can stop receiving updates
 * without logging an error: the entities stay "available" with frozen values, so nothing looks wrong. Reloading the
 * integration fixes it.
 * <p>
 * Every few minutes this reads when {@code homehub.watchdog.entity} (an entity that reports every few minutes, such
 * as the fridge's power sensor) last reported to Home Assistant. When it has been silent for {@code stale-after}
 * (45 minutes), Kudil reloads that entity's integration ({@code homeassistant.reload_config_entry}) and notes it in
 * the activity list. Retried every {@code stale-after}; after {@value #RELOADS_BEFORE_ALERT} reloads that didn't help,
 * phones are alerted and retries continue hourly. When updates arrive again, that is noted too (and the phone alert,
 * if any, is cleared).
 * <p>
 * If Home Assistant itself can't be reached, nothing is done here: the connection alert covers that.
 */
@Service
@ConditionalOnProperty(name = "homehub.alerts.enabled", havingValue = "true")
public class SmartThingsWatchdog {

    private static final Logger log = LoggerFactory.getLogger(SmartThingsWatchdog.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};
    static final String DEVICE = "SmartThings";
    static final String KEY = "stale:smartthings";
    static final int RELOADS_BEFORE_ALERT = 2;
    static final Duration RETRY_AFTER_ALERT = Duration.ofHours(1);
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter EARLIER = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US);

    private final RestClient client;
    private final boolean enabled;
    private final String entity;
    private final Duration staleAfter;
    private final EventRepository repository;
    private final AlertNotifier notifier;
    private final Clock clock;

    private Instant silentSince;   // last report before the current silence; null while updates arrive
    private int reloads;
    private Instant lastReload;
    private String problem;        // the phone alert while it lasts

    @Autowired
    public SmartThingsWatchdog(HomeAssistantProperties homeAssistant, WatchdogProperties properties,
                               EventRepository repository, AlertNotifier notifier) {
        this(RestClient.builder().requestFactory(timeouts()), homeAssistant, properties, repository, notifier,
                Clock.systemDefaultZone());
    }

    SmartThingsWatchdog(RestClient.Builder builder, HomeAssistantProperties homeAssistant, WatchdogProperties properties,
                        EventRepository repository, AlertNotifier notifier, Clock clock) {
        this.entity = properties.entity();
        this.staleAfter = properties.staleAfter();
        this.repository = repository;
        this.notifier = notifier;
        this.clock = clock;
        boolean validEntity = DeviceNames.isSafeEntityId(entity);
        if (!entity.isEmpty() && !validEntity) log.warn("Ignoring homehub.watchdog.entity '{}': not an entity id", entity);
        this.enabled = validEntity && homeAssistant.isConfigured();
        if (enabled) {
            String token = homeAssistant.token().trim();
            builder.baseUrl(homeAssistant.url().trim().replaceAll("/+$", "") + "/api")
                    .defaultHeaders(h -> h.setBearerAuth(token));
        }
        this.client = builder.build();
        if (enabled) seed(repository.latestPerReading());
    }

    /** A phone alert sent before a restart is not sent again; it is cleared once updates arrive. */
    private void seed(List<Event> latestPerReading) {
        latestPerReading.stream()
                .filter(e -> DEVICE.equals(e.device()))
                .filter(e -> Level.PROBLEM.eventType.equals(e.type()) || Level.CLEARED.eventType.equals(e.type()))
                .max(Comparator.comparing(Event::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(e -> Level.PROBLEM.eventType.equals(e.type()))
                .ifPresent(e -> {
                    problem = e.value();
                    silentSince = e.time();
                    reloads = RELOADS_BEFORE_ALERT;
                });
    }

    @Scheduled(initialDelayString = "${homehub.watchdog.check-every:5m}", fixedDelayString = "${homehub.watchdog.check-every:5m}")
    public void run() {
        try {
            check();
        } catch (RuntimeException ex) {
            log.warn("Watchdog: check failed ({})", ex.getClass().getSimpleName());
        }
    }

    synchronized void check() {
        if (!enabled) return;
        Instant now = clock.instant();
        Instant lastReport;
        try {
            lastReport = lastReport();
        } catch (RestClientException ex) {
            return;   // Home Assistant itself is unreachable: the connection alert covers that
        }
        if (lastReport == null) return;

        Duration silent = Duration.between(lastReport, now);
        if (silent.compareTo(staleAfter) < 0) {
            recovered(now);
            return;
        }
        if (silentSince == null) silentSince = lastReport;
        Duration retry = problem != null ? RETRY_AFTER_ALERT : staleAfter;
        if (lastReload != null && now.isBefore(lastReload.plus(retry))) return;

        if (reloads >= RELOADS_BEFORE_ALERT && problem == null) {
            problem = "No updates from the Samsung appliances since " + when(silentSince)
                    + ". Reloading SmartThings didn't help: check it in Home Assistant (Settings › Devices & services).";
            raise(new Alert(KEY, Level.PROBLEM, DEVICE, problem, now), true);
        }
        String failure = reload();
        reloads++;
        lastReload = now;
        log.warn("Watchdog: {} silent for {}; {}", entity, AlertRules.human(silent),
                failure == null ? "reloaded its integration" : "reload failed: " + failure);
        if (reloads <= RELOADS_BEFORE_ALERT) {
            raise(new Alert(KEY, Level.NOTICE, DEVICE, "No updates from the Samsung appliances for "
                    + AlertRules.human(silent) + ". " + (failure == null
                    ? "Kudil reloaded SmartThings in Home Assistant."
                    : "Kudil couldn't reload SmartThings: " + failure + "."), now), false);
        }
    }

    private void recovered(Instant now) {
        if (silentSince == null) return;
        String message = "The Samsung appliances are sending updates again.";
        if (problem != null) raise(new Alert(KEY, Level.CLEARED, DEVICE, message, now), true);
        else if (reloads > 0) raise(new Alert(KEY, Level.NOTICE, DEVICE, message, now), false);
        silentSince = null;
        reloads = 0;
        lastReload = null;
        problem = null;
    }

    /** The phone alert while it lasts, for the dashboard banner. */
    synchronized List<Alert> active() {
        return problem == null ? List.of() : List.of(new Alert(KEY, Level.PROBLEM, DEVICE, problem, silentSince));
    }

    /** When the entity last reported to Home Assistant, even with an unchanged value. */
    private Instant lastReport() {
        Map<String, Object> state = client.get().uri("/states/{id}", entity).retrieve().body(JSON);
        if (state == null) return null;
        return Stream.of("last_reported", "last_updated")
                .map(field -> instant(state.get(field)))
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    /** Reloads the entity's integration. Returns null when done, otherwise why not. */
    private String reload() {
        try {
            client.post().uri("/services/homeassistant/reload_config_entry")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("entity_id", entity))
                    .retrieve().toBodilessEntity();
            return null;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            return "Home Assistant answered HTTP " + status + (status == 401 || status == 403 ? " (needs an admin token)" : "");
        } catch (RestClientException ex) {
            return "Home Assistant is not reachable";
        }
    }

    /** Stores the alert (activity list, live dashboards) and, if asked, sends it to phones. */
    private void raise(Alert alert, boolean toPhones) {
        log.info("Alert: {}", alert.message());
        repository.save(new Event(null, alert.device(), alert.level().eventType, alert.message(), alert.time()));
        if (toPhones) notifier.send(alert);
    }

    private String when(Instant time) {
        ZoneId zone = clock.getZone();
        boolean today = time.atZone(zone).toLocalDate().equals(clock.instant().atZone(zone).toLocalDate());
        return (today ? TODAY : EARLIER).format(time.atZone(zone));
    }

    private static Instant instant(Object value) {
        String text = text(value);
        if (text == null || text.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static JdkClientHttpRequestFactory timeouts() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));   // a reload waits for the integration to start again
        return factory;
    }
}
