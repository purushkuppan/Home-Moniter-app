package com.homehub.alerts;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a missing nightly backup. {@code deploy/backup.sh} writes the time of each successful backup to
 * {@code homehub.alerts.backup.status-file}; when that time is older than {@code missing-after} (26 hours), the
 * household is told once, and told again when backups work again.
 * <ul>
 *   <li>No alert in the first hour after Kudil starts: after the hub was off, the missed backup runs right after
 *       boot.</li>
 *   <li>No status file at all means no backup was ever made; that is reported once Kudil has run for
 *       {@code missing-after}.</li>
 *   <li>A problem reported before a restart is not reported again (seeded from the stored alert events).</li>
 * </ul>
 */
final class BackupAlerts {

    private static final Logger log = LoggerFactory.getLogger(BackupAlerts.class);
    static final String DEVICE = "Backup";
    static final String KEY = "backup";
    /** The file changes once a night; reading it once a minute is plenty. */
    static final Duration READ_EVERY = Duration.ofMinutes(1);
    static final Duration AFTER_START = Duration.ofHours(1);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US);

    private final Path statusFile;   // null: backup alerts are off
    private final Duration missingAfter;
    private final ZoneId zone;
    private final Instant started;
    private Instant nextRead = Instant.MIN;
    private Instant lastSuccess;     // null: no backup recorded
    private String problem;          // message of the reported problem while it lasts
    private Instant problemSince;

    BackupAlerts(BackupProperties properties, Instant started, ZoneId zone) {
        this.statusFile = properties.statusFile().isEmpty() ? null : Path.of(properties.statusFile());
        this.missingAfter = properties.missingAfter();
        this.zone = zone;
        this.started = started;
    }

    /** Picks up a problem that was reported before a restart, so it isn't sent again. */
    synchronized void seed(List<Event> latestPerReading) {
        latestPerReading.stream()
                .filter(e -> DEVICE.equals(e.device()))
                .filter(e -> Level.PROBLEM.eventType.equals(e.type()) || Level.CLEARED.eventType.equals(e.type()))
                .max(Comparator.comparing(Event::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(e -> Level.PROBLEM.eventType.equals(e.type()))
                .ifPresent(e -> {
                    problem = e.value();
                    problemSince = e.time();
                });
    }

    /** Called every few seconds; reads the status file at most once a minute. */
    synchronized List<Alert> check(Instant now) {
        if (statusFile == null || now.isBefore(nextRead)) return List.of();
        nextRead = now.plus(READ_EVERY);
        lastSuccess = read();

        Instant missingSince = (lastSuccess != null ? lastSuccess : started).plus(missingAfter);
        boolean missing = !now.isBefore(missingSince);
        if (problem == null && missing && !now.isBefore(started.plus(AFTER_START))) {
            problem = "Nightly backup missing. " + (lastSuccess == null
                    ? "No backup has been made yet."
                    : "Last good backup: " + when(lastSuccess) + ".");
            problemSince = missingSince;
            return List.of(new Alert(KEY, Level.PROBLEM, DEVICE, problem, now));
        }
        if (problem != null && !missing && lastSuccess != null) {
            problem = null;
            problemSince = null;
            return List.of(new Alert(KEY, Level.CLEARED, DEVICE,
                    "Backups are working again. Last backup: " + when(lastSuccess) + ".", now));
        }
        return List.of();
    }

    /** The reported problem while it lasts, for the dashboard banner. */
    synchronized List<Alert> active() {
        if (problem == null) return List.of();
        return List.of(new Alert(KEY, Level.PROBLEM, DEVICE, problem, problemSince));
    }

    /** Time of the last successful backup, or null when none is recorded. */
    private Instant read() {
        try {
            String text = Files.readString(statusFile).trim();
            try {
                return OffsetDateTime.parse(text).toInstant();   // backup.sh: date --iso-8601=seconds
            } catch (DateTimeParseException ex) {
                return Files.getLastModifiedTime(statusFile).toInstant();
            }
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException ex) {
            log.warn("Backup alerts: can't read {} ({})", statusFile, ex.getClass().getSimpleName());
            return lastSuccess;   // keep what we knew rather than report a backup missing
        }
    }

    private String when(Instant time) {
        return WHEN.format(time.atZone(zone));
    }
}
