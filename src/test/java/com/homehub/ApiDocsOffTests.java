package com.homehub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** API docs switched off (#80, ADR 11): neither the spec nor Swagger UI is served, and the API itself still works. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/api-docs-off.db",
        "homehub.connectors.enabled=false",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class ApiDocsOffTests {

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private int status(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void docsAnswerNotFound() throws Exception {
        assertThat(status("/v3/api-docs")).isEqualTo(404);
        assertThat(status("/swagger-ui.html")).isEqualTo(404);
        assertThat(status("/swagger-ui/index.html")).isEqualTo(404);
        assertThat(status("/api/version")).isEqualTo(200);
    }
}
