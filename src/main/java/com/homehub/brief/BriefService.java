package com.homehub.brief;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.weather.WarningService;
import com.homehub.weather.WeatherService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the Daily Home Brief (#21) once a day at {@code homehub.brief.at} (hub time, normally 20:00): to phones as a
 * quiet notice, and into the activity list. If Kudil was restarting at that time, it goes out as soon as it is back,
 * the same evening; never twice in a day.
 */
@Service
public class BriefService {

    private static final Logger log = LoggerFactory.getLogger(BriefService.class);
    /** The device name the brief is stored under; also how Kudil tells it was sent today. */
    static final String DEVICE = "Daily brief";

    private final EventRepository events;
    private final WeatherService weather;
    private final WarningService warnings;
    private final ObjectProvider<AlertService> alerts;
    private final boolean enabled;
    private final LocalTime at;
    private final Clock clock;

    @Autowired
    public BriefService(EventRepository events, WeatherService weather, WarningService warnings,
                        ObjectProvider<AlertService> alerts,
                        @Value("${homehub.brief.enabled:true}") boolean enabled,
                        @Value("${homehub.brief.at:20:00}") LocalTime at) {
        this(events, weather, warnings, alerts, enabled, at, Clock.systemDefaultZone());
    }

    BriefService(EventRepository events, WeatherService weather, WarningService warnings,
                 ObjectProvider<AlertService> alerts, boolean enabled, LocalTime at, Clock clock) {
        this.events = events;
        this.weather = weather;
        this.warnings = warnings;
        this.alerts = alerts;
        this.enabled = enabled;
        this.at = at;
        this.clock = clock;
    }

    public LocalTime at() {
        return at;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Today's brief so far, for the dashboard's preview. */
    public String preview() {
        return String.join("\n", lines());
    }

    @Scheduled(cron = "0 * * * * *")   // every minute: on time, and soon after a restart that missed it
    public void sendIfDue() {
        if (!enabled) return;
        try {
            ZonedDateTime now = ZonedDateTime.now(clock);
            if (now.toLocalTime().isBefore(at) || sentToday(now)) return;
            AlertService service = alerts.getIfAvailable();
            if (service == null) return;
            String text = preview();
            service.report(List.of(new Alert("brief:" + now.toLocalDate(), Alert.Level.NOTICE, DEVICE, text, now.toInstant())));
            log.info("Daily brief sent");
        } catch (RuntimeException ex) {
            log.warn("Daily brief failed ({}); trying again in a minute", ex.getClass().getSimpleName());
        }
    }

    private List<String> lines() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        Instant midnight = today.atStartOfDay(clock.getZone()).toInstant();
        List<Event> day = events.between(midnight, now.toInstant().plusSeconds(1)).stream()
                .filter(e -> !DEVICE.equals(e.device())).toList();
        return DailyBrief.lines(day, weather.current().orElse(null), warnings.status().warnings(), today, clock.getZone());
    }

    private boolean sentToday(ZonedDateTime now) {
        Instant midnight = now.toLocalDate().atStartOfDay(clock.getZone()).toInstant();
        return events.between(midnight, now.toInstant().plusSeconds(1)).stream()
                .anyMatch(e -> DEVICE.equals(e.device()) && "notice".equals(e.type()));
    }
}
