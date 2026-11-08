package com.homehub.alerts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Backup alert settings, bound from {@code homehub.alerts.backup.*}.
 *
 * @param statusFile   file in which the nightly backup ({@code deploy/backup.sh}) records its last success, on the
 *                     hub {@code /var/lib/homehub-backup/last-success}. Empty: no backup alerts.
 * @param missingAfter how old the last good backup may get before an alert; a little over a day, so one slow night
 *                     doesn't raise one
 */
@ConfigurationProperties("homehub.alerts.backup")
public record BackupProperties(String statusFile, @DefaultValue("26h") Duration missingAfter) {

    public BackupProperties {
        statusFile = statusFile == null ? "" : statusFile.trim();
        if (missingAfter == null) missingAfter = Duration.ofHours(26);
    }
}
