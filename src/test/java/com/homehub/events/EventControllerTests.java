package com.homehub.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/controller.db",
        "homehub.connectors.enabled=false"
})
class EventControllerTests {

    @LocalServerPort int port;
    @Autowired EventRepository repository;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM events");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void latestReturnsTheLastValueOfEveryReading() throws Exception {
        repository.add("Dryer", "machineState", "stop");
        for (int i = 0; i < 55; i++) repository.add("Refrigerator/Fridge", "contact", i % 2 == 0 ? "open" : "closed");

        String latest = get("/api/events/latest");
        assertThat(latest).contains("\"device\":\"Dryer\"").contains("\"value\":\"stop\"");
        assertThat(latest).contains("\"device\":\"Refrigerator/Fridge\"").contains("\"value\":\"open\"");

        // The existing API is unchanged: the latest 50 events, which no longer include the dryer.
        String page = get("/api/events");
        assertThat(page).doesNotContain("Dryer");
        assertThat(page.split("\"id\":")).hasSize(51);
    }
}
