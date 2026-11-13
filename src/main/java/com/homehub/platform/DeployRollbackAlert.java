package com.homehub.platform;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One phone alert when a deploy was rolled back (#124). {@code install-release.sh} writes
 * {@code homehub.deploy.status-file} ("v0.31.0 Alerts check failed: …") when a new release fails its checks, then
 * starts the previous release again. That release, once up, tells the family once and removes the file.
 */
@Component
public class DeployRollbackAlert {

    private static final Logger log = LoggerFactory.getLogger(DeployRollbackAlert.class);
    static final String KEY = "deploy";
    static final String DEVICE = "Kudil update";
    private static final int MAX_REASON = 200;

    private final Path statusFile;   // null: off
    private final ObjectProvider<AlertService> alerts;
    private final Clock clock;

    @Autowired
    public DeployRollbackAlert(@Value("${homehub.deploy.status-file:}") String statusFile,
                               ObjectProvider<AlertService> alerts) {
        this(statusFile, alerts, Clock.systemUTC());
    }

    DeployRollbackAlert(String statusFile, ObjectProvider<AlertService> alerts, Clock clock) {
        this.statusFile = statusFile == null || statusFile.isBlank() ? null : Path.of(statusFile.trim());
        this.alerts = alerts;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (statusFile == null) return;
        String line;
        try {
            line = Files.readString(statusFile, StandardCharsets.UTF_8).strip();
        } catch (NoSuchFileException ex) {
            return;   // the usual case: the last deploy went fine
        } catch (IOException | RuntimeException ex) {
            log.warn("Can't read {} ({})", statusFile, ex.getClass().getSimpleName());
            return;
        }
        Alert alert = new Alert(KEY, Alert.Level.PROBLEM, DEVICE, message(line), clock.instant());
        AlertService service = alerts.getIfAvailable();
        if (service != null) service.report(List.of(alert));
        log.warn("{}", alert.message());
        try {
            Files.deleteIfExists(statusFile);   // told once
        } catch (IOException | RuntimeException ex) {
            log.warn("Can't remove {} ({}); the alert may come again after a restart", statusFile, ex.getClass().getSimpleName());
        }
    }

    /** "v0.31.0 Alerts check failed: rules" -> "The Kudil update to v0.31.0 was undone: alerts check failed: rules. …" */
    static String message(String line) {
        String clean = line.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        String version = clean.matches("v\\d+\\.\\d+\\.\\d+( .*)?") ? clean.split(" ", 2)[0] : "";
        String reason = version.isEmpty() ? clean : clean.substring(version.length()).trim();
        if (reason.length() > MAX_REASON) reason = reason.substring(0, MAX_REASON) + "…";
        if (!reason.isEmpty()) reason = Character.toLowerCase(reason.charAt(0)) + reason.substring(1);
        return "The Kudil update" + (version.isEmpty() ? "" : " to " + version) + " was undone"
                + (reason.isEmpty() ? "." : ": " + reason.replaceAll("\\.$", "") + ".")
                + " Kudil runs the previous version.";
    }
}
