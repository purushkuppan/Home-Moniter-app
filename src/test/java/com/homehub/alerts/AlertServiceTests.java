package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Alerts wired into the running app, with the settings from application.properties. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/alerts.db",
        "homehub.connectors.enabled=false"
})
class AlertServiceTests {

    @LocalServerPort int port;
    @Autowired AlertProperties settings;
    @Autowired EventRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM events");
    }

    @Test
    void defaultSettingsKeepDeviceNamesWithSlashes() {
        assertThat(settings.doorOpen()).containsEntry("Refrigerator/Fridge", Duration.ofMinutes(1))
                .containsEntry("Refrigerator/Freezer", Duration.ofMinutes(2));
        assertThat(settings.tooWarm()).containsEntry("Refrigerator/Fridge", 45.0).containsEntry("Refrigerator/Freezer", 10.0);
        assertThat(settings.tooWarmFor()).isEqualTo(Duration.ofMinutes(15));
        assertThat(settings.finished()).containsExactly("Washer", "Dryer");
        assertThat(settings.sendTo()).as("phones are set for the hub only").isEmpty();
    }

    @Test
    void aFinishedWashIsStoredAsANoticeEvent() throws Exception {
        repository.add("Washer", "machineState", "run");
        repository.add("Washer", "machineState", "stop");

        List<Event> latest = List.of();
        for (int i = 0; i < 50 && latest.stream().noneMatch(e -> e.type().equals("notice")); i++) {
            Thread.sleep(100);   // alerts are raised on their own thread
            latest = repository.latest(10);
        }
        assertThat(latest).extracting(Event::device, Event::type, Event::value)
                .contains(tuple("Washer", "notice", "Washer finished"));
    }

    @Test
    void apiListsActiveProblems() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/alerts")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }
}
