package com.homehub.hub;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs {@link HubMonitor} every {@code homehub.hub.check-every} (30 s) on a thread of its own, since an internet check
 * can wait a few seconds. Stores what happened and hands the alerts to {@link AlertService}, which stores them and
 * sends them to phones. Does nothing unless the hub sets {@code homehub.hub.power-supply-dir} or
 * {@code homehub.hub.internet-hosts}.
 */
@Service
public class HubWatch {

    private static final Logger log = LoggerFactory.getLogger(HubWatch.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    /** "12 F", "-3.5 °C" */
    private static final Pattern TEMPERATURE = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*°?\\s*([FCfc])?");

    private final HubProperties settings;
    private final HubMonitor monitor;
    private final EventRepository events;
    private final ObjectProvider<AlertService> alerts;
    private final InternetCheck internet;
    private final Clock clock;
    private final ScheduledExecutorService worker;

    @Autowired
    public HubWatch(HubProperties settings, EventRepository events, ObjectProvider<AlertService> alerts,
                    @Value("${homehub.hub.check-every:30s}") java.time.Duration every) {
        this(settings, events, alerts, InternetCheck.tcp(settings.internetHosts(), CONNECT_TIMEOUT_MILLIS),
                Clock.systemDefaultZone());
        if (enabled()) {
            worker.scheduleWithFixedDelay(this::check, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Hub watch: power {}, internet {}", settings.powerSupplyDir().isEmpty() ? "off" : "on",
                    settings.internetHosts().isEmpty() ? "off" : "on");
        }
    }

    /** For tests: nothing is scheduled; call {@link #check()}. */
    HubWatch(HubProperties settings, EventRepository events, ObjectProvider<AlertService> alerts,
             InternetCheck internet, Clock clock) {
        this.settings = settings;
        this.events = events;
        this.alerts = alerts;
        this.internet = internet;
        this.clock = clock;
        this.monitor = new HubMonitor(settings, clock.getZone());
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hub-watch");
            t.setDaemon(true);
            return t;
        });
        monitor.seed(events.latestPerReading());
    }

    private boolean enabled() {
        return !settings.powerSupplyDir().isEmpty() || !settings.internetHosts().isEmpty();
    }

    void check() {
        try {
            Instant now = clock.instant();
            PowerSupply.Reading power = settings.powerSupplyDir().isEmpty()
                    ? null : PowerSupply.read(Path.of(settings.powerSupplyDir()));
            Boolean online = settings.internetHosts().isEmpty() ? null : internet.reachable();
            Double freezer = null;
            String unit = "";
            Event reading = freezerReading();
            if (reading != null) {
                Matcher m = TEMPERATURE.matcher(reading.value().trim());
                if (m.matches()) {
                    freezer = Double.parseDouble(m.group(1));
                    unit = m.group(2) == null ? "" : " °" + m.group(2).toUpperCase(Locale.ROOT);
                }
            }
            HubMonitor.Outcome outcome;
            synchronized (monitor) {
                outcome = monitor.check(now, power, online, freezer, unit);
            }
            outcome.log().forEach(events::save);
            if (!outcome.alerts().isEmpty()) {
                AlertService service = alerts.getIfAvailable();
                if (service != null) service.report(outcome.alerts());
                else outcome.alerts().forEach(a -> log.info("Hub: {}", a.message()));
            }
        } catch (RuntimeException ex) {
            log.warn("Hub watch: check failed ({})", ex.getClass().getSimpleName());
        }
    }

    private Event freezerReading() {
        if (settings.freezer().isEmpty()) return null;
        return events.latestPerReading().stream()
                .filter(e -> settings.freezer().equalsIgnoreCase(e.device()) && "temperature".equals(e.type()))
                .filter(e -> e.value() != null)
                .findFirst().orElse(null);
    }

    /** Power cut or internet outage going on now, for the dashboard banner. */
    public List<Alert> active() {
        synchronized (monitor) {
            return monitor.active();
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
