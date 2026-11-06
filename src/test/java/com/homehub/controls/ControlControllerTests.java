package com.homehub.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The HTTP side of device controls, against a Home Assistant address that never answers. */
class ControlControllerTests {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static HttpResponse<String> send(int port, String method, String path, String pin, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (pin != null) request.header(ControlController.PIN_HEADER, pin);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.datasource.url=jdbc:sqlite:target/test-data/controls.db",
            "homehub.connectors.enabled=false",
            "homeassistant.url=http://127.0.0.1:9",   // nothing listens here
            "homeassistant.token=test-token",
            "homehub.control.pin=4821"
    })
    class WithPin {

        @LocalServerPort int port;

        @Test
        void theRightPinUnlocks() throws Exception {
            assertThat(send(port, "POST", "/api/controls/unlock", "4821", null).statusCode()).isEqualTo(200);
        }

        @Test
        void aWrongPinIsRejectedBeforeAnythingReachesHomeAssistant() throws Exception {
            HttpResponse<String> response = send(port, "POST", "/api/controls/number.kitchen_refrigerator_fridge_temperature",
                    "0000", "{\"value\":\"38\"}");
            // 401, not 502: the PIN was checked first, so the unreachable Home Assistant was never contacted.
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("Wrong PIN");
            assertThat(send(port, "POST", "/api/controls/unlock", null, null).statusCode()).isEqualTo(401);
            send(port, "POST", "/api/controls/unlock", "4821", null);   // reset the failure count for other tests
        }

        @Test
        void devicesOutsideTheAllowlistAreRefusedEvenWithThePin() throws Exception {
            HttpResponse<String> response = send(port, "POST", "/api/controls/lock.front_door", "4821", "{\"value\":\"unlock\"}");
            assertThat(response.statusCode()).isEqualTo(403);
        }

        @Test
        void listingExplainsWhenHomeAssistantIsUnreachable() throws Exception {
            HttpResponse<String> response = send(port, "GET", "/api/controls", null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"enabled\":false").contains("not reachable");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.datasource.url=jdbc:sqlite:target/test-data/controls-nopin.db",
            "homehub.connectors.enabled=false",
            "homeassistant.url=http://127.0.0.1:9",
            "homeassistant.token=test-token",
            "homehub.control.pin="
    })
    class WithoutPin {

        @LocalServerPort int port;

        @Test
        void controlsStayDisabled() throws Exception {
            HttpResponse<String> response = send(port, "POST", "/api/controls/unlock", "4821", null);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("PIN is set");
        }
    }
}
