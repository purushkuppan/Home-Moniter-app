package com.homehub.weather;

import static com.homehub.homeassistant.JsonValues.text;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Official weather warnings for the home (#106), for any household: the home's location and country come from Home
 * Assistant ({@code GET /api/config}), and the country picks the {@link WarningSource} (the US: the National Weather
 * Service). Only the location rounded to about 1 km leaves the house.
 * <p>
 * Every few minutes it asks for the warnings in force. A new one becomes an alert (critical ones ring like a leak),
 * one per kind of warning while it lasts, however often the service updates it; a warning that ends gets an all
 * clear. The weather card shows what is in force ({@link #status()}).
 */
@Service
public class WarningService {

    private static final Logger log = LoggerFactory.getLogger(WarningService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};
    /** The home's location changes rarely: read it again this often. */
    static final Duration LOCATION_KEPT = Duration.ofHours(6);

    /** What the weather card shows. */
    public record Status(String source, String note, List<WeatherWarning> warnings) { }

    /** A warning that was reported, so it isn't again while it lasts. */
    private record Reported(String key, Alert.Level level, String device, String message, Instant since) { }

    private record Home(double latitude, double longitude, String country) { }

    private final RestClient homeAssistant;
    private final boolean configured;
    private final boolean enabled;
    private final List<WarningSource> sources;
    private final ObjectProvider<AlertService> alerts;
    private final EventRepository events;
    private final Clock clock;

    private Home home;
    private Instant homeReadAt;
    private boolean seeded;
    private final Map<String, Reported> reported = new LinkedHashMap<>();   // by kind of warning ("tornado-warning")
    private volatile Status status = new Status(null, null, List.of());

    @Autowired
    public WarningService(HomeAssistantProperties properties, List<WarningSource> sources, ObjectProvider<AlertService> alerts,
                          EventRepository events, @Value("${homehub.warnings.enabled:true}") boolean enabled) {
        this(RestClient.builder(), properties, sources, alerts, events, enabled, Clock.systemDefaultZone());
    }

    WarningService(RestClient.Builder builder, HomeAssistantProperties properties, List<WarningSource> sources,
                   ObjectProvider<AlertService> alerts, EventRepository events, boolean enabled, Clock clock) {
        this.configured = properties.isConfigured();
        if (configured) {
            String token = properties.token().trim();
            builder.baseUrl(properties.url().trim().replaceAll("/+$", "") + "/api").defaultHeaders(h -> h.setBearerAuth(token));
        }
        this.homeAssistant = builder.build();
        this.enabled = enabled;
        this.sources = List.copyOf(sources);
        this.alerts = alerts;
        this.events = events;
        this.clock = clock;
    }

    public Status status() {
        return status;
    }

    /** Warnings going on now that the household was alerted to, for the dashboard's alert list. */
    public synchronized List<Alert> active() {
        List<Alert> active = new ArrayList<>();
        reported.values().forEach(r -> {
            if (r.level() == Alert.Level.PROBLEM) active.add(new Alert(r.key(), r.level(), r.device(), r.message(), r.since()));
        });
        return active;
    }

    @Scheduled(fixedDelayString = "${homehub.warnings.check-every:3m}", initialDelayString = "${homehub.warnings.first-check:20s}")
    public void scheduledCheck() {
        if (!enabled || !configured) return;
        try {
            check();
        } catch (RuntimeException ex) {
            log.warn("Weather warnings: check failed ({}); trying again soon", ex.getClass().getSimpleName());
        }
    }

    /** Reads the warnings in force and raises or clears alerts. Package-private for tests. */
    synchronized void check() {
        Instant now = clock.instant();
        if (home == null || homeReadAt == null || !now.isBefore(homeReadAt.plus(LOCATION_KEPT))) {
            home = readHome();
            homeReadAt = now;
        }
        if (home == null) {
            status = new Status(null, "Set the home's location in Home Assistant (Settings > System > General) "
                    + "for official weather warnings.", List.of());
            return;
        }
        Optional<WarningSource> source = sources.stream().filter(s -> s.covers(home.country())).findFirst();
        if (source.isEmpty()) {
            status = new Status(null, "No official weather warnings for " + (home.country().isEmpty() ? "this country"
                    : home.country()) + " yet.", List.of());
            return;
        }
        // About 1 km: enough to find the county, no closer to the house.
        double lat = Math.round(home.latitude() * 100) / 100.0, lon = Math.round(home.longitude() * 100) / 100.0;
        List<WeatherWarning> warnings = source.get().active(lat, lon);
        status = new Status(source.get().name(), null, List.copyOf(warnings));
        if (!seeded) seed(now);

        // One alert per kind of warning while it lasts: the service sends updates (a later end time) as new warnings.
        Map<String, WeatherWarning> byKind = new LinkedHashMap<>();
        warnings.forEach(w -> byKind.merge(slug(w.event()), w, (a, b) -> later(a, b)));
        List<Alert> raise = new ArrayList<>();
        byKind.forEach((kind, w) -> {
            if (reported.containsKey(kind)) return;
            Alert alert = new Alert(key(w), w.level() == WeatherWarning.Level.NOTICE ? Alert.Level.NOTICE : Alert.Level.PROBLEM,
                    w.event(), message(w), now);
            reported.put(kind, new Reported(alert.key(), alert.level(), alert.device(), alert.message(), now));
            raise.add(alert);
        });
        for (var it = reported.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (byKind.containsKey(entry.getKey())) continue;
            Reported r = entry.getValue();
            it.remove();
            if (r.level() == Alert.Level.PROBLEM) {
                raise.add(new Alert(r.key(), Alert.Level.CLEARED, r.device(), r.device() + " has ended.", now));
            }
        }
        if (!raise.isEmpty()) alerts.ifAvailable(a -> a.report(raise));
    }

    /**
     * After a restart: warnings reported in the last day and not ended since (their newest alert or notice is newer
     * than their newest all clear) are not reported again.
     */
    private void seed(Instant now) {
        seeded = true;
        Map<String, Event> lastReport = new HashMap<>(), lastClear = new HashMap<>();
        for (Event e : events.latestPerReading()) {
            if (e.time() == null || e.time().isBefore(now.minus(Duration.ofDays(1))) || !isWarningName(e.device())) continue;
            switch (e.type()) {
                case "alert", "notice" -> lastReport.merge(e.device(), e, (a, b) -> a.time().isAfter(b.time()) ? a : b);
                case "alertCleared" -> lastClear.put(e.device(), e);
                default -> { }
            }
        }
        lastReport.forEach((device, e) -> {
            Event cleared = lastClear.get(device);
            if (cleared != null && !cleared.time().isBefore(e.time())) return;
            Alert.Level level = "alert".equals(e.type()) ? Alert.Level.PROBLEM : Alert.Level.NOTICE;
            WeatherWarning.Level kind = NwsWarningSource.levelOf(device);
            reported.put(slug(device), new Reported(key(kind, device), level, device, e.value(), e.time()));
        });
    }

    private Home readHome() {
        try {
            Map<String, Object> config = homeAssistant.get().uri("/config").retrieve().body(JSON);
            if (config == null || !(config.get("latitude") instanceof Number lat) || !(config.get("longitude") instanceof Number lon)
                    || (lat.doubleValue() == 0 && lon.doubleValue() == 0)) {
                return null;
            }
            return new Home(lat.doubleValue(), lon.doubleValue(), text(config.get("country")).toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            log.warn("Weather warnings: can't read the home's location from Home Assistant ({})", ex.getClass().getSimpleName());
            return home;   // keep the last one
        }
    }

    /** "Tornado Warning for the house until 7:45 PM. Go to your safe room now." */
    String message(WeatherWarning w) {
        StringBuilder m = new StringBuilder(w.event()).append(" for the house");
        if (w.ends() != null) m.append(" until ").append(when(w.ends()));
        m.append('.');
        String advice = switch (w.event()) {
            case "Tornado Warning", "Tornado Emergency" -> "Go to your safe room now.";
            case "Severe Thunderstorm Warning" -> "Stay inside, away from windows.";
            case "Flash Flood Warning", "Flash Flood Emergency" -> "Never drive through flooded roads.";
            case "Extreme Wind Warning" -> "Take shelter in an inside room.";
            case "Hurricane Warning", "Storm Surge Warning" -> "Follow local evacuation orders.";
            default -> "";
        };
        if (!advice.isEmpty()) m.append(' ').append(advice);
        if (w.hailInches() != null) {
            m.append(" Hail up to ").append(String.format(Locale.ROOT, "%.2f", w.hailInches()).replaceAll("0+$", "").replaceAll("\\.$", ""))
                    .append(" in: move the cars into the garage.");
        }
        return m.toString();
    }

    /** "7:45 PM" today, "Fri 6:00 PM" otherwise, in the hub's time zone. */
    private String when(Instant time) {
        ZonedDateTime local = time.atZone(clock.getZone());
        boolean today = local.toLocalDate().equals(LocalDate.ofInstant(clock.instant(), clock.getZone()));
        return local.format(DateTimeFormatter.ofPattern(today ? "h:mm a" : "EEE h:mm a", Locale.US));
    }

    private static String key(WeatherWarning w) {
        return key(w.level(), w.event());
    }

    /** Critical warnings are "severe:" (critical kinds, #101); the rest "weather:". */
    private static String key(WeatherWarning.Level level, String event) {
        return (level == WeatherWarning.Level.CRITICAL ? "severe:" : "weather:") + slug(event);
    }

    static String slug(String event) {
        return event.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static boolean isWarningName(String device) {
        return device != null && device.matches(".*\\b(Warning|Watch|Advisory|Statement|Emergency)$");
    }

    private static WeatherWarning later(WeatherWarning a, WeatherWarning b) {
        if (a.ends() == null) return a;
        if (b.ends() == null) return b;
        return b.ends().isAfter(a.ends()) ? b : a;
    }
}
