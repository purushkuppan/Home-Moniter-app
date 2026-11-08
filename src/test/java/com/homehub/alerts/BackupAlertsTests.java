package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupAlertsTests {

    private static final ZoneId HUB = ZoneId.of("America/Chicago");
    /** Sunday 8 November 2026, 4:01:34 PM in Chicago: the hub's first backup. */
    private static final Instant BACKUP = Instant.parse("2026-11-08T22:01:34Z");
    private static final Instant STARTED = BACKUP.minus(Duration.ofHours(2));

    @TempDir Path dir;

    private Path status() {
        return dir.resolve("last-success");
    }

    private void backupAt(Instant time) throws Exception {
        // As backup.sh writes it: date --iso-8601=seconds, in the hub's zone.
        Files.writeString(status(), time.atZone(HUB).toOffsetDateTime() + "\n");
    }

    private BackupAlerts alerts(Instant started) {
        return new BackupAlerts(new BackupProperties(status().toString(), Duration.ofHours(26)), started, HUB);
    }

    @Test
    void quietWhileBackupsAreRecent() throws Exception {
        backupAt(BACKUP);
        BackupAlerts alerts = alerts(STARTED);
        assertThat(alerts.check(BACKUP.plus(Duration.ofHours(25)))).isEmpty();
        assertThat(alerts.active()).isEmpty();
    }

    @Test
    void reportsABackupOlderThan26HoursOnceAndClearsWhenOneSucceeds() throws Exception {
        backupAt(BACKUP);
        BackupAlerts alerts = alerts(STARTED);
        Instant late = BACKUP.plus(Duration.ofHours(26));

        assertThat(alerts.check(late)).singleElement().satisfies(a -> {
            assertThat(a.level()).isEqualTo(Level.PROBLEM);
            assertThat(a.key()).isEqualTo("backup");
            assertThat(a.device()).isEqualTo("Backup");
            assertThat(a.message()).isEqualTo("Nightly backup missing. Last good backup: Sun Nov 8, 4:01 PM.");
            assertThat(a.reachesPhone()).isTrue();
        });
        assertThat(alerts.active()).singleElement().satisfies(a -> assertThat(a.time()).isEqualTo(late));
        assertThat(alerts.check(late.plus(Duration.ofHours(5)))).as("reported once").isEmpty();

        Instant fixed = Instant.parse("2026-11-10T09:01:05Z");   // Tue 3:01 AM
        backupAt(fixed);
        assertThat(alerts.check(fixed.plus(Duration.ofMinutes(1)))).singleElement().satisfies(a -> {
            assertThat(a.level()).isEqualTo(Level.CLEARED);
            assertThat(a.message()).isEqualTo("Backups are working again. Last backup: Tue Nov 10, 3:01 AM.");
        });
        assertThat(alerts.active()).isEmpty();
    }

    @Test
    void readsTheFileAtMostOnceAMinute() throws Exception {
        backupAt(BACKUP);
        BackupAlerts alerts = alerts(STARTED);
        Instant now = BACKUP.plus(Duration.ofHours(25));
        assertThat(alerts.check(now)).isEmpty();
        Files.delete(status());   // now "no backup yet", 27 hours after the start
        assertThat(alerts.check(now.plusSeconds(30))).as("not read again so soon").isEmpty();
        assertThat(alerts.check(now.plusSeconds(60))).singleElement()
                .satisfies(a -> assertThat(a.message()).isEqualTo("Nightly backup missing. No backup has been made yet."));
    }

    @Test
    void waitsAnHourAfterStartForTheBackupMissedWhileTheHubWasOff() throws Exception {
        backupAt(BACKUP);
        Instant started = BACKUP.plus(Duration.ofDays(3));   // hub was off for three days
        BackupAlerts alerts = alerts(started);
        assertThat(alerts.check(started.plus(Duration.ofMinutes(59)))).isEmpty();

        backupAt(started.plus(Duration.ofMinutes(2)));         // the timer's Persistent=true catch-up
        assertThat(alerts.check(started.plus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    void stillReportsAfterTheFirstHourWhenTheCatchUpBackupFails() throws Exception {
        backupAt(BACKUP);
        Instant started = BACKUP.plus(Duration.ofDays(3));
        BackupAlerts alerts = alerts(started);
        assertThat(alerts.check(started.plus(Duration.ofHours(1)))).singleElement()
                .satisfies(a -> assertThat(a.level()).isEqualTo(Level.PROBLEM));
    }

    @Test
    void noStatusFileMeansNoBackupWasEverMade() {
        BackupAlerts alerts = alerts(STARTED);
        assertThat(alerts.check(STARTED.plus(Duration.ofHours(25)))).isEmpty();
        assertThat(alerts.check(STARTED.plus(Duration.ofHours(26)))).singleElement()
                .satisfies(a -> assertThat(a.message()).isEqualTo("Nightly backup missing. No backup has been made yet."));
    }

    @Test
    void anUnreadableTimeFallsBackToTheFileDate() throws Exception {
        Files.writeString(status(), "yesterday\n");
        Files.setLastModifiedTime(status(), java.nio.file.attribute.FileTime.from(BACKUP));
        BackupAlerts alerts = alerts(STARTED);
        assertThat(alerts.check(BACKUP.plus(Duration.ofHours(25)))).isEmpty();
        assertThat(alerts(STARTED).check(BACKUP.plus(Duration.ofHours(27)))).singleElement()
                .satisfies(a -> assertThat(a.message()).endsWith("Last good backup: Sun Nov 8, 4:01 PM."));
    }

    @Test
    void aProblemReportedBeforeARestartIsNotSentAgainButIsClearedLater() throws Exception {
        backupAt(BACKUP);
        Instant restart = BACKUP.plus(Duration.ofHours(30));
        BackupAlerts alerts = alerts(restart);
        alerts.seed(List.of(
                new Event(7L, "Backup", "alertCleared", "Backups are working again.", BACKUP.minus(Duration.ofDays(2))),
                new Event(9L, "Backup", "alert", "Nightly backup missing. Last good backup: Sun Nov 8, 4:01 PM.",
                        BACKUP.plus(Duration.ofHours(26))),
                new Event(8L, "Refrigerator/Fridge", "contact", "closed", BACKUP)));

        assertThat(alerts.check(restart.plus(Duration.ofHours(2)))).isEmpty();
        assertThat(alerts.active()).singleElement()
                .satisfies(a -> assertThat(a.message()).startsWith("Nightly backup missing."));

        backupAt(restart.plus(Duration.ofHours(3)));
        assertThat(alerts.check(restart.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(1)))).singleElement()
                .satisfies(a -> assertThat(a.level()).isEqualTo(Level.CLEARED));
    }

    @Test
    void aClearedProblemBeforeARestartStaysCleared() throws Exception {
        backupAt(BACKUP);
        BackupAlerts alerts = alerts(STARTED);
        alerts.seed(List.of(
                new Event(9L, "Backup", "alertCleared", "Backups are working again.", BACKUP),
                new Event(7L, "Backup", "alert", "Nightly backup missing.", BACKUP.minus(Duration.ofDays(1)))));
        assertThat(alerts.active()).isEmpty();
        assertThat(alerts.check(BACKUP.plus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    void offWithoutAStatusFile() {
        BackupAlerts alerts = new BackupAlerts(new BackupProperties("", null), STARTED, HUB);
        assertThat(alerts.check(STARTED.plus(Duration.ofDays(30)))).isEmpty();
        assertThat(alerts.active()).isEmpty();
    }
}
