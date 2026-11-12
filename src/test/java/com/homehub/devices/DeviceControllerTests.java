package com.homehub.devices;

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

/** The Devices list over HTTP (#99): names and rooms, never entity ids; choices need the PIN. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/device-api.db",
        "homehub.connectors.enabled=false",
        "homehub.alerts.enabled=false",
        "homehub.control.pin=4821"
})
class DeviceControllerTests {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired DeviceCatalog catalog;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void hub() {
        jdbc.update("DELETE FROM devices");
        catalog.sync(DeviceCatalogTests.hub().build());
    }

    private HttpResponse<String> send(String method, String path, String pin, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (pin != null) request.header("X-HomeHub-Pin", pin);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long idOf(String haName) {
        return catalog.all().stream().filter(d -> d.haName().equals(haName)).findFirst().orElseThrow().id();
    }

    @Test
    void theListShowsNewDevicesFirstWithWhatTheyMeasureAndNoEntityIds() throws Exception {
        String body = send("GET", "/api/devices", null, null).body();

        assertThat(body.indexOf("\"status\":\"new\"")).isLessThan(body.indexOf("\"status\":\"confirmed\""));
        assertThat(body).contains("\"name\":\"Water Leak XS Sensor\"", "\"kindLabel\":\"Leak sensor\"", "\"room\":\"Kitchen\"",
                "\"maker\":\"Zooz ZSE42\"", "\"measures\":[\"Water\",\"Battery\"]",
                "\"measures\":[\"Open or closed\",\"Temperature\"]",   // the fridge
                "\"measures\":[\"On or off\",\"Power use\"]",          // the plug
                "\"suggestedName\":\"purushphone\"");
        // Zero technical exposure: no entity ids, no Home Assistant device ids.
        assertThat(body).doesNotContain("binary_sensor.", "sensor.", "switch.", "\"haDeviceId\"", "\"entities\"");
    }

    @Test
    void confirmingNeedsThePinAndAName() throws Exception {
        long phone = idOf("purushphone");

        assertThat(send("POST", "/api/devices/" + phone, null, "{\"action\":\"confirm\",\"name\":\"Phone\"}").statusCode())
                .isEqualTo(401);
        assertThat(send("POST", "/api/devices/" + phone, "0000", "{\"action\":\"confirm\",\"name\":\"Phone\"}").statusCode())
                .isEqualTo(401);
        assertThat(catalog.all()).filteredOn(d -> d.id() == phone).allMatch(d -> d.status() == Device.Status.NEW);

        HttpResponse<String> noName = send("POST", "/api/devices/" + phone, "4821", "{\"action\":\"confirm\",\"name\":\"\"}");
        assertThat(noName.statusCode()).isEqualTo(400);
        assertThat(noName.body()).contains("Give it a name");

        HttpResponse<String> ok = send("POST", "/api/devices/" + phone, "4821", "{\"action\":\"confirm\",\"name\":\"Purush's phone\"}");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("Purush's phone is watched now.", "\"status\":\"confirmed\"");
    }

    @Test
    void hidingAndShowingAgain() throws Exception {
        long stick = idOf("800 Series Long Range USB Controller");

        HttpResponse<String> hidden = send("POST", "/api/devices/" + stick, "4821", "{\"action\":\"hide\"}");
        assertThat(hidden.statusCode()).isEqualTo(200);
        assertThat(hidden.body()).contains("is hidden.", "\"status\":\"hidden\"");
        String list = send("GET", "/api/devices", null, null).body();
        assertThat(list.lastIndexOf("\"status\":\"hidden\"")).as("hidden devices last").isGreaterThan(list.lastIndexOf("\"status\":\"confirmed\""));

        HttpResponse<String> shown = send("POST", "/api/devices/" + stick, "4821", "{\"action\":\"show\"}");
        assertThat(shown.body()).contains("back on the list to review", "\"status\":\"new\"");
    }

    @Test
    void unknownDevicesAndActionsAreRefused() throws Exception {
        assertThat(send("POST", "/api/devices/99999", "4821", "{\"action\":\"hide\"}").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/devices/" + idOf("purushphone"), "4821", "{\"action\":\"delete\"}").statusCode())
                .isEqualTo(400);
    }
}
