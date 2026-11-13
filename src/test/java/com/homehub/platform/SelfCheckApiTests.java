package com.homehub.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.events.EventRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The post-deploy check in the running app, as install-release.sh calls it (#124). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/selfcheck.db",
        "homehub.connectors.enabled=false",
        "homeassistant.url=", "homeassistant.token="
})
class SelfCheckApiTests {

    @LocalServerPort int port;
    @Autowired EventRepository events;

    @Test
    void alertsWorkAndTheTestEventIsGoneAgain() throws Exception {
        long before = events.latest(1000).stream().filter(e -> SelfCheck.TYPE.equals(e.type())).count();
        HttpResponse<String> answer = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/selfcheck"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(answer.statusCode()).isEqualTo(200);
        // install-release.sh reads exactly these: "ok":true, and "name":"…","ok":false for the failed ones.
        assertThat(answer.body()).startsWith("{\"ok\":true,").contains(
                "{\"name\":\"stored\",\"ok\":true,", "{\"name\":\"live stream\",\"ok\":true,",
                "{\"name\":\"rules\",\"ok\":true,\"detail\":\"A pretend leak raises a critical alert (not sent).\"}",
                "{\"name\":\"home assistant\",\"ok\":true,", "{\"name\":\"devices\",\"ok\":true,");
        assertThat(events.latest(1000).stream().filter(e -> SelfCheck.TYPE.equals(e.type())).count())
                .as("the test event is deleted again").isEqualTo(before);
        assertThat(events.latest(1000)).as("the pretend leak stored nothing")
                .noneMatch(e -> SelfCheck.DEVICE.equals(e.device()));
    }

    @Test
    void onlyFromTheHubItself() {
        assertThat(SelfCheckController.fromHub("127.0.0.1")).isTrue();
        assertThat(SelfCheckController.fromHub("::1")).isTrue();
        assertThat(SelfCheckController.fromHub("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(SelfCheckController.fromHub("192.168.5.20")).isFalse();
        assertThat(SelfCheckController.fromHub("100.101.102.103")).as("a Tailscale phone").isFalse();
        assertThat(SelfCheckController.fromHub(null)).isFalse();
    }
}
