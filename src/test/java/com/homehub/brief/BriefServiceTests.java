package com.homehub.brief;

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
import com.homehub.weather.WarningService;
import com.homehub.weather.WeatherService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The brief goes out once a day at 20:00 hub time (#21), also after a restart that missed it. */
class BriefServiceTests {

    private static final ZoneId DENTON = ZoneId.of("America/Chicago");

    private final EventRepository events = mock(EventRepository.class);
    private final AlertService alerts = mock(AlertService.class);
    private final List<Event> stored = new ArrayList<>();

    private BriefService at(String localTime, boolean enabled) {
        Instant now = java.time.LocalDate.of(2026, 11, 13).atTime(LocalTime.parse(localTime)).atZone(DENTON).toInstant();
        when(events.between(any(), any())).thenAnswer(call -> stored.stream()
                .filter(e -> !e.time().isBefore(call.getArgument(0)) && e.time().isBefore(call.getArgument(1))).toList());
        WeatherService weather = mock(WeatherService.class);
        when(weather.current()).thenReturn(Optional.empty());
        WarningService warnings = mock(WarningService.class);
        when(warnings.status()).thenReturn(new WarningService.Status(null, null, List.of()));
        var provider = new StaticListableBeanFactory(Map.of("alertService", alerts)).getBeanProvider(AlertService.class);
        return new BriefService(events, weather, warnings, provider, enabled, LocalTime.of(20, 0), Clock.fixed(now, DENTON));
    }

    @SuppressWarnings("unchecked")
    private List<Alert> sent() {
        ArgumentCaptor<List<Alert>> captor = ArgumentCaptor.forClass(List.class);
        verify(alerts, org.mockito.Mockito.atLeast(0)).report(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Test
    void notBeforeEightInTheEvening() {
        at("19:59", true).sendIfDue();
        verify(alerts, never()).report(any());
    }

    @Test
    void atEightItGoesOutAsAQuietNotice() {
        stored.add(new Event(1L, "Back door", "contact", "open", Instant.parse("2026-11-13T15:00:00Z")));
        at("20:00", true).sendIfDue();

        assertThat(sent()).singleElement().satisfies(a -> {
            assertThat(a.level()).isEqualTo(Alert.Level.NOTICE);
            assertThat(a.device()).isEqualTo("Daily brief");
            assertThat(a.key()).isEqualTo("brief:2026-11-13");
            assertThat(a.message()).startsWith("Today at home, Fri Nov 13:\n• Back door opened once");
        });
    }

    @Test
    void neverTwiceADayButStillAfterALateRestart() {
        stored.add(new Event(1L, "Daily brief", "notice", "Today at home…", Instant.parse("2026-11-14T02:00:00Z")));   // 20:00
        at("21:30", true).sendIfDue();
        verify(alerts, never()).report(any());

        stored.clear();
        at("23:10", true).sendIfDue();   // Kudil was restarting at 20:00
        assertThat(sent()).hasSize(1);
    }

    @Test
    void switchedOffItSendsNothingButThePreviewWorks() {
        BriefService brief = at("20:30", false);
        brief.sendIfDue();
        verify(alerts, never()).report(any());
        assertThat(brief.preview()).startsWith("Today at home, Fri Nov 13:");
    }

    @Test
    void theBriefDoesntCountItself() {
        stored.add(new Event(1L, "Daily brief", "notice", "Washer finished", Instant.parse("2026-11-13T15:00:00Z")));
        assertThat(at("12:00", true).preview()).doesNotContain("Washer");
    }
}
