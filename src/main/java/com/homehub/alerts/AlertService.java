package com.homehub.alerts;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
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

    private final AlertRules rules;
    private final EventRepository repository;
    private final AlertNotifier notifier;
    private final Clock clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "alerts");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public AlertService(AlertProperties properties, EventRepository repository, AlertNotifier notifier) {
        this(properties, repository, notifier, Clock.systemUTC());
    }

    AlertService(AlertProperties properties, EventRepository repository, AlertNotifier notifier, Clock clock) {
        this.rules = new AlertRules(properties);
        this.repository = repository;
        this.notifier = notifier;
        this.clock = clock;
        rules.seed(repository.latestPerReading());   // a door left open before a restart is still watched
        worker.scheduleWithFixedDelay(this::check, CHECK_EVERY_SECONDS, CHECK_EVERY_SECONDS, TimeUnit.SECONDS);
    }

    /** Called by Spring whenever {@link EventRepository} saves an event. */
    @EventListener
    public void onEventSaved(Event event) {
        if (Alert.EVENT_TYPES.contains(event.type())) return;   // our own alerts
        worker.execute(() -> {
            try {
                raise(rules.onEvent(event));
            } catch (RuntimeException ex) {
                log.warn("Alerts: failed to handle an event ({})", ex.getClass().getSimpleName());
            }
        });
    }

    /** Problems that were reported and are still going on, for the dashboard banner. */
    public List<Alert> active() {
        return rules.active();
    }

    void check() {
        try {
            raise(rules.check(clock.instant()));
        } catch (RuntimeException ex) {
            // An exception would cancel the repeating check, so catch everything.
            log.warn("Alerts: check failed ({})", ex.getClass().getSimpleName());
        }
    }

    private void raise(List<Alert> alerts) {
        for (Alert alert : alerts) {
            log.info("Alert: {}", alert.message());
            repository.save(new Event(null, alert.device(), alert.level().eventType, alert.message(), alert.time()));
            notifier.send(alert);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
