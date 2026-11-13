package com.homehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.events.EventStream;
import com.homehub.homeassistant.HomeAssistantProperties;
import com.homehub.homeassistant.HomeAssistantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SelfCheckTests {

    private static final Instant NOW = Instant.parse("2026-11-13T21:00:00Z");
    private final EventRepository events = mock(EventRepository.class);
    private final EventStream stream = mock(EventStream.class);
    private final AlertService service = mock(AlertService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AlertService> alerts = mock(ObjectProvider.class);
    private final Alert leak = new Alert("leak:kudil self-check", Alert.Level.PROBLEM, SelfCheck.DEVICE, "Water leak", NOW);

    @BeforeEach
    void storedAndOnTheStream() {
        when(events.save(any())).thenAnswer(call -> {
            Event e = call.getArgument(0);
            return new Event(42L, e.device(), e.type(), e.value(), e.time());
        });
        when(events.find(42L)).thenReturn(Optional.of(new Event(42L, SelfCheck.DEVICE, SelfCheck.TYPE, "test", NOW)));
        when(stream.lastEventId()).thenReturn(42L);
        when(alerts.getIfAvailable()).thenReturn(service);
        when(service.dryRunLeak(SelfCheck.DEVICE)).thenReturn(new AlertService.DryRun(List.of(leak), true));
        when(events.latest(anyInt())).thenReturn(List.of(
                new Event(41L, "Kudil", "notice", "x", NOW.minusSeconds(60)),
                new Event(40L, "Back door", "contact", "closed", NOW.minusSeconds(25 * 60))));
    }

    private SelfCheck check(HomeAssistantProperties homeAssistant) {
        return new SelfCheck(events, stream, alerts, homeAssistant, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HomeAssistantProperties none() {
        return new HomeAssistantProperties("", "");
    }

    @Test
    void allGoodAndTheTestEventIsDeleted() {
        SelfCheck.Result result = check(none()).run();
        assertThat(result.ok()).isTrue();
        assertThat(result.checks()).extracting(SelfCheck.Check::name, SelfCheck.Check::ok).containsExactly(
                tuple("stored", true), tuple("live stream", true), tuple("rules", true),
                tuple("home assistant", true), tuple("devices", true));
        assertThat(result.checks().get(4).detail()).as("our own notice doesn't count as a device")
                .isEqualTo("A device last reported 25 minutes ago (Back door).");
        verify(events).delete(42L);
    }

    @Test
    void anEventThatNeverReachedTheStreamFails() {
        when(stream.lastEventId()).thenReturn(41L);
        SelfCheck.Result result = check(none()).run();
        assertThat(result.ok()).isFalse();
        assertThat(result.checks().get(1)).isEqualTo(new SelfCheck.Check("live stream", false,
                "The test event didn't reach the live stream that feeds the dashboard."));
        verify(events).delete(42L);
    }

    @Test
    void aBrokenRuleFails() {
        when(service.dryRunLeak(SelfCheck.DEVICE)).thenReturn(new AlertService.DryRun(List.of(), false));
        assertThat(check(none()).run().checks().get(2)).isEqualTo(
                new SelfCheck.Check("rules", false, "A pretend leak raised no alert."));

        when(service.dryRunLeak(SelfCheck.DEVICE)).thenReturn(new AlertService.DryRun(List.of(leak), false));
        assertThat(check(none()).run().checks().get(2).detail()).isEqualTo(
                "A pretend leak raised \"Water leak\", but not as a critical alert.");

        when(alerts.getIfAvailable()).thenReturn(null);
        assertThat(check(none()).run().checks().get(2).detail()).isEqualTo("Alerts are switched off (homehub.alerts.enabled).");
    }

    @Test
    void aDatabaseThatCantSaveFails() {
        org.mockito.Mockito.doThrow(new IllegalStateException("disk full")).when(events).save(any());
        SelfCheck.Result result = check(none()).run();
        assertThat(result.ok()).isFalse();
        assertThat(result.checks().get(0).detail()).isEqualTo("Couldn't save a test event (IllegalStateException).");
        verify(events, org.mockito.Mockito.never()).delete(anyLong());
    }

    @Test
    void homeAssistantMustBeConnectedWhenItIsConfigured() {
        SelfCheck check = check(new HomeAssistantProperties("http://ha:8123", "token"));
        assertThat(check.run().checks().get(3)).isEqualTo(new SelfCheck.Check("home assistant", false, "Not connected yet."));
        check.onHomeAssistant(new HomeAssistantStatus(false, NOW));
        assertThat(check.run().checks().get(3).detail()).isEqualTo("Can't be reached.");
        check.onHomeAssistant(new HomeAssistantStatus(true, NOW));
        assertThat(check.run().ok()).isTrue();
    }

    @Test
    void aQuietHouseIsANoteNotAFailure() {
        when(events.latest(anyInt())).thenReturn(List.of());
        SelfCheck.Result result = check(none()).run();
        assertThat(result.ok()).isTrue();
        assertThat(result.checks().get(4).detail()).isEqualTo("No device has reported recently.");
    }
}
