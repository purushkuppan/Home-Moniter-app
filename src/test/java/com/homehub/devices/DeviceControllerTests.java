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
    @Autowired com.homehub.events.EventRepository events;
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
        // Named as its readings are shown (homehub.connectors.homeassistant.names), not "Water Leak XS Sensor".
        assertThat(body).contains("\"name\":\"Kitchen leak sensor\"", "\"kindLabel\":\"Leak sensor\"", "\"room\":\"Kitchen\"",
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
    void eachDeviceSaysWhatItAlertsAboutAndADoorsAlertCanBeChanged() throws Exception {
        catalog.sync(DeviceCatalogTests.hubWithBackDoor().build());
        String list = send("GET", "/api/devices", null, null).body();
        assertThat(list).contains("\"alerts\":[\"Critical alert when wet\",\"Low battery warning\"]",
                "\"alerts\":[\"Alert when open longer than 10 min (default), a warning at night\",\"Low battery warning\"]",
                "\"openForChoices\":[0,1,2,5,10,15,30,60]", "\"defaultOpenFor\":10");

        long door = idOf("Backdoor openclose");
        assertThat(send("POST", "/api/devices/" + door, null, "{\"action\":\"open-for\",\"minutes\":2}").statusCode())
                .as("needs the PIN").isEqualTo(401);
        HttpResponse<String> shorter = send("POST", "/api/devices/" + door, "4821", "{\"action\":\"open-for\",\"minutes\":2}");
        assertThat(shorter.statusCode()).isEqualTo(200);
        assertThat(shorter.body()).contains("Back door: Alert when open longer than 2 min.", "\"openFor\":2");
        assertThat(send("POST", "/api/devices/" + door, "4821", "{\"action\":\"open-for\",\"minutes\":0}").body())
                .contains("Back door: No alert when left open.");
        assertThat(send("POST", "/api/devices/" + door, "4821", "{\"action\":\"open-for\",\"minutes\":null}").body())
                .contains("(default)", "\"openFor\":null");
        assertThat(send("POST", "/api/devices/" + door, "4821", "{\"action\":\"open-for\",\"minutes\":7}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void theFridgesAlertsAreChoicesWithTheirDefaults() throws Exception {
        jdbc.update("DELETE FROM events");
        events.add("Refrigerator/Fridge", "contact", "closed");
        events.add("Refrigerator/Freezer", "contact", "closed");
        events.add("Refrigerator/Fridge", "temperature", "43 F");
        events.add("Refrigerator/Freezer", "temperature", "5 F");
        String list = send("GET", "/api/devices", null, null).body();

        // The defaults from application.properties: fridge door 1 min, freezer 2, fridge too warm above 45 °F, freezer 10.
        assertThat(list).contains("\"key\":\"open-for:Fridge\",\"label\":\"Fridge door: alert if open longer than\",\"value\":\"\"",
                "{\"value\":\"\",\"label\":\"1 min (default)\"}",
                "{\"value\":\"\",\"label\":\"2 min (default)\"}",
                "\"key\":\"too-warm:Fridge\",\"label\":\"Fridge: alert when warmer than\"",
                "{\"value\":\"\",\"label\":\"45 °F (default)\"}",
                "{\"value\":\"\",\"label\":\"10 °F (default)\"}",
                "{\"value\":\"off\",\"label\":\"Never\"}",
                "\"key\":\"offline\",\"label\":\"Alert when it stops reporting for\"",
                "{\"value\":\"\",\"label\":\"30 min (default)\"}");

        long fridge = idOf("Refrigerator");
        HttpResponse<String> saved = send("POST", "/api/devices/" + fridge, "4821",
                "{\"action\":\"setting\",\"key\":\"too-warm:Fridge\",\"value\":\"41\"}");
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(saved.body()).contains("Refrigerator: saved.", "\"key\":\"too-warm:Fridge\",\"label\":\"Fridge: alert when warmer than\",\"value\":\"41\"");
        assertThat(send("POST", "/api/devices/" + fridge, null, "{\"action\":\"setting\",\"key\":\"offline\",\"value\":\"off\"}")
                .statusCode()).as("needs the PIN").isEqualTo(401);
        assertThat(send("POST", "/api/devices/" + fridge, "4821", "{\"action\":\"setting\",\"key\":\"offline\",\"value\":\"45\"}")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void unknownDevicesAndActionsAreRefused() throws Exception {
        assertThat(send("POST", "/api/devices/99999", "4821", "{\"action\":\"hide\"}").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/devices/" + idOf("purushphone"), "4821", "{\"action\":\"delete\"}").statusCode())
                .isEqualTo(400);
    }
}
