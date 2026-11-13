package com.homehub.alerts;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.HomeAssistantStatus;
import com.homehub.homeassistant.NotificationAction;
import com.homehub.weather.WeatherService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
    private final CriticalAlerts critical;
    private final List<String> escalateTo;
    private final Clock clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "alerts");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public AlertService(AlertProperties properties, BackupProperties backup, CriticalAlertProperties criticalSettings,
                        DefaultAlertProperties defaults, DeviceAlertSettings deviceSettings,
                        EventRepository repository, AlertNotifier notifier, WeatherService weather) {
        // The hub's own time zone: weather checks run at local times such as 07:00.
        this(properties, backup, criticalSettings, defaults, deviceSettings, repository, notifier, weather,
                Clock.systemDefaultZone());
    }

    AlertService(AlertProperties properties, BackupProperties backup, CriticalAlertProperties criticalSettings,
                 DefaultAlertProperties defaults, DeviceAlertSettings deviceSettings,
                 EventRepository repository, AlertNotifier notifier, WeatherService weather, Clock clock) {
        this.rules = new AlertRules(properties, defaults, clock.getZone(), deviceSettings);
        this.weatherAlerts = new WeatherAlerts(properties, clock.getZone());
        this.backupAlerts = new BackupAlerts(backup, clock.instant(), clock.getZone());
        this.critical = new CriticalAlerts(criticalSettings);
        this.escalateTo = AlertNotifier.targets(criticalSettings.escalateTo(), "homehub.alerts.critical.escalate-to");
        this.weather = weather;
        this.repository = repository;
        this.notifier = notifier;
        this.clock = clock;
        List<Event> latest = repository.latestPerReading();
        rules.seed(latest);           // a door left open before a restart is still watched
        weatherAlerts.seed(latest);   // and today's weather notices aren't sent twice
        backupAlerts.seed(latest);    // nor a missing backup that was already reported
        seedCritical(latest);         // and an unanswered leak keeps ringing
        worker.scheduleWithFixedDelay(this::check, CHECK_EVERY_SECONDS, CHECK_EVERY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * A critical alert still going on after a restart: keep repeating it, unless someone already said "I'm on it"
     * (the newest acknowledgement of that device is from after the alert started).
     */
    private void seedCritical(List<Event> latest) {
        Instant now = clock.instant();
        for (Alert alert : rules.active()) {
            if (!critical.isCritical(alert)) continue;
            CriticalAlerts.Ack ack = latest.stream()
                    .filter(e -> Alert.ACK_EVENT_TYPE.equals(e.type()) && alert.device().equalsIgnoreCase(e.device()))
                    .filter(e -> e.time() != null && !e.time().isBefore(alert.time()))
                    .findFirst()
                    .map(e -> new CriticalAlerts.Ack(whoFrom(e.value()), e.time()))
                    .orElse(null);
            critical.restore(alert, now, ack);
        }
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

    /**
     * Alerts decided elsewhere, such as official weather warnings (#106): stored, sent to phones (critical ones ring
     * and repeat until answered), like the alerts Kudil raises itself.
     */
    public void report(List<Alert> alerts) {
        work(() -> raise(alerts));
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
        try {
            for (CriticalAlerts.Step step : critical.due(now)) followUp(step, now);
        } catch (RuntimeException ex) {
            log.warn("Alerts: critical alert check failed ({})", ex.getClass().getSimpleName());
        }
    }

    private void raise(List<Alert> alerts) {
        for (Alert alert : alerts) {
            log.info("Alert: {}", alert.message());
            repository.save(new Event(null, alert.device(), alert.level().eventType, alert.message(), alert.time()));
            if (alert.level() == Alert.Level.CLEARED) critical.ended(alert.key());   // nothing more to repeat
            if (!alert.reachesPhone()) continue;   // dashboard only
            if (critical.isCritical(alert)) {
                critical.sent(alert, clock.instant());
                notifier.send(alert, true);
            } else if (Alert.CONNECTION_KEY.equals(alert.key())) {
                // Home Assistant just came back: give its phone notifications a moment to load before sending.
                worker.schedule(() -> notifier.send(alert), PHONE_DELAY_AFTER_RECONNECT_SECONDS, TimeUnit.SECONDS);
            } else {
                notifier.send(alert);
            }
        }
    }

    /** An unanswered critical alert: send it again, also to the escalation phones, or stop after the last repeat. */
    private void followUp(CriticalAlerts.Step step, Instant now) {
        Alert alert = step.alert();
        String note = switch (step.kind()) {
            case REPEAT -> {
                notifier.send(new Alert(alert.key(), alert.level(), alert.device(),
                        "Still no answer: " + alert.message(), now), true);
                yield "No answer yet, sent again (" + ordinal(step.count() + 1) + " time): " + alert.message();
            }
            case ESCALATE -> {
                notifier.sendTo(alert, true, escalateTo);
                yield "No answer for " + AlertRules.human(Duration.between(alert.time(), now))
                        + ", also sent to " + String.join(", ", escalateTo.stream().map(AlertService::phoneName).toList())
                        + ": " + alert.message();
            }
            case GAVE_UP -> "Nobody answered after " + (step.count() + 1) + " alerts; stopped repeating. "
                    + "It stays on the dashboard until it is over: " + alert.message();
        };
        log.info("Alert: {}", note);
        repository.save(new Event(null, alert.device(), Alert.Level.NOTICE.eventType, note, now));
    }

    /**
     * Someone said "I'm on it" for a critical alert: no more repeats, the notification goes away on the other phones,
     * and the answer is recorded (who, when). Returns false when there is nothing to answer (over, or already answered).
     *
     * @param who        "Purushphone", or "dashboard"
     * @param fromPhone  the phone that answered (keeps its notification), or null
     */
    public boolean acknowledge(String key, String who, String fromPhone) {
        Instant now = clock.instant();
        Optional<Alert> answered = critical.acknowledge(key, who, now);
        answered.ifPresent(alert -> {
            String note = "On it (" + who + "): " + alert.message();
            log.info("Alert: {}", note);
            repository.save(new Event(null, alert.device(), Alert.ACK_EVENT_TYPE, note, now));
            List<String> others = new ArrayList<>();
            for (String phone : notifier.phones()) if (!phone.equals(fromPhone)) others.add(phone);
            for (String phone : escalateTo) if (!phone.equals(fromPhone) && !others.contains(phone)) others.add(phone);
            work(() -> notifier.clear(key, others));
        });
        return answered.isPresent();
    }

    /** "I'm on it" tapped on a phone: the button's action is {@code KUDIL_ACK|<phone>|<alert key>}. */
    @EventListener
    public void onNotificationAction(NotificationAction action) {
        String[] parts = action.action().split("\\|", 3);
        if (parts.length != 3 || !AlertNotifier.ACK_ACTION.equals(parts[0])) return;   // some other app's button
        if (!acknowledge(parts[2], phoneName(parts[1]), parts[1])) {
            log.info("Alert: \"I'm on it\" from {} for an alert that is over or already answered", phoneName(parts[1]));
        }
    }

    /** Who answered this critical alert, if anyone. */
    public Optional<CriticalAlerts.Ack> acknowledgement(String key) {
        return critical.ackOf(key);
    }

    /** Whether this alert is critical and still waits for someone to say "I'm on it". */
    public boolean waitingForAnswer(String key) {
        return critical.waiting(key);
    }

    /**
     * Settings → "Send a test critical alert": rings every phone like a real leak would, once, with the "I'm on it"
     * button. Returns how many phones Home Assistant accepted it for.
     */
    public int sendTestCritical() {
        Instant now = clock.instant();
        Alert test = new Alert(CriticalAlerts.TEST_KEY, Alert.Level.PROBLEM, "Kudil",
                "Test: this is how a critical alert rings. Tap \"I'm on it\".", now);
        critical.ended(test.key());   // a new test replaces an unanswered earlier one
        critical.sent(test, now);
        int phones = notifier.send(test, true);
        String note = "Test critical alert sent to " + phones + (phones == 1 ? " phone" : " phones");
        log.info("Alert: {}", note);
        repository.save(new Event(null, "Kudil", Alert.Level.NOTICE.eventType, note, now));
        return phones;
    }

    /** "mobile_app_purush_phone" -> "Purush phone" */
    static String phoneName(String service) {
        String name = service.replaceFirst("^mobile_app_", "").replace('_', ' ').trim();
        return name.isEmpty() ? service : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** "On it (Purushphone): Water leak in the kitchen" -> "Purushphone" */
    private static String whoFrom(String note) {
        if (note == null) return "someone";
        int open = note.indexOf('('), close = note.indexOf("):");
        return open >= 0 && close > open ? note.substring(open + 1, close) : "someone";
    }

    private static String ordinal(int n) {
        int mod100 = n % 100, mod10 = n % 10;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : mod10 == 1 ? "st" : mod10 == 2 ? "nd" : mod10 == 3 ? "rd" : "th";
        return n + suffix;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
