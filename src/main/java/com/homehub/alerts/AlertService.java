package com.homehub.alerts;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.HomeAssistantStatus;
import com.homehub.weather.WeatherService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Watches every saved reading with {@link AlertRules}, checks the timers every few seconds, and for each alert:
 * stores it as an event (so it appears in the activity list and is pushed to open dashboards) and sends it to
 * phones through {@link AlertNotifier}.
 * <p>
 * All work happens on one background thread, so neither the code that saved a reading nor a slow Home Assistant
 * ever waits for an alert to go out.
 */
@Service
@ConditionalOnProperty(name = "homehub.alerts.enabled", havingValue = "true")
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    static final long CHECK_EVERY_SECONDS = 10;
    static final long PHONE_DELAY_AFTER_RECONNECT_SECONDS = 30;

    private final AlertRules rules;
    private final WeatherAlerts weatherAlerts;
    private final BackupAlerts backupAlerts;
    private final WeatherService weather;
    private final EventRepository repository;
    private final AlertNotifier notifier;
    private final Clock clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "alerts");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public AlertService(AlertProperties properties, BackupProperties backup, EventRepository repository,
                        AlertNotifier notifier, WeatherService weather) {
        // The hub's own time zone: weather checks run at local times such as 07:00.
        this(properties, backup, repository, notifier, weather, Clock.systemDefaultZone());
    }

    AlertService(AlertProperties properties, BackupProperties backup, EventRepository repository, AlertNotifier notifier,
                 WeatherService weather, Clock clock) {
        this.rules = new AlertRules(properties);
        this.weatherAlerts = new WeatherAlerts(properties, clock.getZone());
        this.backupAlerts = new BackupAlerts(backup, clock.instant(), clock.getZone());
        this.weather = weather;
        this.repository = repository;
        this.notifier = notifier;
        this.clock = clock;
        List<Event> latest = repository.latestPerReading();
        rules.seed(latest);           // a door left open before a restart is still watched
        weatherAlerts.seed(latest);   // and today's weather notices aren't sent twice
        backupAlerts.seed(latest);    // nor a missing backup that was already reported
        worker.scheduleWithFixedDelay(this::check, CHECK_EVERY_SECONDS, CHECK_EVERY_SECONDS, TimeUnit.SECONDS);
    }

    /** Called by Spring whenever {@link EventRepository} saves an event. */
    @EventListener
    public void onEventSaved(Event event) {
        if (Alert.EVENT_TYPES.contains(event.type())) return;   // our own alerts
        work(() -> raise(rules.onEvent(event)));
    }

    /** Called by Spring when the Home Assistant connector loses or regains Home Assistant. */
    @EventListener
    public void onConnection(HomeAssistantStatus status) {
        work(() -> raise(rules.onConnection(status.reachable(), status.time())));
    }

    private void work(Runnable task) {
        try {
            worker.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    log.warn("Alerts: failed to handle a change ({})", ex.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException ex) {
            // shutting down
        }
    }

    /** Problems that were reported and are still going on, for the dashboard banner. */
    public List<Alert> active() {
        List<Alert> active = new ArrayList<>(rules.active());
        active.addAll(backupAlerts.active());
        active.sort(Comparator.comparing(Alert::time));
        return active;
    }

    void check() {
        // An exception would cancel the repeating check, so catch everything.
        Instant now = clock.instant();
        try {
            raise(rules.check(now));
        } catch (RuntimeException ex) {
            log.warn("Alerts: check failed ({})", ex.getClass().getSimpleName());
        }
        try {
            // The forecast is only read around the daily check times; WeatherService keeps it for 10 minutes.
            if (weatherAlerts.due(now)) raise(weatherAlerts.check(now, weather.current().orElse(null)));
        } catch (RuntimeException ex) {
            log.warn("Alerts: weather check failed ({})", ex.getClass().getSimpleName());
        }
        try {
            raise(backupAlerts.check(now));
        } catch (RuntimeException ex) {
            log.warn("Alerts: backup check failed ({})", ex.getClass().getSimpleName());
        }
    }

    private void raise(List<Alert> alerts) {
        for (Alert alert : alerts) {
            log.info("Alert: {}", alert.message());
            repository.save(new Event(null, alert.device(), alert.level().eventType, alert.message(), alert.time()));
            if (!alert.reachesPhone()) continue;   // dashboard only
            if (Alert.CONNECTION_KEY.equals(alert.key())) {
                // Home Assistant just came back: give its phone notifications a moment to load before sending.
                worker.schedule(() -> notifier.send(alert), PHONE_DELAY_AFTER_RECONNECT_SECONDS, TimeUnit.SECONDS);
            } else {
                notifier.send(alert);
            }
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
