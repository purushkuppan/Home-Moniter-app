package com.homehub.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/stream.db",
        "homehub.connectors.enabled=false"
})
class EventStreamTests {

    @LocalServerPort int port;
    @Autowired EventStream stream;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void savedEventIsPushedToOpenStreams() throws Exception {
        HttpResponse<Stream<String>> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/stream"))
                        .header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/event-stream"));

        Iterator<String> lines = response.body().iterator();
        waitUntil(() -> stream.subscriberCount() == 1);

        HttpResponse<String> posted = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"device\":\"Refrigerator/Fridge\",\"type\":\"contact\",\"value\":\"open\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(posted.statusCode()).isEqualTo(201);

        // Read the stream until the pushed event arrives (with a timeout so a failure can't hang the build).
        String received = CompletableFuture.supplyAsync(() -> {
            StringBuilder event = new StringBuilder();
            while (lines.hasNext()) {
                String line = lines.next();
                event.append(line).append('\n');
                if (line.startsWith("data:") && line.contains("Refrigerator/Fridge")) return event.toString();
            }
            return event.toString();
        }).get(10, TimeUnit.SECONDS);

        assertThat(received).contains("event:event").contains("\"type\":\"contact\"").contains("\"value\":\"open\"");
        assertThat(received).containsPattern("id:\\d+");
        response.body().close();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).as("condition within 5s").isTrue();
    }
}
