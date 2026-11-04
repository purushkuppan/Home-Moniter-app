package com.homehub.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;

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
    void latestRespectsLimit() {
        for (int i = 0; i < 5; i++) repository.add("d", "t", String.valueOf(i));
        assertThat(repository.latest(3)).extracting(Event::value).containsExactly("4", "3", "2");
    }
}
