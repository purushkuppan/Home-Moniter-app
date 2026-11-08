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
        "homehub.connectors.enabled=false",
        "homehub.alerts.enabled=false"   // alerts would add events of their own
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
    void dashboardMustBeRevalidatedSoPhonesDontShowAnOldCopy() throws Exception {
        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-cache"));
        // Users see the product name; HomeHub is only the code name.
        assertThat(page.body()).contains("<title>Kudil</title>", "<h1>Kudil</h1>").doesNotContain("HomeHub is not reachable");
        // Every file in the jar is dated 1980; with a Last-Modified header, browsers holding an old copy would be told
        // "not modified" after every deploy.
        assertThat(page.headers().firstValue("Last-Modified")).isEmpty();

        HttpResponse<String> revalidated = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                        .header("If-Modified-Since", "Fri, 01 Feb 1980 00:00:00 GMT").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(revalidated.statusCode()).isEqualTo(200);
    }

    @Test
    void dashboardHasATemperatureUnitSwitchThatIsRememberedPerBrowser() throws Exception {
        String page = get("/");
        assertThat(page).contains("id=\"unitSwitch\"", "aria-label=\"Temperature unit\"",
                "data-unit=\"F\"", "data-unit=\"C\"", ">°F</button>", ">°C</button>");
        // Remembered in the browser only, and every access is guarded (private mode throws).
        assertThat(page).contains("const UNIT_KEY = 'homehub.tempUnit'",
                "try { localStorage.setItem(UNIT_KEY, unit); } catch");
        // Controls keep the device's own number as the value that is sent; only the label is converted.
        assertThat(page).contains("o.value = v;", "sendCommand(c, select.value)");
    }

    @Test
    void aHiddenDashboardMakesNoPeriodicRequests() throws Exception {
        String page = get("/");
        assertThat(page).contains("const watched = () => !paused && !document.hidden;",
                "setInterval(() => { if (watched()) { checkVersion(); load(); } }, SAFETY_RELOAD_MS);",
                "document.addEventListener('visibilitychange', () => { if (watched()) { checkVersion(); load(); } });");
        // Loaded once on start: by the live stream opening, or after 3 s if it doesn't.
        assertThat(page).contains("stream.onopen = () => { checkVersion(); load(); };",
                "setTimeout(() => { if (!everLoaded) load(); }, 3000);");
    }

    @Test
    void temperaturesStayInHomeAssistantsUnitInTheApi() throws Exception {
        repository.add("Refrigerator/Freezer", "temperature", "0 F");
        // Conversion is display only: the API and the database keep the reading as it came in.
        assertThat(get("/api/events/latest")).contains("\"value\":\"0 F\"");
    }

    @Test
    void versionIdentifiesTheRunningServer() throws Exception {
        String version = get("/api/version");
        assertThat(version).contains("\"started\":\"").contains("\"version\":\"");
        assertThat(get("/api/version")).isEqualTo(version);   // stable until the next restart
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
