package com.homehub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API docs (#80, ADR 11): the OpenAPI spec and Swagger UI, on by default. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/api-docs.db",
        "homehub.connectors.enabled=false"
})
class ApiDocsTests {

    /** Every endpoint under /api today. */
    static final Set<String> API_PATHS = Set.of(
            "/api/events", "/api/events/latest", "/api/events/stream",
            "/api/controls", "/api/controls/unlock", "/api/controls/{entityId}",
            "/api/alerts", "/api/alerts/ack", "/api/alerts/test",
            "/api/devices", "/api/devices/{id}",
            "/api/weather", "/api/sun", "/api/warnings",
            "/api/brief", "/api/version", "/api/status");

    /** The endpoints that check the household PIN (header X-HomeHub-Pin), as "METHOD path". */
    static final Set<String> PIN_PROTECTED = Set.of(
            "POST /api/controls/unlock", "POST /api/controls/{entityId}",
            "POST /api/alerts/test", "POST /api/devices/{id}");

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode spec() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    /** "METHOD path" of every operation in the spec, with the operation. */
    private static Map<String, JsonNode> operations(JsonNode spec) {
        Map<String, JsonNode> operations = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonNode> path : spec.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                operations.put(op.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey(), op.getValue());
            }
        }
        return operations;
    }

    @Test
    void specListsEveryApiPathAndNothingElse() throws Exception {
        JsonNode spec = spec();
        List<String> paths = new ArrayList<>(spec.get("paths").propertyNames());
        assertThat(paths).containsExactlyInAnyOrderElementsOf(API_PATHS);   // no actuator, no dashboard, no /error
        assertThat(spec.at("/info/title").asString()).isEqualTo("Kudil API");

        // The same version as /api/version ("dev" when run from source, the release number on the hub).
        String running = json.readTree(get("/api/version").body()).get("version").asString();
        assertThat(spec.at("/info/version").asString()).isEqualTo(running);
    }

    @Test
    void pinIsOneSecuritySchemeWithoutAnExample() throws Exception {
        JsonNode schemes = spec().at("/components/securitySchemes");
        assertThat(schemes.propertyNames()).containsExactly(ApiDocs.PIN);
        JsonNode pin = schemes.get(ApiDocs.PIN);
        assertThat(pin.get("type").asString()).isEqualTo("apiKey");
        assertThat(pin.get("in").asString()).isEqualTo("header");
        assertThat(pin.get("name").asString()).isEqualTo("X-HomeHub-Pin");
        assertThat(pin.has("example")).isFalse();
        // No example PIN anywhere in the spec.
        assertThat(get("/v3/api-docs").body()).doesNotContainIgnoringCase("\"example\"");
    }

    @Test
    void onlyThePinProtectedPostsRequireThePin() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.has("security")).as("no PIN required globally").isFalse();
        Map<String, JsonNode> operations = operations(spec);
        assertThat(operations.keySet()).containsAll(PIN_PROTECTED).contains("GET /api/controls", "POST /api/alerts/ack");

        operations.forEach((operation, op) -> {
            boolean needsPin = op.has("security") && op.get("security").toString().contains("\"" + ApiDocs.PIN + "\"");
            assertThat(needsPin).as(operation + " requires the PIN").isEqualTo(PIN_PROTECTED.contains(operation));
            // The PIN is sent by "Authorize", never typed as an ordinary header parameter.
            assertThat(String.valueOf(op.get("parameters"))).as(operation).doesNotContain("X-HomeHub-Pin");
            assertThat(op.at("/summary").asString()).as(operation + " has a summary").isNotBlank();
        });
    }

    @Test
    void liveFeedIsDescribedAsAnEventStream() throws Exception {
        JsonNode stream = spec().at("/paths/~1api~1events~1stream/get");
        assertThat(stream.at("/responses/200/content").propertyNames()).containsExactly("text/event-stream");
        assertThat(stream.at("/description").asString()).contains("Swagger UI can't play a stream");
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        HttpResponse<String> ui = get("/swagger-ui.html");   // redirects to /swagger-ui/index.html
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
    }
}
