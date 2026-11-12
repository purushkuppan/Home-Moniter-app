package com.homehub.alerts;

import com.homehub.controls.ControlPin;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerts for the dashboard.
 * <ul>
 *   <li>{@code GET /api/alerts}: problems going on right now (an empty list when alerts are switched off), with
 *       whether each is critical and who said "I'm on it".</li>
 *   <li>{@code POST /api/alerts/ack} with {@code {"key": "..."}}: "I'm on it" from the dashboard for a critical alert.
 *       No PIN: answering only stops the repeats; the alert stays until the problem is over.</li>
 *   <li>{@code POST /api/alerts/test}: rings every phone with a test critical alert. Needs the household PIN
 *       (header {@code X-HomeHub-Pin}), since it sounds an alarm on the family's phones.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    /** One problem going on, as the dashboard shows it. */
    public record ActiveAlert(String key, Alert.Level level, String device, String message, Instant time,
                              boolean critical, String acknowledgedBy, Instant acknowledgedAt) {
    }

    public record AckRequest(String key) {
    }

    private static final String PIN_HEADER = "X-HomeHub-Pin";

    private final ObjectProvider<AlertService> alerts;
    private final ObjectProvider<SmartThingsWatchdog> watchdog;
    private final ControlPin pin;

    public AlertController(ObjectProvider<AlertService> alerts, ObjectProvider<SmartThingsWatchdog> watchdog, ControlPin pin) {
        this.alerts = alerts;
        this.watchdog = watchdog;
        this.pin = pin;
    }

    @GetMapping
    public List<ActiveAlert> active() {
        List<ActiveAlert> active = new ArrayList<>();
        alerts.ifAvailable(service -> service.active().forEach(a -> {
            var ack = service.acknowledgement(a.key());
            boolean critical = service.waitingForAnswer(a.key()) || ack.isPresent();
            active.add(new ActiveAlert(a.key(), a.level(), a.device(), a.message(), a.time(), critical,
                    ack.map(CriticalAlerts.Ack::who).orElse(null), ack.map(CriticalAlerts.Ack::at).orElse(null)));
        }));
        watchdog.ifAvailable(w -> w.active().forEach(a ->
                active.add(new ActiveAlert(a.key(), a.level(), a.device(), a.message(), a.time(), false, null, null))));
        active.sort(Comparator.comparing(ActiveAlert::time));
        return active;
    }

    @PostMapping("/ack")
    public ResponseEntity<Map<String, Object>> acknowledge(@RequestBody(required = false) AckRequest request) {
        AlertService service = alerts.getIfAvailable();
        if (service == null) return message(HttpStatus.NOT_FOUND, "Alerts are switched off.");
        if (request == null || request.key() == null || request.key().isBlank()) {
            return message(HttpStatus.BAD_REQUEST, "Which alert? (key)");
        }
        if (!service.acknowledge(request.key().trim(), "dashboard", null)) {
            return message(HttpStatus.CONFLICT, "This alert is over or someone already answered it.");
        }
        return message(HttpStatus.OK, "Thanks. The other phones stop ringing.");
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestHeader(value = PIN_HEADER, required = false) String attempt) {
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
