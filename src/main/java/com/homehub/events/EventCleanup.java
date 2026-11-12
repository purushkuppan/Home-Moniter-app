package com.homehub.events;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes old events once a day (at {@code homehub.events.cleanup-at}, hub time) and once shortly after startup:
 * readings and commands older than {@code keep-days}, alerts older than {@code keep-alert-days}.
 * <p>
 * The newest event of every device reading (device + type) is never deleted, however old: readings are only stored
 * when they change, so it is the current state of a quiet device (a door closed for months) and what the dashboard
 * and the alert rules start from.
 * <p>
 * Rows are deleted in batches, so the single database connection is never held for long. SQLite reuses the freed
 * pages for new events, so the file stops growing; it is not shrunk (no VACUUM).
 */
@Component
public class EventCleanup {

    private static final Logger log = LoggerFactory.getLogger(EventCleanup.class);

    /** Event types written by alerts (see {@code Alert.EVENT_TYPES}); they are kept for {@code keep-alert-days}. */
    public static final List<String> ALERT_TYPES = List.of("alert", "alertCleared", "notice", "alertAck");
    static final int BATCH_SIZE = 5_000;
    /** The first cleanup waits a little, so it doesn't compete with the connectors and alerts starting up. */
    static final Duration FIRST_RUN_AFTER = Duration.ofMinutes(2);

    private static final String TYPES = String.join(", ", Collections.nCopies(ALERT_TYPES.size(), "?"));
    private static final String DELETE_BATCH = """
            DELETE FROM events WHERE id IN (
                SELECT id FROM events
                WHERE time < ? AND type %s (%s)
                  AND id NOT IN (SELECT MAX(id) FROM events GROUP BY device, type)
                LIMIT ?)""";

    private final EventProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int batchSize;
    private ScheduledExecutorService worker;

    @Autowired
    public EventCleanup(EventProperties properties, JdbcTemplate jdbc) {
        // The hub's own time zone: the cleanup runs at a local time such as 03:30.
        this(properties, jdbc, Clock.systemDefaultZone(), BATCH_SIZE);
    }

    EventCleanup(EventProperties properties, JdbcTemplate jdbc, Clock clock, int batchSize) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @PostConstruct
    void start() {
        if (!properties.cleanupOn()) {
            log.info("Event cleanup is off: all events are kept");
            return;
        }
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "event-cleanup");
            t.setDaemon(true);
            return t;
        });
        worker.schedule(this::run, FIRST_RUN_AFTER.toMillis(), TimeUnit.MILLISECONDS);
        scheduleNext();
    }

    private void scheduleNext() {
        try {
            worker.schedule(() -> {
                try {
                    run();
                } finally {
                    scheduleNext();
                }
            }, untilNextRun(clock.instant()).toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            // shutting down
        }
    }

    /**
     * Time from {@code now} to the next daily cleanup. A run less than a minute away is skipped, so a timer that
     * fires a little early doesn't clean up twice.
     */
    Duration untilNextRun(Instant now) {
        ZonedDateTime local = now.atZone(clock.getZone());
        ZonedDateTime next = local.toLocalDate().atTime(properties.cleanupAt()).atZone(clock.getZone());
        if (next.isBefore(local.plusMinutes(1))) {
            next = local.toLocalDate().plusDays(1).atTime(properties.cleanupAt()).atZone(clock.getZone());
        }
        return Duration.between(local, next);
    }

    /** Deletes what is too old now. Never throws, so the daily schedule keeps going. */
    int run() {
        Instant now = clock.instant();
        try {
            int readings = deleteOlderThan(properties.keepDays(), now, false);
            int alerts = deleteOlderThan(properties.keepAlertDays(), now, true);
            List<String> parts = new ArrayList<>();
            parts.add(describe(readings, "readings and commands", properties.keepDays()));
            parts.add(describe(alerts, "alerts", properties.keepAlertDays()));
            log.info("Event cleanup: {}", String.join(", ", parts));
            return readings + alerts;
        } catch (RuntimeException ex) {
            log.warn("Event cleanup failed", ex);
            return 0;
        }
    }

    private static String describe(int removed, String what, int days) {
        return days > 0 ? "removed " + removed + " " + what + " older than " + days + " days" : "kept all " + what;
    }

    /**
     * Deletes the events of one group (alerts, or everything else) older than {@code days}, batch by batch.
     * Each batch is its own statement and transaction, so other work gets the connection in between.
     */
    private int deleteOlderThan(int days, Instant now, boolean alerts) {
        if (days <= 0) return 0;
        // Times are stored as ISO-8601 text in UTC, so text order is time order (to the second).
        String before = now.minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS).toString();
        String sql = DELETE_BATCH.formatted(alerts ? "IN" : "NOT IN", TYPES);
        List<Object> args = new ArrayList<>();
        args.add(before);
        args.addAll(ALERT_TYPES);
        args.add(batchSize);
        int total = 0;
        int removed;
        do {
            removed = jdbc.update(sql, args.toArray());
            total += removed;
        } while (removed >= batchSize);
        return total;
    }

    @PreDestroy
    void stop() {
        if (worker != null) worker.shutdownNow();
    }
}
