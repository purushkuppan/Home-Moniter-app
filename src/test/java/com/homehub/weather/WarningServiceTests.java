package com.homehub.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.homehub.alerts.Alert;
import com.homehub.alerts.AlertService;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Official weather warnings for the house (#106): any home's location, a source per country. */
class WarningServiceTests {

    private static final String HA = "http://ha.test:8123";
    private static final ZoneId DENTON = ZoneId.of("America/Chicago");
    private static final Instant NOW = Instant.parse("2026-11-13T00:30:00Z");   // 6:30 PM in Denton

    /** A warning source the test controls; remembers where it was asked about. */
    private static final class FakeSource implements WarningSource {
        List<WeatherWarning> warnings = List.of();
        final List<String> asked = new ArrayList<>();

        @Override public String name() { return "US National Weather Service"; }
        @Override public boolean covers(String country) { return "US".equals(country); }
        @Override public List<WeatherWarning> active(double latitude, double longitude) {
            asked.add(latitude + "," + longitude);
            return warnings;
        }
    }

    private final FakeSource source = new FakeSource();
    private final AlertService alerts = mock(AlertService.class);
    private final EventRepository events = mock(EventRepository.class);
    private final MutableClock clock = new MutableClock();
    private MockRestServiceServer homeAssistant;

    private static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return DENTON; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private WarningService service(String config, int configReads) {
        RestClient.Builder builder = RestClient.builder();
        homeAssistant = MockRestServiceServer.bindTo(builder).build();
        homeAssistant.expect(ExpectedCount.times(configReads), requestTo(HA + "/api/config"))
                .andRespond(withSuccess(config, MediaType.APPLICATION_JSON));
        var provider = new StaticListableBeanFactory(Map.of("alertService", alerts)).getBeanProvider(AlertService.class);
        return new WarningService(builder, new HomeAssistantProperties(HA, "secret"), List.of(source), provider, events,
                true, clock);
    }

    private static final String DENTON_HOME = """
            {"latitude":33.214567,"longitude":-97.133089,"country":"US","time_zone":"America/Chicago","location_name":"Home"}""";

    private static WeatherWarning warning(String event, WeatherWarning.Level level, String ends, Double hail) {
        return new WeatherWarning(event, level, event + " issued", ends == null ? null : Instant.parse(ends), hail,
                "https://forecast.weather.gov/MapClick.php?lat=33.21&lon=-97.13");
    }

    @SuppressWarnings("unchecked")
    private List<Alert> reported() {
        ArgumentCaptor<List<Alert>> captor = ArgumentCaptor.forClass(List.class);
        verify(alerts, org.mockito.Mockito.atLeast(0)).report(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Test
    void aTornadoWarningRingsOnceWhileItLastsAndEndsWithAnAllClear() {
        WarningService service = service(DENTON_HOME, 1);
        source.warnings = List.of(warning("Tornado Warning", WeatherWarning.Level.CRITICAL, "2026-11-13T01:45:00Z", null));

        service.check();
        assertThat(source.asked).as("only the location rounded to about 1 km").containsExactly("33.21,-97.13");
        assertThat(reported()).extracting(Alert::key, Alert::level, Alert::device, Alert::message).containsExactly(tuple(
                "severe:tornado-warning", Alert.Level.PROBLEM, "Tornado Warning",
                "Tornado Warning for the house until 7:45 PM."));
        assertThat(service.active()).extracting(Alert::key).containsExactly("severe:tornado-warning");

        // The service extends it: a new warning with a later end, still the same tornado warning.
        source.warnings = List.of(warning("Tornado Warning", WeatherWarning.Level.CRITICAL, "2026-11-13T02:15:00Z", null));
        clock.now = NOW.plus(Duration.ofMinutes(3));
        service.check();
        assertThat(reported()).as("not again").hasSize(1);

        source.warnings = List.of();
        clock.now = NOW.plus(Duration.ofMinutes(40));
        service.check();
        assertThat(reported()).extracting(Alert::key, Alert::level, Alert::message).last()
                .isEqualTo(tuple("severe:tornado-warning", Alert.Level.CLEARED, "Tornado Warning has ended."));
        assertThat(service.active()).isEmpty();
        homeAssistant.verify();   // the home's location was read once
    }

    @Test
    void hailMeansMovingTheCarsAndWatchesAreQuietNotices() {
        WarningService service = service(DENTON_HOME, 1);
        source.warnings = List.of(
                warning("Severe Thunderstorm Warning", WeatherWarning.Level.CRITICAL, "2026-11-13T02:15:00Z", 1.75),
                warning("Freeze Watch", WeatherWarning.Level.NOTICE, "2026-11-14T15:00:00Z", null),
                warning("Winter Storm Warning", WeatherWarning.Level.WARNING, null, null));

        service.check();

        assertThat(reported()).extracting(Alert::key, Alert::level, Alert::message).containsExactly(
                tuple("severe:severe-thunderstorm-warning", Alert.Level.PROBLEM, "Severe Thunderstorm Warning for the house "
                        + "until 8:15 PM. Hail up to 1.75 in: move the cars into the garage."),
                tuple("weather:freeze-watch", Alert.Level.NOTICE, "Freeze Watch for the house until Sat 9:00 AM."),
                tuple("weather:winter-storm-warning", Alert.Level.PROBLEM, "Winter Storm Warning for the house."));
        assertThat(service.active()).as("notices aren't problems going on").extracting(Alert::device)
                .containsExactly("Severe Thunderstorm Warning", "Winter Storm Warning");
        assertThat(service.status().source()).isEqualTo("US National Weather Service");
        assertThat(service.status().warnings()).hasSize(3);

        source.warnings = List.of();
        service.check();
        assertThat(reported()).filteredOn(a -> a.level() == Alert.Level.CLEARED).extracting(Alert::device)
                .as("a watch that ends needs no all clear")
                .containsExactlyInAnyOrder("Severe Thunderstorm Warning", "Winter Storm Warning");
    }

    @Test
    void withoutAHomeLocationOrASourceForTheCountryTheCardSaysSo() {
        WarningService noLocation = service("{\"latitude\":0,\"longitude\":0,\"country\":\"US\"}", 1);
        noLocation.check();
        assertThat(noLocation.status().note()).contains("Set the home's location in Home Assistant");
        assertThat(source.asked).isEmpty();

        WarningService india = service("{\"latitude\":13.08,\"longitude\":80.27,\"country\":\"IN\"}", 1);
        india.check();
        assertThat(india.status().note()).isEqualTo("No official weather warnings for IN yet.");
        assertThat(india.status().source()).isNull();
        assertThat(source.asked).isEmpty();
        verify(alerts, never()).report(any());
    }

    @Test
    void theHomesLocationIsReadAgainEverySixHours() {
        WarningService service = service(DENTON_HOME, 2);
        service.check();
        clock.now = NOW.plus(Duration.ofHours(5));
        service.check();
        clock.now = NOW.plus(Duration.ofHours(6));
        service.check();
        homeAssistant.verify();
    }

    @Test
    void afterARestartAWarningAlreadyReportedIsNotReportedAgain() {
        when(events.latestPerReading()).thenReturn(List.of(
                new Event(7L, "Tornado Warning", "alert", "Tornado Warning for the house until 7:45 PM. Go to your safe room now.",
                        NOW.minus(Duration.ofMinutes(10))),
                new Event(8L, "Freeze Watch", "notice", "Freeze Watch for the house.", NOW.minus(Duration.ofHours(30))),
                new Event(9L, "Refrigerator/Fridge", "alert", "Fridge door has been open for 1 minute", NOW)));
        WarningService service = service(DENTON_HOME, 1);
        source.warnings = List.of(
                warning("Tornado Warning", WeatherWarning.Level.CRITICAL, "2026-11-13T01:45:00Z", null),
                warning("Freeze Watch", WeatherWarning.Level.NOTICE, null, null));

        service.check();

        assertThat(reported()).as("the freeze watch is from yesterday: news again").extracting(Alert::device)
                .containsExactly("Freeze Watch");
        assertThat(service.active()).extracting(Alert::key).containsExactly("severe:tornado-warning");
    }

    @Test
    void namesBecomeKeys() {
        assertThat(WarningService.slug("Severe Thunderstorm Warning")).isEqualTo("severe-thunderstorm-warning");
        assertThat(WarningService.slug(" Tornado  Emergency ")).isEqualTo("tornado-emergency");
    }
}
