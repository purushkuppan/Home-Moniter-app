package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import com.homehub.weather.Weather;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;

class WeatherAlertsTests {

    private static final ZoneId HUB = ZoneId.of("America/Chicago");
    private static final AlertProperties SETTINGS = settings(32.0, 0.1);

    /** Sunday 8 November 2026 in Chicago (CST, UTC-6). */
    private static final Instant SUNDAY_7AM = Instant.parse("2026-11-08T13:00:00Z");
    private static final Instant SUNDAY_7PM = Instant.parse("2026-11-09T01:00:00Z");

    private static AlertProperties settings(Double freezeBelow, Double rainFrom) {
        return new AlertProperties(List.of(), "", Map.of(), Map.of(), null, List.of(), Map.of(), null,
                freezeBelow, LocalTime.of(19, 0), rainFrom, LocalTime.of(7, 0), null);
    }

    /** Daily forecast as met.no gives it through Home Assistant: one entry per day, stamped at local noon. */
    private static Weather.Day day(String date, String condition, double high, double low, double rain) {
        return new Weather.Day(date + "T18:00:00+00:00", condition, high, low, rain);
    }

    private static Weather forecast(Weather.Day... days) {
        return new Weather("cloudy", 40.0, "°F", 70.0, 5.0, "mph", "in", "met.no", SUNDAY_7AM, false, List.of(days));
    }

    private static final Weather COLD_AND_WET = forecast(
            day("2026-11-08", "rainy", 45, 36, 0.314),
            day("2026-11-09", "cloudy", 40, 28, 0.0),
            day("2026-11-10", "sunny", 50, 30, 0.0));

    private static final Weather MILD_AND_DRY = forecast(
            day("2026-11-08", "sunny", 65, 50, 0.0),
            day("2026-11-09", "sunny", 66, 48, 0.02));

    private final WeatherAlerts alerts = new WeatherAlerts(SETTINGS, HUB);

    @Test
    void freezeWarningAtSevenInTheEveningForTomorrowsLow() {
        assertThat(alerts.due(SUNDAY_7PM.minusSeconds(60))).isFalse();
        assertThat(alerts.check(SUNDAY_7PM.minusSeconds(60), COLD_AND_WET)).isEmpty();

        assertThat(alerts.due(SUNDAY_7PM)).isTrue();
        assertThat(alerts.check(SUNDAY_7PM, COLD_AND_WET)).singleElement().satisfies(a -> {
            assertThat(a.key()).isEqualTo("weather:freeze");
            assertThat(a.level()).isEqualTo(Level.NOTICE);
            assertThat(a.device()).isEqualTo("Weather/Freeze");
            assertThat(a.message()).isEqualTo("Freeze warning: low of 28 °F tonight");
            assertThat(a.reachesPhone()).isTrue();
        });
    }

    @Test
    void rainNoticeAtSevenInTheMorningForToday() {
        assertThat(alerts.check(SUNDAY_7AM, COLD_AND_WET)).extracting(Alert::key, Alert::device, Alert::message)
                .containsExactly(tuple(
                        "weather:rain", "Weather/Rain", "Rain expected today: 0.31 in"));
    }

    @Test
    void snowIsCalledSnow() {
        Weather snow = forecast(day("2026-11-08", "snowy", 30, 20, 1.5));
        assertThat(alerts.check(SUNDAY_7AM, snow)).extracting(Alert::message)
                .containsExactly("Snow expected today: 1.5 in");
    }

    @Test
    void eachCheckRunsOnceADay() {
        assertThat(alerts.check(SUNDAY_7PM, COLD_AND_WET)).hasSize(1);
        assertThat(alerts.due(SUNDAY_7PM.plusSeconds(10))).isFalse();
        assertThat(alerts.check(SUNDAY_7PM.plusSeconds(10), COLD_AND_WET)).isEmpty();

        Instant monday7pm = SUNDAY_7PM.plus(Duration.ofDays(1));
        assertThat(alerts.check(monday7pm, COLD_AND_WET)).extracting(Alert::message)
                .as("Tuesday's low of 30").containsExactly("Freeze warning: low of 30 °F tonight");
    }

    @Test
    void nothingWhenItStaysWarmAndDryAndTheCheckIsStillDone() {
        assertThat(alerts.check(SUNDAY_7AM, MILD_AND_DRY)).as("0.0 in is below 0.1 in").isEmpty();
        assertThat(alerts.check(SUNDAY_7PM, MILD_AND_DRY)).as("48 °F").isEmpty();
        assertThat(alerts.due(SUNDAY_7PM.plusSeconds(10))).isFalse();
        assertThat(alerts.check(SUNDAY_7PM.plusSeconds(10), COLD_AND_WET)).as("a later, colder forecast").isEmpty();
    }

    @Test
    void withoutAForecastTheCheckIsTriedAgain() {
        assertThat(alerts.check(SUNDAY_7PM, null)).isEmpty();
        assertThat(alerts.check(SUNDAY_7PM, forecast())).isEmpty();
        assertThat(alerts.check(SUNDAY_7PM, forecast(day("2026-11-08", "cloudy", 40, 20, 0)))).as("only today").isEmpty();
        Weather stale = new Weather("cloudy", 40.0, "°F", 70.0, 5.0, "mph", "in", "met.no", SUNDAY_7AM, true,
                COLD_AND_WET.days());
        assertThat(alerts.check(SUNDAY_7PM, stale)).as("Home Assistant can't be reached").isEmpty();
        assertThat(alerts.due(SUNDAY_7PM.plusSeconds(60))).isTrue();

        assertThat(alerts.check(SUNDAY_7PM.plusSeconds(60), COLD_AND_WET)).hasSize(1);
    }

    @Test
    void aMissedCheckRunsLateButNotMoreThanThreeHoursLate() {
        Instant tenPm = SUNDAY_7PM.plus(Duration.ofHours(3));
        assertThat(alerts.due(tenPm.minusSeconds(1))).isTrue();
        assertThat(alerts.due(tenPm)).isFalse();
        assertThat(alerts.check(tenPm, COLD_AND_WET)).isEmpty();

        assertThat(alerts.check(SUNDAY_7PM.plus(Duration.ofHours(2)), COLD_AND_WET)).hasSize(1);
    }

    @Test
    void afterARestartTodaysNoticesAreNotRepeated() {
        alerts.seed(List.of(
                new Event(1L, "Weather/Rain", "notice", "Rain expected today: 0.31 in", SUNDAY_7AM),
                new Event(2L, "Weather/Freeze", "notice", "Freeze warning: low of 25 °F tonight",
                        SUNDAY_7PM.minus(Duration.ofDays(1)))));   // yesterday's

        assertThat(alerts.check(SUNDAY_7AM.plusSeconds(600), COLD_AND_WET)).isEmpty();
        assertThat(alerts.check(SUNDAY_7PM, COLD_AND_WET)).extracting(Alert::key).containsExactly("weather:freeze");
    }

    @Test
    void settingsBindFromTheProperties() {
        var source = new MapConfigurationPropertySource(Map.of(
                "homehub.alerts.freeze-below", "", "homehub.alerts.rain-from", "2.5", "homehub.alerts.rain-check", "06:30"));
        AlertProperties bound = new Binder(List.of(source), null, ApplicationConversionService.getSharedInstance())
                .bindOrCreate("homehub.alerts", AlertProperties.class);

        assertThat(bound.freezeBelow()).as("empty = off").isNull();
        assertThat(bound.freezeCheck()).isEqualTo(LocalTime.of(19, 0));
        assertThat(bound.rainFrom()).isEqualTo(2.5);
        assertThat(bound.rainCheck()).isEqualTo(LocalTime.of(6, 30));
    }

    @Test
    void anEmptyLimitSwitchesTheNoticeOff() {
        WeatherAlerts off = new WeatherAlerts(settings(null, null), HUB);
        assertThat(off.due(SUNDAY_7AM)).isFalse();
        assertThat(off.due(SUNDAY_7PM)).isFalse();
        assertThat(off.check(SUNDAY_7PM, COLD_AND_WET)).isEmpty();
    }
}
