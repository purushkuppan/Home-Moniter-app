package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.NotificationAction;
import com.homehub.weather.WeatherService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A water leak at night (#101): rings, repeats, escalates, and stops when someone says "I'm on it". */
class AlertServiceCriticalTests {

    private static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");

    /** A clock the test moves forward by hand. */
    private static final class TestClock extends Clock {
        Instant now = T0;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final EventRepository repository = mock(EventRepository.class);
    private final AlertNotifier notifier = mock(AlertNotifier.class);
    private final List<Event> saved = new ArrayList<>();
    private AlertService service;

    private AlertService start(List<Event> latest) {
        when(repository.latestPerReading()).thenReturn(latest);
        when(repository.save(any())).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(notifier.phones()).thenReturn(List.of("mobile_app_purushphone", "mobile_app_ammaphone"));
        when(notifier.send(any(), eq(true))).thenReturn(2);
        WeatherService weather = mock(WeatherService.class);
        when(weather.current()).thenReturn(Optional.empty());
        AlertProperties alerts = new AlertProperties(List.of("notify.mobile_app_purushphone", "notify.mobile_app_ammaphone"),
                "", Map.of(), Map.of(), null, List.of(), Map.of(), null, null, null, null, null, null,
                Map.of("Kitchen leak sensor", "Water leak in the kitchen"));
        CriticalAlertProperties critical = new CriticalAlertProperties(List.of("leak"), Duration.ofMinutes(5), 24,
                Duration.ofMinutes(10), List.of("notify.mobile_app_neighbour"), List.of());
        service = new AlertService(alerts, new BackupProperties("", null), critical, DefaultAlertProperties.defaults(),
                DeviceAlertSettings.NONE, repository, notifier, weather, clock);
        return service;
    }

    @AfterEach
    void stop() {
        if (service != null) service.stop();
    }

    private void leak(String value, Instant time) {
        service.onEventSaved(new Event(1L, "Kitchen leak sensor", "leak", value, time));
    }

    private List<String> notes() {
        return saved.stream().filter(e -> e.type().equals("notice") || e.type().equals("alertAck")).map(Event::value).toList();
    }

    @Test
    void aLeakRingsRepeatsEscalatesAndStopsWhenSomeoneIsOnIt() {
        start(List.of());
        leak("wet", T0);

        var sent = ArgumentCaptor.forClass(Alert.class);
        verify(notifier, timeout(2000)).send(sent.capture(), eq(true));
        assertThat(sent.getValue().message()).isEqualTo("Water leak in the kitchen");
        assertThat(service.waitingForAnswer("leak:kitchen leak sensor")).isTrue();

        clock.now = T0.plus(Duration.ofMinutes(5));
        service.check();
        verify(notifier).send(org.mockito.ArgumentMatchers.argThat(a -> a != null
                && a.message().equals("Still no answer: Water leak in the kitchen")), eq(true));

        clock.now = T0.plus(Duration.ofMinutes(10));
        service.check();
        verify(notifier).sendTo(any(), eq(true), eq(List.of("mobile_app_neighbour")));

        // Amma taps "I'm on it" on her phone.
        clock.now = T0.plus(Duration.ofMinutes(11));
        service.onNotificationAction(new NotificationAction(
                "KUDIL_ACK|mobile_app_ammaphone|leak:kitchen leak sensor", clock.now));
        verify(notifier, timeout(2000)).clear("leak:kitchen leak sensor", List.of("mobile_app_purushphone", "mobile_app_neighbour"));
        assertThat(service.acknowledgement("leak:kitchen leak sensor"))
                .hasValueSatisfying(ack -> assertThat(ack.who()).isEqualTo("Ammaphone"));

        clock.now = T0.plus(Duration.ofMinutes(30));
        service.check();
        assertThat(notes()).containsExactly(
                "No answer yet, sent again (2nd time): Water leak in the kitchen",
                "No answer for 10 minutes, also sent to Neighbour: Water leak in the kitchen",
                "No answer yet, sent again (3rd time): Water leak in the kitchen",
                "On it (Ammaphone): Water leak in the kitchen");
        assertThat(saved).filteredOn(e -> e.type().equals("alertAck"))
                .extracting(Event::device).containsExactly("Kitchen leak sensor");
    }

    @Test
    void theDashboardCanAnswerAndADryReadingEndsIt() {
        start(List.of());
        leak("wet", T0);
        verify(notifier, timeout(2000)).send(any(), eq(true));

        assertThat(service.acknowledge("leak:kitchen leak sensor", "dashboard", null)).isTrue();
        assertThat(service.acknowledge("leak:kitchen leak sensor", "dashboard", null)).as("once").isFalse();
        verify(notifier, timeout(2000)).clear("leak:kitchen leak sensor",
                List.of("mobile_app_purushphone", "mobile_app_ammaphone", "mobile_app_neighbour"));

        leak("dry", T0.plus(Duration.ofMinutes(20)));
        verify(notifier, timeout(2000)).send(org.mockito.ArgumentMatchers.argThat(a -> a != null
                && a.level() == Alert.Level.CLEARED));
        assertThat(service.acknowledgement("leak:kitchen leak sensor")).as("over").isEmpty();
    }

    @Test
    void otherAppsButtonsAndUnknownAlertsAreIgnored() {
        start(List.of());
        service.onNotificationAction(new NotificationAction("SOME_OTHER_APP", T0));
        service.onNotificationAction(new NotificationAction("KUDIL_ACK|mobile_app_purushphone|leak:nothing", T0));
        assertThat(service.acknowledge("door:refrigerator/fridge", "dashboard", null)).isFalse();
        verify(notifier, never()).clear(any(), anyList());
        assertThat(saved).isEmpty();
    }

    @Test
    void afterARestartAnUnansweredLeakKeepsRingingAndAnAnsweredOneDoesNot() {
        Instant earlier = T0.minus(Duration.ofMinutes(30));
        start(List.of(
                new Event(1L, "Kitchen leak sensor", "leak", "wet", earlier),
                new Event(2L, "Kitchen leak sensor", "alert", "Water leak in the kitchen", earlier)));
        assertThat(service.waitingForAnswer("leak:kitchen leak sensor")).isTrue();
        clock.now = T0.plus(Duration.ofMinutes(5));
        service.check();
        verify(notifier).send(any(), eq(true));
        service.stop();

        saved.clear();
        start(List.of(
                new Event(1L, "Kitchen leak sensor", "leak", "wet", earlier),
                new Event(2L, "Kitchen leak sensor", "alert", "Water leak in the kitchen", earlier),
                new Event(3L, "Kitchen leak sensor", "alertAck", "On it (Purushphone): Water leak in the kitchen",
                        earlier.plus(Duration.ofMinutes(1)))));
        assertThat(service.waitingForAnswer("leak:kitchen leak sensor")).isFalse();
        assertThat(service.acknowledgement("leak:kitchen leak sensor"))
                .hasValueSatisfying(ack -> assertThat(ack.who()).isEqualTo("Purushphone"));
    }

    @Test
    void theTestAlertRingsOnceAndIsLogged() {
        start(List.of());
        assertThat(service.sendTestCritical()).isEqualTo(2);
        verify(notifier).send(org.mockito.ArgumentMatchers.argThat(a -> a != null
                && a.key().equals("test:critical") && a.message().contains("I'm on it")), eq(true));
        clock.now = T0.plus(Duration.ofMinutes(10));
        service.check();
        assertThat(saved).extracting(Event::device, Event::type, Event::value)
                .containsExactly(tuple("Kudil", "notice", "Test critical alert sent to 2 phones"));
    }

    @Test
    void phoneNamesReadNaturally() {
        assertThat(AlertService.phoneName("mobile_app_purushphone")).isEqualTo("Purushphone");
        assertThat(AlertService.phoneName("mobile_app_amma_s_phone")).isEqualTo("Amma s phone");
    }
}
