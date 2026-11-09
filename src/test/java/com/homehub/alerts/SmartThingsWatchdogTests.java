package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The outage of 8 November 2026: SmartThings went silent at 2:54 PM (Chicago) without an error. */
class SmartThingsWatchdogTests {

    private static final String URL = "http://ha.test:8123";
    private static final String ENTITY = "sensor.kitchen_refrigerator_power";
    private static final String STATE_URL = URL + "/api/states/" + ENTITY;
    private static final String RELOAD_URL = URL + "/api/services/homeassistant/reload_config_entry";
    private static final ZoneId HUB = ZoneId.of("America/Chicago");
    private static final Instant SILENT_FROM = Instant.parse("2026-11-08T20:54:18Z");   // 2:54 PM

    /** A clock the test moves forward by hand. */
    private static final class TestClock extends Clock {
        Instant now = SILENT_FROM.plus(Duration.ofMinutes(5));

        @Override public ZoneId getZone() { return HUB; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final EventRepository repository = mock(EventRepository.class);
    private final AlertNotifier notifier = mock(AlertNotifier.class);
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(false).build();
        when(repository.latestPerReading()).thenReturn(List.of());
    }

    private SmartThingsWatchdog watchdog() {
        return new SmartThingsWatchdog(builder, new HomeAssistantProperties(URL, "secret"),
                new WatchdogProperties(ENTITY, Duration.ofMinutes(30)), repository, notifier, clock);
    }

    /** The power sensor as Home Assistant answers: value unchanged, but reported at the given time. */
    private void expectLastReport(Instant reported) {
        server.expect(ExpectedCount.once(), requestTo(STATE_URL))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess("""
                        {"entity_id":"%s","state":"69","attributes":{"unit_of_measurement":"W"},
                         "last_changed":"2026-11-08T19:12:00.000000+00:00",
                         "last_reported":"%s","last_updated":"2026-11-08T19:12:00.000000+00:00"}"""
                        .formatted(ENTITY, reported.toString().replace("Z", "+00:00")), MediaType.APPLICATION_JSON));
    }

    private void expectReload() {
        server.expect(ExpectedCount.once(), requestTo(RELOAD_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"entity_id\":\"" + ENTITY + "\"}"))
                .andRespond(withSuccess());
    }

    private List<Event> saved() {
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(repository, org.mockito.Mockito.atLeast(0)).save(events.capture());
        return new ArrayList<>(events.getAllValues());
    }

    private void at(Duration sinceSilent) {
        clock.now = SILENT_FROM.plus(sinceSilent);
    }

    @Test
    void nothingHappensWhileTheFridgeKeepsReporting() {
        expectLastReport(SILENT_FROM);
        at(Duration.ofMinutes(29));
        watchdog().check();
        server.verify();
        assertThat(saved()).isEmpty();
        verify(notifier, never()).send(any());
    }

    @Test
    void silentFor30MinutesReloadsSmartThingsAndNotesItWhenUpdatesComeBack() {
        SmartThingsWatchdog watchdog = watchdog();
        expectLastReport(SILENT_FROM);
        expectReload();
        expectLastReport(SILENT_FROM.plus(Duration.ofMinutes(31)));   // the reload worked

        at(Duration.ofMinutes(30));
        watchdog.check();
        at(Duration.ofMinutes(35));
        watchdog.check();
        server.verify();

        assertThat(saved()).extracting(Event::device, Event::type, Event::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple("SmartThings", "notice",
                        "No updates from the Samsung appliances for 30 minutes. Kudil reloaded SmartThings in Home Assistant."),
                org.assertj.core.groups.Tuple.tuple("SmartThings", "notice",
                        "The Samsung appliances are sending updates again."));
        verify(notifier, never()).send(any());   // fixed by itself: nobody's phone needs to buzz
        assertThat(watchdog.active()).isEmpty();
    }

    @Test
    void retriesEvery30MinutesThenAlertsPhonesAndClearsWhenUpdatesReturn() {
        SmartThingsWatchdog watchdog = watchdog();
        expectLastReport(SILENT_FROM);
        expectReload();                                     // 0:30 reload 1
        expectLastReport(SILENT_FROM);                      // 0:45 too soon to try again
        expectLastReport(SILENT_FROM);
        expectReload();                                     // 1:00 reload 2
        expectLastReport(SILENT_FROM);
        expectReload();                                     // 1:30 2 reloads didn't help: alert, reload 3
        expectLastReport(SILENT_FROM);                      // 2:29:59 after the alert, retries are hourly
        expectLastReport(SILENT_FROM.plus(Duration.ofMinutes(152)));   // 2:35 back

        for (Duration t : List.of(Duration.ofMinutes(30), Duration.ofMinutes(45), Duration.ofMinutes(60),
                Duration.ofMinutes(90), Duration.ofMinutes(150).minusSeconds(1), Duration.ofMinutes(155))) {
            at(t);
            watchdog.check();
        }
        server.verify();

        ArgumentCaptor<Alert> phone = ArgumentCaptor.forClass(Alert.class);
        verify(notifier, org.mockito.Mockito.times(2)).send(phone.capture());
        assertThat(phone.getAllValues()).extracting(Alert::level, Alert::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Level.PROBLEM, "No updates from the Samsung appliances since 2:54 PM. "
                        + "Reloading SmartThings didn't help: check it in Home Assistant (Settings › Devices & services)."),
                org.assertj.core.groups.Tuple.tuple(Level.CLEARED, "The Samsung appliances are sending updates again."));
        assertThat(phone.getAllValues()).extracting(Alert::key).containsOnly("stale:smartthings");
        // The activity list shows the first two reloads, the alert and the all clear; later retries only in the log.
        assertThat(saved()).extracting(Event::type).containsExactly("notice", "notice", "alert", "alertCleared");
        assertThat(watchdog.active()).isEmpty();
    }

    @Test
    void theAlertIsShownOnTheDashboardWhileItLasts() {
        SmartThingsWatchdog watchdog = watchdog();
        for (int i = 0; i < 3; i++) {
            expectLastReport(SILENT_FROM);
            expectReload();
        }
        for (int minutes : new int[] {30, 60, 90}) {
            at(Duration.ofMinutes(minutes));
            watchdog.check();
        }
        server.verify();
        assertThat(watchdog.active()).singleElement().satisfies(a -> {
            assertThat(a.level()).isEqualTo(Level.PROBLEM);
            assertThat(a.time()).as("since the last report").isEqualTo(SILENT_FROM);
        });
    }

    @Test
    void aReloadThatHomeAssistantRefusesIsReported() {
        expectLastReport(SILENT_FROM);
        server.expect(ExpectedCount.once(), requestTo(RELOAD_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        at(Duration.ofMinutes(30));
        watchdog().check();
        server.verify();
        assertThat(saved()).extracting(Event::value).containsExactly("No updates from the Samsung appliances for 30 minutes. "
                + "Kudil couldn't reload SmartThings: Home Assistant answered HTTP 401 (needs an admin token).");
    }

    @Test
    void doesNothingWhenHomeAssistantItselfIsUnreachable() {
        server.expect(ExpectedCount.once(), requestTo(STATE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        at(Duration.ofHours(3));
        watchdog().check();
        server.verify();
        assertThat(saved()).isEmpty();
    }

    @Test
    void anAlertSentBeforeARestartIsNotSentAgainAndIsClearedLater() {
        when(repository.latestPerReading()).thenReturn(List.of(new Event(40L, "SmartThings", "alert",
                "No updates from the Samsung appliances since 2:54 PM.", SILENT_FROM.plus(Duration.ofMinutes(90)))));
        SmartThingsWatchdog watchdog = watchdog();
        assertThat(watchdog.active()).hasSize(1);

        expectLastReport(SILENT_FROM);
        expectReload();
        expectLastReport(SILENT_FROM.plus(Duration.ofMinutes(121)));

        at(Duration.ofMinutes(120));
        watchdog.check();                                   // still silent: reload quietly, no second alert
        verify(notifier, never()).send(any());
        at(Duration.ofMinutes(125));
        watchdog.check();
        server.verify();
        verify(notifier).send(org.mockito.ArgumentMatchers.argThat(a -> a.level() == Level.CLEARED));
    }

    @Test
    void offWithoutAnEntityOrWithoutHomeAssistant() {
        SmartThingsWatchdog noEntity = new SmartThingsWatchdog(builder, new HomeAssistantProperties(URL, "secret"),
                new WatchdogProperties("", null), repository, notifier, clock);
        SmartThingsWatchdog noHomeAssistant = new SmartThingsWatchdog(RestClient.builder(), new HomeAssistantProperties("", ""),
                new WatchdogProperties(ENTITY, null), repository, notifier, clock);
        at(Duration.ofDays(1));
        noEntity.check();
        noHomeAssistant.check();
        server.verify();   // no request at all
        assertThat(saved()).isEmpty();
    }
}
