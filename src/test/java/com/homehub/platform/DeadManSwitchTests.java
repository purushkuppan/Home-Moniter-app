package com.homehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.homeassistant.HomeAssistantProperties;
import com.homehub.homeassistant.HomeAssistantStatus;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(OutputCaptureExtension.class)
class DeadManSwitchTests {

    private static final Instant NOW = Instant.parse("2026-11-13T21:00:00Z");
    private static final String PING = "https://hc-ping.com/0f3c2a1e-test-uuid-secret";

    @TempDir Path dir;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AlertService service = mock(AlertService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AlertService> alerts = mock(ObjectProvider.class);
    private final HttpClient http = mock(HttpClient.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void healthy() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(alerts.getIfAvailable()).thenReturn(service);
        when(service.lastCheck()).thenReturn(NOW.minusSeconds(8));
        HttpResponse<Void> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) ok);
    }

    private DeadManSwitch deadMan(String pingUrl, HomeAssistantProperties homeAssistant, int diskWarn, String meminfo) {
        return new DeadManSwitch(new DeadManProperties(pingUrl, Duration.ofMinutes(5), dir.toString(), diskWarn, meminfo, 10),
                jdbc, alerts, homeAssistant, http, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DeadManSwitch deadMan() {
        return deadMan(PING, new HomeAssistantProperties("", ""), 101, "");
    }

    @SuppressWarnings("unchecked")
    private HttpRequest sent() throws Exception {
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        return request.getValue();
    }

    @Test
    void pingsWhileHealthy(CapturedOutput output) throws Exception {
        deadMan().tick();
        HttpRequest request = sent();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri()).hasToString(PING);
        assertThat(output.getAll()).as("the ping URL is a secret").doesNotContain("secret");
    }

    @Test
    void reportsFailWithTheReasonWhenUnwell(CapturedOutput output) throws Exception {
        when(service.lastCheck()).thenReturn(NOW.minusSeconds(120));
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("locked"));
        deadMan().tick();
        HttpRequest request = sent();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri()).hasToString(PING + "/fail");
        assertThat(output.getAll()).contains("the database doesn't answer; the alert timers are stuck")
                .doesNotContain("secret");
    }

    @Test
    void whatCountsAsUnwell() {
        assertThat(deadMan().problems(NOW)).isEmpty();

        DeadManSwitch withHomeAssistant = deadMan(PING, new HomeAssistantProperties("http://ha:8123", "token"), 101, "");
        assertThat(withHomeAssistant.problems(NOW)).containsExactly("Home Assistant can't be reached, so phones get no alerts");
        withHomeAssistant.onHomeAssistant(new HomeAssistantStatus(true, NOW));
        assertThat(withHomeAssistant.problems(NOW)).isEmpty();

        when(service.lastCheck()).thenReturn(null);
        assertThat(deadMan().problems(NOW)).containsExactly("the alert timers are stuck");
        when(alerts.getIfAvailable()).thenReturn(null);
        assertThat(deadMan().problems(NOW)).containsExactly("alerts are switched off");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noPingWithoutAnHttpsUrl() throws Exception {
        deadMan("", new HomeAssistantProperties("", ""), 101, "").tick();
        deadMan("http://hc-ping.com/abc", new HomeAssistantProperties("", ""), 101, "").tick();
        verify(http, never()).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diskAndMemoryBecomeKudilsOwnAlerts() throws Exception {
        Path meminfo = Files.writeString(dir.resolve("meminfo"),
                "MemTotal:        4000000 kB\nMemFree:          100000 kB\nMemAvailable:     200000 kB\nSwapTotal:       0 kB\nSwapFree:        0 kB\n");
        DeadManSwitch deadMan = deadMan(PING, new HomeAssistantProperties("", ""), 0, meminfo.toString());   // any disk is "full"
        deadMan.tick();
        ArgumentCaptor<List<Alert>> raised = ArgumentCaptor.forClass(List.class);
        verify(service).report(raised.capture());
        assertThat(raised.getValue()).extracting(Alert::key).containsExactly("disk:hub", "memory:hub");
        assertThat(deadMan.active()).extracting(Alert::key).containsExactly("disk:hub", "memory:hub");
        verify(http).send(any(), any(HttpResponse.BodyHandler.class));   // disk and memory don't stop the ping
    }

    @Test
    void readsMeminfo() {
        assertThat(DeadManSwitch.meminfo("MemTotal:  4000 kB\nMemAvailable: 1200 kB\nHugePages_Total:       0\n"))
                .containsEntry("MemTotal", 4000L).containsEntry("MemAvailable", 1200L).containsEntry("HugePages_Total", 0L);
    }
}
