package com.homehub.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.events.Event;
import com.homehub.weather.Weather;
import com.homehub.weather.WeatherWarning;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Daily Home Brief (#21) for a day like the hub's. */
class DailyBriefTests {

    private static final ZoneId DENTON = ZoneId.of("America/Chicago");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 13);   // a Friday
    private static final Instant MORNING = Instant.parse("2026-11-13T13:00:00Z");   // 7:00 AM in Denton

    private final List<Event> day = new ArrayList<>();
    private long id = 1;

    private void at(int minutes, String device, String type, String value) {
        day.add(new Event(id++, device, type, value, MORNING.plus(Duration.ofMinutes(minutes))));
    }

    private void atSeconds(long seconds, String device, String type, String value) {
        day.add(new Event(id++, device, type, value, MORNING.plus(Duration.ofSeconds(seconds))));
    }

    private static Weather forecast() {
        return new Weather("sunny", 70.0, "°F", 40.0, 5.0, "mph", "in", "met.no", Instant.now(), false, List.of(
                new Weather.Day("2026-11-13T12:00:00-06:00", "sunny", 74.0, 51.0, 0.0),
                new Weather.Day("2026-11-14T12:00:00-06:00", "partlycloudy", 72.4, 52.0, 0.15)));
    }

    @Test
    void anOrdinaryDayInAFewLines() {
        // The fridge opened 3 times (the longest 2 min 20 s), the back door once, the freezer once.
        atSeconds(0, "Refrigerator/Fridge", "contact", "open");
        atSeconds(30, "Refrigerator/Fridge", "contact", "closed");
        atSeconds(600, "Refrigerator/Fridge", "contact", "open");
        atSeconds(740, "Refrigerator/Fridge", "contact", "closed");
        atSeconds(1200, "Refrigerator/Fridge", "contact", "open");
        atSeconds(1210, "Refrigerator/Fridge", "contact", "closed");
        at(60, "Back door", "contact", "open");
        at(61, "Back door", "contact", "closed");
        at(90, "Refrigerator/Freezer", "contact", "open");
        at(91, "Refrigerator/Freezer", "contact", "closed");
        // Two loads of washing, one in the dryer.
        at(120, "Washer", "notice", "Washer finished");
        at(200, "Washer", "notice", "Washer finished");
        at(260, "Dryer", "notice", "Dryer finished");
        // The plug: on for 2 h 15 min.
        at(300, "Indoor Smart Plug", "switch", "on");
        at(300, "Indoor Smart Plug", "power", "62 W");
        at(435, "Indoor Smart Plug", "switch", "off");
        at(500, "Kitchen leak sensor", "leak", "dry");

        List<String> lines = DailyBrief.lines(day, forecast(), List.of(), DAY, DENTON);

        assertThat(lines).containsExactly(
                "Today at home, Fri Nov 13:",
                "• Fridge door opened 3 times (longest 2 min 20 s); back door once; freezer door once",
                "• Washer ran 2 cycles, dryer 1",
                "• Indoor Smart Plug was on for 2 h 15 min",
                "• No leaks, no alerts",
                "Tomorrow: Partly cloudy, 72°/52°, rain 0.15 in");
    }

    @Test
    void leaksAlertsAndWarningsAreThere() {
        at(0, "Kitchen leak sensor", "leak", "wet");
        at(0, "Kitchen leak sensor", "alert", "Water leak in the kitchen");
        at(15, "Kitchen leak sensor", "leak", "dry");
        at(400, "Back door", "alert", "Back door has been open for 10 minutes");
        WeatherWarning storm = new WeatherWarning("Winter Storm Warning", WeatherWarning.Level.WARNING, "x",
                Instant.parse("2026-11-14T12:00:00Z"), null, "https://forecast.weather.gov/");
        WeatherWarning watch = new WeatherWarning("Freeze Watch", WeatherWarning.Level.NOTICE, "x", null, null, "x");

        List<String> lines = DailyBrief.lines(day, null, List.of(storm, watch), DAY, DENTON);

        assertThat(lines).containsExactly(
                "Today at home, Fri Nov 13:",
                "• Water detected by Kitchen leak sensor at 7:00 AM",
                "• 2 alerts, the first: Water leak in the kitchen",
                "• In force: Winter Storm Warning until Sat 6:00 AM");   // a watch isn't worth a line; no forecast
    }

    @Test
    void aQuietDayStillSaysSo() {
        assertThat(DailyBrief.lines(List.of(), null, List.of(), DAY, DENTON))
                .containsExactly("Today at home, Fri Nov 13:", "• No alerts");
    }

    @Test
    void quickLooksAndJustSwitchedOnPlugsReadNaturally() {
        atSeconds(0, "Refrigerator/Fridge", "contact", "open");
        atSeconds(20, "Refrigerator/Fridge", "contact", "closed");
        atSeconds(30, "Indoor Smart Plug", "switch", "on");
        atSeconds(40, "Lamp plug", "switch", "on");
        atSeconds(50, "Lamp plug", "switch", "off");
        assertThat(DailyBrief.doors(day)).as("a quick look: no longest").isEqualTo("Fridge door opened once");
        assertThat(DailyBrief.switches(day)).containsExactly("Indoor Smart Plug is on now", "Lamp plug was on for less than a minute");
    }

    @Test
    void aPlugStillOnIsCountedToTheLastEvent() {
        at(0, "Indoor Smart Plug", "switch", "on");
        at(90, "Back door", "contact", "open");
        assertThat(DailyBrief.switches(day)).containsExactly("Indoor Smart Plug was on for 1 h 30 min, still on");
    }

    @Test
    void durationsAndConditionsReadNaturally() {
        assertThat(DailyBrief.duration(Duration.ofSeconds(45))).isEqualTo("45 s");
        assertThat(DailyBrief.duration(Duration.ofSeconds(140))).isEqualTo("2 min 20 s");
        assertThat(DailyBrief.duration(Duration.ofMinutes(25))).isEqualTo("25 min");
        assertThat(DailyBrief.duration(Duration.ofMinutes(120))).isEqualTo("2 h");
        assertThat(DailyBrief.condition("lightning-rainy")).isEqualTo("Thunderstorms");
        assertThat(DailyBrief.condition("rainy")).isEqualTo("Rainy");
    }
}
