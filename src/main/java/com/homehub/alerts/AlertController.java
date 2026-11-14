package com.homehub.alerts;

import com.homehub.ApiDocs;
import com.homehub.controls.ControlPin;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.hub.HubWatch;
import com.homehub.platform.DeadManSwitch;
import com.homehub.weather.WarningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerts for the dashboard.
 * <ul>
 *   <li>{@code GET /api/alerts}: problems going on right now (an empty list when alerts are switched off), with
 *       whether each is critical and who said "I'm on it".</li>
 *   <li>{@code GET /api/alerts/notified?key=...&at=...}: the alert a tapped phone notification was about (#184).</li>
 *   <li>{@code POST /api/alerts/ack} with {@code {"key": "..."}}: "I'm on it" from the dashboard for a critical alert.
 *       No PIN: answering only stops the repeats; the alert stays until the problem is over.</li>
 *   <li>{@code POST /api/alerts/test}: rings every phone with a test critical alert. Needs the household PIN
 *       (header {@code X-HomeHub-Pin}), since it sounds an alarm on the family's phones.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/alerts")
@Tag(name = "Alerts", description = "Problems going on right now, \"I'm on it\", and the test alarm")
public class AlertController {

    /** One problem going on, as the dashboard shows it, with what to do next (#161), or null. */
    public record ActiveAlert(String key, Alert.Level level, String device, String message, String nextStep, Instant time,
                              boolean critical, String acknowledgedBy, Instant acknowledgedAt) {
    }

    /** {@code who}: optional, e.g. "Android phone" from the ntfy button (#154); "dashboard" when absent. */
    public record AckRequest(String key, String who) {
    }

    /** Letters, digits, spaces, - and _, at most 30: it ends up in the activity list. */
    private static final Pattern WHO = Pattern.compile("[\\p{L}\\p{N} _-]{1,30}");

    private static final String PIN_HEADER = "X-HomeHub-Pin";

    private final ObjectProvider<AlertService> alerts;
    private final ObjectProvider<SmartThingsWatchdog> watchdog;
    private final ObjectProvider<WarningService> warnings;
    private final ObjectProvider<HubWatch> hub;
    private final ObjectProvider<DeadManSwitch> deadMan;
    private final ControlPin pin;
    private final EventRepository events;

    public AlertController(ObjectProvider<AlertService> alerts, ObjectProvider<SmartThingsWatchdog> watchdog,
                           ObjectProvider<WarningService> warnings, ObjectProvider<HubWatch> hub,
                           ObjectProvider<DeadManSwitch> deadMan, ControlPin pin, EventRepository events) {
        this.events = events;
        this.alerts = alerts;
        this.watchdog = watchdog;
        this.warnings = warnings;
        this.hub = hub;
        this.deadMan = deadMan;
        this.pin = pin;
    }

    @GetMapping
    @Operation(summary = "Problems going on right now, oldest first",
            description = "With whether each is critical and who said \"I'm on it\". Empty when alerts are switched off.")
    public List<ActiveAlert> active() {
        List<Alert> all = new ArrayList<>();
        AlertService service = alerts.getIfAvailable();
        if (service != null) all.addAll(service.active());
        watchdog.ifAvailable(w -> all.addAll(w.active()));
        warnings.ifAvailable(w -> all.addAll(w.active()));   // official weather warnings in force (#106)
        hub.ifAvailable(h -> all.addAll(h.active()));        // power cut, internet down (#103)
        deadMan.ifAvailable(d -> all.addAll(d.active()));    // the hub's disk or memory (#102)

        List<ActiveAlert> active = new ArrayList<>();
        for (Alert a : all) {
            var ack = service == null ? java.util.Optional.<CriticalAlerts.Ack>empty() : service.acknowledgement(a.key());
            boolean critical = service != null && (service.waitingForAnswer(a.key()) || ack.isPresent());
            active.add(new ActiveAlert(a.key(), a.level(), a.device(), a.message(), NextSteps.of(a).orElse(null), a.time(), critical,
                    ack.map(CriticalAlerts.Ack::who).orElse(null), ack.map(CriticalAlerts.Ack::at).orElse(null)));
        }
        active.sort(Comparator.comparing(ActiveAlert::time));
        return active;
    }

    /**
     * The alert a phone notification was about (#184). {@code goingOn}: the problem is still there (it is also in
     * {@code GET /api/alerts}). A problem that is over has no next step any more; news and all clears keep theirs.
     */
    public record Notified(String key, Alert.Level level, String device, String message, String nextStep, Instant time,
                           boolean goingOn) {
    }

    @GetMapping("/notified")
    @Operation(summary = "The alert a phone notification was about",
            description = "`key` and `at` come from the notification's link (`#alert=...&at=...`). Says whether it is still "
                    + "going on and what to do next. 404 when Kudil has no such alert.")
    public ResponseEntity<Notified> notified(@RequestParam String key, @RequestParam(required = false) String at) {
        for (ActiveAlert a : active()) {
            if (a.key().equals(key)) {
                return ResponseEntity.ok(new Notified(a.key(), a.level(), a.device(), a.message(), a.nextStep(), a.time(), true));
            }
        }
        Instant time;
        try {
            time = at == null ? null : Instant.parse(at.trim());
        } catch (DateTimeParseException ex) {
            time = null;
        }
        if (time == null) return ResponseEntity.notFound().build();
        List<Event> stored = events.at(time, NOTIFIED_TYPES);
        // Alerts raised in the same moment share a time: prefer the one about the device the key names.
        String lowerKey = key.toLowerCase(Locale.ROOT);
        Optional<Event> found = stored.stream()
                .filter(e -> e.device() != null && lowerKey.contains(e.device().split("/")[0].trim().toLowerCase(Locale.ROOT)))
                .findFirst()
                .or(() -> stored.stream().findFirst());
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        Event e = found.get();
        Alert alert = new Alert(key, Alert.levelOf(e.type()).orElseThrow(), e.device(), e.value(), e.time());
        String step = alert.level() == Alert.Level.PROBLEM ? null : NextSteps.of(alert).orElse(null);
        return ResponseEntity.ok(new Notified(key, alert.level(), alert.device(), alert.message(), step, alert.time(), false));
    }

    /** Event types an alert is stored with (not "On it" answers). */
    private static final List<String> NOTIFIED_TYPES = List.of(Alert.Level.PROBLEM.eventType,
            Alert.Level.CLEARED.eventType, Alert.Level.NOTICE.eventType);

    @PostMapping("/ack")
    @Operation(summary = "\"I'm on it\" for a critical alert",
            description = "Body `{\"key\": \"...\", \"who\": \"Android phone\"}` (`who` optional). No PIN: it only stops the repeats; the alert stays until the "
                    + "problem is over. 409 when the alert is over or someone already answered.")
    public ResponseEntity<Map<String, Object>> acknowledge(@RequestBody(required = false) AckRequest request) {
        AlertService service = alerts.getIfAvailable();
        if (service == null) return message(HttpStatus.NOT_FOUND, "Alerts are switched off.");
        if (request == null || request.key() == null || request.key().isBlank()) {
            return message(HttpStatus.BAD_REQUEST, "Which alert? (key)");
        }
        String who = request.who() != null && WHO.matcher(request.who().trim()).matches() ? request.who().trim() : "dashboard";
        if (!service.acknowledge(request.key().trim(), who, null)) {
            return message(HttpStatus.CONFLICT, "This alert is over or someone already answered it.");
        }
        return message(HttpStatus.OK, "Thanks. The other phones stop ringing.");
    }

    @PostMapping("/test")
    @Operation(summary = "Ring every phone with a test critical alert",
            description = "Sounds an alarm on the family's phones, even on silent. Needs the household PIN.",
            security = @SecurityRequirement(name = ApiDocs.PIN))
    public ResponseEntity<Map<String, Object>> test(@Parameter(hidden = true) @RequestHeader(value = PIN_HEADER, required = false) String attempt) {
        switch (pin.check(attempt)) {
            case OK -> { }
            case WRONG -> { return message(HttpStatus.UNAUTHORIZED, "Wrong PIN."); }
            case LOCKED -> {
                return message(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many wrong PINs. Try again in " + Math.max(1, (pin.lockedSeconds() + 59) / 60) + " minutes.");
            }
            case NOT_CONFIGURED -> { return message(HttpStatus.FORBIDDEN, "Set a PIN (homehub.control.pin) first."); }
        }
        AlertService service = alerts.getIfAvailable();
        if (service == null) return message(HttpStatus.NOT_FOUND, "Alerts are switched off.");
        int phones = service.sendTestCritical();
        if (phones == 0) {
            return message(HttpStatus.SERVICE_UNAVAILABLE,
                    "No phone got it. Check homehub.alerts.send-to and that Home Assistant is reachable.");
        }
        return message(HttpStatus.OK, "Test alert sent to " + phones + (phones == 1 ? " phone" : " phones")
                + ". It should ring even on silent. Tap \"I'm on it\" to stop it.");
    }

    private static ResponseEntity<Map<String, Object>> message(HttpStatus status, String text) {
        return ResponseEntity.status(status).body(Map.of("message", text));
    }
}
