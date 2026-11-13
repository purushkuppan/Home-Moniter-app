package com.homehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class DeployRollbackAlertTests {

    @TempDir Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void aRolledBackDeployIsToldOnceAndTheNoteRemoved() throws Exception {
        Path note = dir.resolve("deploy-rolled-back");
        Files.writeString(note, "v0.31.0 Alerts check failed: rules, home assistant.\n");
        AlertService service = mock(AlertService.class);
        ObjectProvider<AlertService> alerts = mock(ObjectProvider.class);
        when(alerts.getIfAvailable()).thenReturn(service);
        DeployRollbackAlert rollback = new DeployRollbackAlert(note.toString(), alerts,
                Clock.fixed(Instant.parse("2026-11-13T21:00:00Z"), ZoneOffset.UTC));

        rollback.check();
        ArgumentCaptor<List<Alert>> sent = ArgumentCaptor.forClass(List.class);
        verify(service).report(sent.capture());
        assertThat(sent.getValue()).extracting(Alert::key, Alert::level, Alert::message).containsExactly(
                org.assertj.core.api.Assertions.tuple("deploy", Alert.Level.PROBLEM,
                        "The Kudil update to v0.31.0 was undone: alerts check failed: rules, home assistant. "
                                + "Kudil runs the previous version."));
        assertThat(note).doesNotExist();

        rollback.check();   // after the next restart: nothing
        verify(service).report(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nothingWithoutANoteOrASetting() {
        ObjectProvider<AlertService> alerts = mock(ObjectProvider.class);
        new DeployRollbackAlert(dir.resolve("missing").toString(), alerts, Clock.systemUTC()).check();
        new DeployRollbackAlert("", alerts, Clock.systemUTC()).check();
        verify(alerts, never()).getIfAvailable();
    }

    @Test
    void messagesAreShortAndPlain() {
        assertThat(DeployRollbackAlert.message("v1.2.3 Health check failed: the app didn't start."))
                .isEqualTo("The Kudil update to v1.2.3 was undone: health check failed: the app didn't start. "
                        + "Kudil runs the previous version.");
        assertThat(DeployRollbackAlert.message("something odd\u0007")).isEqualTo(
                "The Kudil update was undone: something odd. Kudil runs the previous version.");
        assertThat(DeployRollbackAlert.message("v1.2.3 " + "x".repeat(500))).hasSizeLessThan(300);
    }
}
