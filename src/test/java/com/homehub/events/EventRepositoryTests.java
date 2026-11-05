package com.homehub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/repository.db",
        "homehub.connectors.enabled=false"
})
class EventRepositoryTests {

    @Autowired EventRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM events");
    }

    @Test
    void saveKeepsEventTimeAndLatestIsNewestFirst() {
        Instant earlier = Instant.now().minus(1, ChronoUnit.HOURS);
        repository.save(new Event(null, "fridge", "contact", "open", earlier));
        repository.add("washer", "machineState", "run");

        var latest = repository.latest(50);

        assertThat(latest).extracting(Event::device).containsExactly("washer", "fridge");
        assertThat(latest.get(1).time()).isEqualTo(earlier);
        assertThat(latest.get(0).id()).isGreaterThan(latest.get(1).id());
    }

    @Test
    void saveIfChangedSkipsRepeatsOfTheLastStoredValue() {
        Event closed = new Event(null, "Refrigerator/Fridge", "contact", "closed", null);
        Event temp = new Event(null, "Refrigerator/Fridge", "temperature", "43 F", null);

        // First run of the app
        assertThat(repository.saveIfChanged(closed)).isPresent();
        assertThat(repository.saveIfChanged(temp)).isPresent();
        // App restarts and reports the same state again: nothing new is stored
        assertThat(repository.saveIfChanged(closed)).isEmpty();
        assertThat(repository.saveIfChanged(temp)).isEmpty();
        // Real changes are stored, compared per device + type
        assertThat(repository.saveIfChanged(new Event(null, "Refrigerator/Fridge", "contact", "open", null))).isPresent();
        assertThat(repository.saveIfChanged(new Event(null, "Refrigerator/Freezer", "contact", "closed", null))).isPresent();
        assertThat(repository.saveIfChanged(closed)).isPresent();   // closed again after open

        assertThat(repository.latest(50)).extracting(Event::device, Event::type, Event::value).containsExactly(
                tuple("Refrigerator/Fridge", "contact", "closed"),
                tuple("Refrigerator/Freezer", "contact", "closed"),
                tuple("Refrigerator/Fridge", "contact", "open"),
                tuple("Refrigerator/Fridge", "temperature", "43 F"),
                tuple("Refrigerator/Fridge", "contact", "closed"));
    }

    @Test
    void latestRespectsLimit() {
        for (int i = 0; i < 5; i++) repository.add("d", "t", String.valueOf(i));
        assertThat(repository.latest(3)).extracting(Event::value).containsExactly("4", "3", "2");
    }
}
