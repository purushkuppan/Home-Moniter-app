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
import java.time.LocalTime;
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
    @Autowired BackupProperties backup;
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
        assertThat(settings.offline()).containsExactly(java.util.Map.entry("Refrigerator", Duration.ofMinutes(30)));
        assertThat(settings.connectionLostAfter()).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.freezeBelow()).isEqualTo(32.0);
        assertThat(settings.freezeCheck()).isEqualTo(LocalTime.of(19, 0));
        assertThat(settings.rainFrom()).isEqualTo(0.1);
        assertThat(settings.rainCheck()).isEqualTo(LocalTime.of(7, 0));
        assertThat(backup.statusFile()).as("backup alerts are for the hub only").isEmpty();
        assertThat(backup.missingAfter()).isEqualTo(Duration.ofHours(26));
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

    @Autowired CriticalAlertProperties critical;

    private HttpResponse<String> post(String path, String json, String pin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (pin != null) request.header("X-HomeHub-Pin", pin);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String alerts() throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/alerts")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void criticalAlertDefaults() {
        assertThat(critical.kinds()).containsExactly("leak", "smoke", "co", "severe");
        assertThat(critical.repeatEvery()).isEqualTo(Duration.ofMinutes(5));
        assertThat(critical.maxRepeats()).isEqualTo(24);
        assertThat(critical.escalateAfter()).isEqualTo(Duration.ofMinutes(10));
        assertThat(critical.escalateTo()).isEmpty();
    }

    @Test
    void aLeakIsCriticalAndCanBeAnsweredFromTheDashboard() throws Exception {
        repository.add("Kitchen leak sensor", "leak", "dry");
        repository.add("Kitchen leak sensor", "leak", "wet");
        String active = "";
        for (int i = 0; i < 50 && !active.contains("Water leak in the kitchen"); i++) {
            Thread.sleep(100);   // alerts are raised on their own thread
            active = alerts();
        }
        assertThat(active).contains("\"key\":\"leak:kitchen leak sensor\"", "\"critical\":true", "\"acknowledgedBy\":null");
        assertThat(active).as("what to do next (#161)")
                .contains("\"nextStep\":\"Turn off the water at the main valve, then dry the floor and find where it comes from.\"");

        // The ntfy "I'm on it" button (#154) says who answered.
        HttpResponse<String> answer = post("/api/alerts/ack",
                "{\"key\":\"leak:kitchen leak sensor\",\"who\":\"Android phone\"}", null);
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(alerts()).contains("\"acknowledgedBy\":\"Android phone\"");
        assertThat(post("/api/alerts/ack", "{\"key\":\"leak:kitchen leak sensor\"}", null).statusCode())
                .as("already answered").isEqualTo(409);
        assertThat(repository.latest(10)).extracting(Event::type, Event::value)
                .contains(tuple("alertAck", "On it (Android phone): Water leak in the kitchen"));

        repository.add("Kitchen leak sensor", "leak", "dry");   // over: nothing left on the dashboard
        for (int i = 0; i < 50 && alerts().contains("Water leak"); i++) Thread.sleep(100);
        assertThat(alerts()).isEqualTo("[]");
    }

    @Test
    void answeringNeedsAKnownAlertAndTheTestNeedsThePin() throws Exception {
        assertThat(post("/api/alerts/ack", "{\"key\":\"leak:nothing\"}", null).statusCode()).isEqualTo(409);
        assertThat(post("/api/alerts/ack", "{}", null).statusCode()).isEqualTo(400);
        // No PIN is set in the tests: the test alert, which rings the family's phones, stays off.
        HttpResponse<String> test = post("/api/alerts/test", "", "1234");
        assertThat(test.statusCode()).isEqualTo(403);
        assertThat(test.body()).contains("Set a PIN");
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
