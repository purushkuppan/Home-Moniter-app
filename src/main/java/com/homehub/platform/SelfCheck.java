package com.homehub.platform;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.events.EventStream;
import com.homehub.homeassistant.HomeAssistantProperties;
import com.homehub.homeassistant.HomeAssistantStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Proves that alerts still work, not just that the app answers (#124). {@code install-release.sh} runs it after every
 * deploy and rolls back when it fails.
 * <ol>
 *   <li><b>Stored:</b> a test event is saved and read back.</li>
 *   <li><b>Live stream:</b> that event reached the stream that feeds open dashboards.</li>
 *   <li><b>Rules:</b> a pretend leak, run through a fresh copy of the rules with the live settings, raises a critical
 *       alert. Nothing is stored or sent to phones.</li>
 *   <li><b>Home Assistant:</b> connected (phone alerts still go through it), when it is configured.</li>
 *   <li><b>Devices</b> (a note, never a failure): how long ago a real device last reported. A quiet house at night is
 *       not a broken deploy.</li>
 * </ol>
 * The test event is deleted again at once; the dashboard never shows it ({@value #TYPE} events are skipped).
 */
@Service
public class SelfCheck {

    public static final String DEVICE = "Kudil self-check";
    public static final String TYPE = "selfCheck";
    /** Events Kudil writes itself: never a sign that a device reported. */
    private static final Set<String> NOT_DEVICES = Set.of(TYPE, "alert", "alertCleared", "notice", "alertAck", "command");

    /** One check: its name, whether it passed, and what was found. */
    public record Check(String name, boolean ok, String detail) {
    }

    /** All checks; {@code ok} only when every one passed. */
    public record Result(boolean ok, List<Check> checks) {
    }

    private final EventRepository events;
    private final EventStream stream;
    private final ObjectProvider<AlertService> alerts;
    private final HomeAssistantProperties homeAssistant;
    private final Clock clock;
    private volatile Boolean homeAssistantReachable;   // null: not known yet

    @Autowired
    public SelfCheck(EventRepository events, EventStream stream, ObjectProvider<AlertService> alerts,
                     HomeAssistantProperties homeAssistant) {
        this(events, stream, alerts, homeAssistant, Clock.systemUTC());
    }

    SelfCheck(EventRepository events, EventStream stream, ObjectProvider<AlertService> alerts,
              HomeAssistantProperties homeAssistant, Clock clock) {
        this.events = events;
        this.stream = stream;
        this.alerts = alerts;
        this.homeAssistant = homeAssistant;
        this.clock = clock;
    }

    @EventListener
    public void onHomeAssistant(HomeAssistantStatus status) {
        homeAssistantReachable = status.reachable();
    }

    public synchronized Result run() {
        List<Check> checks = new ArrayList<>();
        Instant now = clock.instant();

        // 1 and 2: stored, and on the live stream. Deleted again whatever happens.
        Event saved = null;
        try {
            saved = events.save(new Event(null, DEVICE, TYPE, "test " + now, now));
            long id = saved.id();
            Optional<Event> back = events.find(id);
            checks.add(new Check("stored", back.isPresent() && TYPE.equals(back.get().type()),
                    back.isPresent() ? "A test event was saved and read back." : "The test event was saved but can't be read back."));
            boolean onStream = stream.lastEventId() == id;
            checks.add(new Check("live stream", onStream, onStream ? "The test event reached the live stream."
                    : "The test event didn't reach the live stream that feeds the dashboard."));
        } catch (RuntimeException ex) {
            checks.add(new Check("stored", false, "Couldn't save a test event (" + ex.getClass().getSimpleName() + ")."));
            checks.add(new Check("live stream", false, "Not checked: no test event."));
        } finally {
            if (saved != null) {
                try {
                    events.delete(saved.id());
                } catch (RuntimeException ex) {
                    // left in the table; the dashboard skips it anyway
                }
            }
        }

        // 3: the rules, dry.
        AlertService service = alerts.getIfAvailable();
        if (service == null) {
            checks.add(new Check("rules", false, "Alerts are switched off (homehub.alerts.enabled)."));
        } else {
            try {
                AlertService.DryRun dry = service.dryRunLeak(DEVICE);
                Alert first = dry.alerts().isEmpty() ? null : dry.alerts().get(0);
                boolean ok = first != null && first.level() == Alert.Level.PROBLEM && first.key().startsWith("leak:") && dry.critical();
                checks.add(new Check("rules", ok, ok ? "A pretend leak raises a critical alert (not sent)."
                        : first == null ? "A pretend leak raised no alert."
                        : "A pretend leak raised \"" + first.message() + "\", but not as a critical alert."));
            } catch (RuntimeException ex) {
                checks.add(new Check("rules", false, "The rules failed on a pretend leak (" + ex.getClass().getSimpleName() + ")."));
            }
        }

        // 4: Home Assistant, which still delivers phone alerts.
        if (!homeAssistant.isConfigured()) {
            checks.add(new Check("home assistant", true, "Not configured: nothing to check."));
        } else {
            Boolean reachable = homeAssistantReachable;
            checks.add(new Check("home assistant", Boolean.TRUE.equals(reachable),
                    reachable == null ? "Not connected yet." : reachable ? "Connected." : "Can't be reached."));
        }

        boolean ok = checks.stream().allMatch(Check::ok);

        // 5: a note only.
        checks.add(new Check("devices", true, lastDeviceReport(now)));
        return new Result(ok, checks);
    }

    private String lastDeviceReport(Instant now) {
        try {
            return events.latest(200).stream()
                    .filter(e -> e.type() != null && !NOT_DEVICES.contains(e.type()) && e.time() != null)
                    .findFirst()
                    .map(e -> "A device last reported " + minutes(Duration.between(e.time(), now)) + " ago (" + e.device() + ").")
                    .orElse("No device has reported recently.");
        } catch (RuntimeException ex) {
            return "Couldn't read the latest events.";
        }
    }

    private static String minutes(Duration d) {
        long m = Math.max(0, d.toMinutes());
        if (m < 1) return "less than a minute";
        if (m < 60) return m + (m == 1 ? " minute" : " minutes");
        return (m / 60) + (m / 60 == 1 ? " hour" : " hours");
    }
}
