package com.homehub.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class HubWatchTests {

    @Test
    @SuppressWarnings("unchecked")
    void storesWhatHappenedAndHandsTheAlertsToTheAlertService() {
        EventRepository events = mock(EventRepository.class);
        when(events.latestPerReading()).thenReturn(List.of());
        AlertService service = mock(AlertService.class);
        ObjectProvider<AlertService> alerts = mock(ObjectProvider.class);
        when(alerts.getIfAvailable()).thenReturn(service);
        AtomicBoolean online = new AtomicBoolean(true);
        HubProperties settings = new HubProperties(null, List.of("example.invalid:53"), java.time.Duration.ZERO,
                null, null, "", 60);
        HubWatch watch = new HubWatch(settings, events, alerts, online::get,
                Clock.fixed(Instant.parse("2026-11-13T08:05:00Z"), ZoneOffset.UTC));

        watch.check();
        verify(service, never()).report(any());

        online.set(false);
        watch.check();
        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().value()).isEqualTo("lost");
        ArgumentCaptor<List<Alert>> reported = ArgumentCaptor.forClass(List.class);
        verify(service).report(reported.capture());
        assertThat(reported.getValue()).extracting(Alert::key).containsExactly("internet:home");
        assertThat(watch.active()).extracting(Alert::key).containsExactly("internet:home");
    }
}
