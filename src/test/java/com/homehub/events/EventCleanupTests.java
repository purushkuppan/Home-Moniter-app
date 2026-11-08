package com.homehub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/cleanup.db",
        "homehub.connectors.enabled=false",
        "homehub.alerts.enabled=false"   // alerts would add events of their own
})
class EventCleanupTests {

    private static final ZoneId HUB = ZoneId.of("America/Chicago");
    private static final Instant NOW = Instant.parse("2026-11-08T16:00:00Z");   // 10:00 at the hub
    private static final Clock CLOCK = Clock.fixed(NOW, HUB);
    private static final EventProperties DEFAULTS = new EventProperties(90, 365, LocalTime.of(3, 30));

    @Autowired EventRepository repository;
    @Autowired EventProperties settings;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM events");
    }

    private void add(String device, String type, String value, int daysAgo) {
        repository.save(new Event(null, device, type, value, NOW.minus(Duration.ofDays(daysAgo))));
    }

    private EventCleanup cleanup(EventProperties properties) {
        return new EventCleanup(properties, jdbc, CLOCK, EventCleanup.BATCH_SIZE);
    }

    private List<Event> all() {
        return repository.latest(10_000);
    }

    @Test
    void settingsBindWithTheirDefaults() {
        assertThat(settings.keepDays()).isEqualTo(90);
        assertThat(settings.keepAlertDays()).isEqualTo(365);
        assertThat(settings.cleanupAt()).isEqualTo(LocalTime.of(3, 30));
    }

    @Test
    void zeroOrEmptyMeansKeepForever() {
        EventProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "homehub.events.keep-days", "0",
                "homehub.events.keep-alert-days", "",
                "homehub.events.cleanup-at", "04:15")))
                .bindOrCreate("homehub.events", EventProperties.class);

        assertThat(bound.keepDays()).isZero();
        assertThat(bound.keepAlertDays()).isZero();
        assertThat(bound.cleanupAt()).isEqualTo(LocalTime.of(4, 15));
        assertThat(bound.cleanupOn()).isFalse();
        assertThat(new EventProperties(null, null, null))
                .isEqualTo(new EventProperties(0, 0, LocalTime.of(3, 30)));
    }

    @Test
    void oldReadingsAndCommandsAreDeletedRecentOnesKept() {
        add("Refrigerator/Fridge", "contact", "open", 100);
        add("Refrigerator/Fridge", "contact", "closed", 95);
        add("Refrigerator/Fridge", "contact", "open", 10);
        add("Refrigerator/Fridge", "contact", "closed", 1);
        add("Refrigerator/Fridge", "command", "Ice maker on", 120);
        add("Refrigerator/Fridge", "command", "Ice maker off", 89);

        assertThat(cleanup(DEFAULTS).run()).isEqualTo(3);

        assertThat(all()).extracting(Event::type, Event::value).containsExactly(
                tuple("command", "Ice maker off"),
                tuple("contact", "closed"),
                tuple("contact", "open"));
    }

    @Test
    void theNewestEventOfEveryReadingIsKeptHoweverOld() {
        add("Refrigerator/Freezer", "contact", "closed", 400);   // a door nobody opened for more than a year
        add("Washer", "machineState", "run", 200);
        add("Washer", "machineState", "stop", 150);
        add("Washer", "alert", "Washer is offline", 800);
        add("Washer", "alertCleared", "Washer is back online", 700);
        List<Event> before = repository.latestPerReading();

        assertThat(cleanup(DEFAULTS).run()).isEqualTo(1);

        assertThat(repository.latestPerReading()).isEqualTo(before);
        assertThat(all()).extracting(Event::value).doesNotContain("run");
    }

    @Test
    void alertsAreKeptLongerThanReadings() {
        add("Refrigerator/Fridge", "temperature", "50", 400);
        add("Refrigerator/Fridge", "alert", "Fridge is too warm", 400);
        add("Refrigerator/Fridge", "alertCleared", "Fridge temperature is back to normal", 399);
        add("Refrigerator/Fridge", "temperature", "52", 300);
        add("Refrigerator/Fridge", "alert", "Fridge is too warm", 300);
        add("Refrigerator/Fridge", "alertCleared", "Fridge temperature is back to normal", 299);
        add("Weather/Freeze", "notice", "Freeze warning: low of 28 °F tonight", 370);
        add("Weather/Freeze", "notice", "Freeze warning: low of 30 °F tonight", 200);
        add("Refrigerator/Fridge", "temperature", "40", 1);

        assertThat(cleanup(DEFAULTS).run()).isEqualTo(5);

        assertThat(all()).extracting(Event::type, Event::value).containsExactly(
                tuple("temperature", "40"),
                tuple("notice", "Freeze warning: low of 30 °F tonight"),
                tuple("alertCleared", "Fridge temperature is back to normal"),
                tuple("alert", "Fridge is too warm"));
        assertThat(all()).extracting(Event::time).allMatch(t -> t.isAfter(NOW.minus(Duration.ofDays(301))));
    }

    @Test
    void zeroKeepsThatGroupForever() {
        add("Washer", "machineState", "run", 500);
        add("Washer", "machineState", "stop", 400);
        add("Washer", "notice", "Washer finished", 500);
        add("Washer", "notice", "Washer finished", 400);

        assertThat(cleanup(new EventProperties(0, 0, null)).run()).isZero();
        assertThat(all()).hasSize(4);

        assertThat(cleanup(new EventProperties(0, 365, null)).run()).isEqualTo(1);
        assertThat(all()).extracting(Event::type).containsExactly("notice", "machineState", "machineState");

        assertThat(cleanup(new EventProperties(90, 0, null)).run()).isEqualTo(1);
        assertThat(all()).extracting(Event::type).containsExactly("notice", "machineState");
    }

    @Test
    void deletesInBatchesUntilNothingIsLeft() {
        for (int i = 0; i < 10; i++) add("Refrigerator/Fridge", "contact", i % 2 == 0 ? "open" : "closed", 200 - i);
        add("Refrigerator/Fridge", "contact", "open", 1);

        assertThat(new EventCleanup(DEFAULTS, jdbc, CLOCK, 3).run()).isEqualTo(10);

        assertThat(all()).extracting(Event::value).containsExactly("open");
    }

    @Test
    void runsDailyAtTheLocalCleanupTime() {
        EventCleanup cleanup = cleanup(DEFAULTS);

        // 10:00 at the hub: tomorrow 03:30
        assertThat(cleanup.untilNextRun(NOW)).isEqualTo(Duration.ofHours(17).plusMinutes(30));
        // 02:00 at the hub: today 03:30
        assertThat(cleanup.untilNextRun(Instant.parse("2026-11-09T08:00:00Z"))).isEqualTo(Duration.ofMinutes(90));
        // A timer that fires a moment early doesn't run twice
        assertThat(cleanup.untilNextRun(Instant.parse("2026-11-09T09:29:59Z"))).isEqualTo(Duration.ofHours(24).plusSeconds(1));
    }
}
