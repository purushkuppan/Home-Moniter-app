package com.homehub.platform;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.homeassistant.HomeAssistantProperties;
import com.homehub.homeassistant.HomeAssistantStatus;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The dead-man switch (#102). Every alert comes from the hub, so if the hub dies, nothing tells us; an outside check
 * (Healthchecks.io) does, when Kudil stops pinging it.
 * <ul>
 *   <li>Every {@code every} (5 minutes) Kudil pings {@code ping-url}, but only when it is really healthy: the database
 *       answers, the alert timers ran in the last minute, and Home Assistant (which still delivers phone alerts) is
 *       connected. Otherwise it reports {@code /fail} with the reason, so the outside check alerts at once instead of
 *       after its grace time. A dead hub, a dead process or no internet simply stop the pings.</li>
 *   <li>Every check also looks at the disk that holds the database and at memory and swap, and raises Kudil's own
 *       alerts ({@link HubResources}).</li>
 * </ul>
 * The ping URL is a secret and is never logged; the reasons sent with {@code /fail} name only what failed.
 */
@Service
public class DeadManSwitch {

    private static final Logger log = LoggerFactory.getLogger(DeadManSwitch.class);
    /** The alert timers run every 10 s; a minute without a run means they are stuck. */
    static final Duration TIMERS_STUCK_AFTER = Duration.ofMinutes(1);
    private static final Pattern MEMINFO = Pattern.compile("^(\\w+):\\s+(\\d+)", Pattern.MULTILINE);

    private final DeadManProperties settings;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<AlertService> alerts;
    private final HomeAssistantProperties homeAssistant;
    private final HttpClient http;
    private final Clock clock;
    private final HubResources resources;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dead-man-switch");
        t.setDaemon(true);
        return t;
    });
    private volatile Boolean homeAssistantReachable;   // null: not known yet
    private volatile String lastFailure;               // what the last /fail said, so a change is logged once

    @Autowired
    public DeadManSwitch(DeadManProperties settings, JdbcTemplate jdbc, ObjectProvider<AlertService> alerts,
                         HomeAssistantProperties homeAssistant) {
        this(settings, jdbc, alerts, homeAssistant,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC());
        long every = settings.every().toMillis();
        // The first check waits a minute, so Home Assistant has connected after a restart.
        worker.scheduleWithFixedDelay(this::tick, Math.min(every, 60_000), every, TimeUnit.MILLISECONDS);
        if (settings.pings()) log.info("Dead-man switch: pinging the outside check every {}", settings.every());
        else if (!settings.pingUrl().isEmpty()) log.warn("homehub.deadman.ping-url must start with https://; no pings.");
    }

    /** For tests: nothing is scheduled; call {@link #tick()}. */
    DeadManSwitch(DeadManProperties settings, JdbcTemplate jdbc, ObjectProvider<AlertService> alerts,
                  HomeAssistantProperties homeAssistant, HttpClient http, Clock clock) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.homeAssistant = homeAssistant;
        this.http = http;
        this.clock = clock;
        this.resources = new HubResources(settings.diskWarn(), settings.memoryWarn());
    }

    @EventListener
    public void onHomeAssistant(HomeAssistantStatus status) {
        homeAssistantReachable = status.reachable();
    }

    void tick() {
        try {
            Instant now = clock.instant();
            List<String> problems = problems(now);
            if (settings.pings()) ping(problems);
            List<Alert> raised = new ArrayList<>(disk(now));
            raised.addAll(memory(now));
            AlertService service = alerts.getIfAvailable();
            if (!raised.isEmpty() && service != null) service.report(raised);
        } catch (RuntimeException ex) {
            log.warn("Dead-man switch: check failed ({})", ex.getClass().getSimpleName());
        }
    }

    /** What is wrong with Kudil right now, in plain words; empty when it is healthy. */
    List<String> problems(Instant now) {
        List<String> problems = new ArrayList<>();
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) problems.add("the database doesn't answer");
        } catch (RuntimeException ex) {
            problems.add("the database doesn't answer");
        }
        AlertService service = alerts.getIfAvailable();
        if (service == null) {
            problems.add("alerts are switched off");
        } else {
            Instant last = service.lastCheck();
            if (last == null || last.isBefore(now.minus(TIMERS_STUCK_AFTER))) problems.add("the alert timers are stuck");
        }
        if (homeAssistant.isConfigured() && !Boolean.TRUE.equals(homeAssistantReachable)) {
            problems.add("Home Assistant can't be reached, so phones get no alerts");
        }
        return problems;
    }

    /** Problems with the hub's disk or memory going on now, for the dashboard banner. */
    public List<Alert> active() {
        synchronized (resources) {
            return resources.active();
        }
    }

    private void ping(List<String> problems) {
        boolean healthy = problems.isEmpty();
        String reason = String.join("; ", problems);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Kudil");
        if (healthy) {
            request.uri(URI.create(settings.pingUrl())).GET();
        } else {
            request.uri(URI.create(settings.pingUrl().replaceAll("/+$", "") + "/fail"))
                    .POST(HttpRequest.BodyPublishers.ofString("Kudil: " + reason));
        }
        try {
            HttpResponse<Void> answer = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (answer.statusCode() >= 300) {
                log.warn("Dead-man switch: the outside check answered HTTP {}", answer.statusCode());
            } else if (!healthy && !reason.equals(lastFailure)) {
                log.warn("Dead-man switch: told the outside check that Kudil is unwell: {}", reason);
            } else if (healthy && lastFailure != null) {
                log.info("Dead-man switch: Kudil is healthy again");
            }
            lastFailure = healthy ? null : reason;
        } catch (IOException ex) {
            log.warn("Dead-man switch: can't reach the outside check ({})", ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Alert> disk(Instant now) {
        try {
            FileStore store = Files.getFileStore(Path.of(settings.diskPath()));
            synchronized (resources) {
                return resources.disk(store.getTotalSpace(), store.getUsableSpace(), now);
            }
        } catch (IOException | RuntimeException ex) {
            return List.of();   // a folder that doesn't exist yet, or no disk information
        }
    }

    private List<Alert> memory(Instant now) {
        if (settings.meminfo().isEmpty()) return List.of();
        try {
            Map<String, Long> values = meminfo(Files.readString(Path.of(settings.meminfo())));
            synchronized (resources) {
                return resources.memory(values, now);
            }
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
    }

    /** "MemTotal:  4000000 kB" lines -> {MemTotal=4000000, ...} */
    static Map<String, Long> meminfo(String text) {
        Map<String, Long> values = new HashMap<>();
        Matcher m = MEMINFO.matcher(text);
        while (m.find()) values.put(m.group(1), Long.parseLong(m.group(2)));
        return values;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
