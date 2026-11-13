package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.homehub.alerts.Alert.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NtfyNotifierTests {

    private static final String URL = "http://hub:2586";
    private static final Instant T = Instant.parse("2026-11-13T18:00:00Z");
    private static final Alert LEAK = new Alert("leak:kitchen leak sensor", Level.PROBLEM, "Kitchen leak sensor",
            "Water leak in the kitchen", T);

    private static AlertProperties alerts(String link) {
        return new AlertProperties(List.of(), link, Map.of(), Map.of(), null, List.of(), Map.of(), null, null, null,
                null, null, null, Map.of());
    }

    @Test
    void aCriticalAlertIsUrgentWithAnImOnItButtonThatAnswersKudil() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(URL + "/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tk_secret"))
                .andExpect(content().json("""
                        {"topic":"kudil-alerts","title":"Kudil",
                         "message":"Water leak in the kitchen\\nTurn off the water at the main valve, then dry the floor and find where it comes from.",
                         "priority":5,"tags":["rotating_light"],"click":"http://kudil:8080",
                         "actions":[{"action":"http","label":"I'm on it","url":"http://kudil:8080/api/alerts/ack",
                                     "method":"POST","headers":{"Content-Type":"application/json"},
                                     "body":"{\\"key\\":\\"leak:kitchen leak sensor\\",\\"who\\":\\"Android phone\\"}",
                                     "clear":true}]}""", JsonCompareMode.LENIENT))
                .andRespond(withSuccess());
        NtfyNotifier ntfy = new NtfyNotifier(builder, new NtfyProperties(URL + "/", "kudil-alerts", "", "tk_secret"),
                alerts("http://kudil:8080/"));

        assertThat(ntfy.configured()).isTrue();
        assertThat(ntfy.send(LEAK, true)).isEqualTo(1);
        server.verify();
    }

    @Test
    void severitySetsThePriorityAndOnlyCriticalAlertsHaveAButton() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/")).andExpect(jsonPath("$.priority").value(4))
                .andExpect(jsonPath("$.tags[0]").value("warning")).andExpect(jsonPath("$.actions").doesNotExist())
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess());
        server.expect(requestTo(URL + "/")).andExpect(jsonPath("$.priority").value(3))
                .andExpect(jsonPath("$.message").value("Kitchen leak sensor is dry again.")).andRespond(withSuccess());
        server.expect(requestTo(URL + "/")).andExpect(jsonPath("$.priority").value(2)).andRespond(withSuccess());
        NtfyNotifier ntfy = new NtfyNotifier(builder, new NtfyProperties(URL, "kudil", "", ""), alerts(""));

        ntfy.send(LEAK, false);
        ntfy.send(new Alert(LEAK.key(), Level.CLEARED, LEAK.device(), "Kitchen leak sensor is dry again.", T), false);
        ntfy.send(new Alert("finished:washer", Level.NOTICE, "Washer", "Washer finished", T), false);
        server.verify();
    }

    @Test
    void theIPhoneFallbackIsAnOrdinaryPushOnItsOwnTopic() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/")).andExpect(jsonPath("$.topic").value("kudil-iphone"))
                .andExpect(jsonPath("$.priority").value(4)).andExpect(jsonPath("$.actions").doesNotExist())
                .andRespond(withSuccess());
        NtfyNotifier ntfy = new NtfyNotifier(builder, new NtfyProperties(URL, "kudil", "kudil-iphone", ""),
                alerts("http://kudil:8080"));

        assertThat(ntfy.sendFallback(LEAK)).isEqualTo(1);
        server.verify();
    }

    @Test
    void aFailedSendIsCountedNotThrown() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/")).andRespond(withServerError());
        NtfyNotifier ntfy = new NtfyNotifier(builder, new NtfyProperties(URL, "kudil", "", ""), alerts(""));

        assertThat(ntfy.send(LEAK, true)).isZero();
    }

    @Test
    void offWithoutAUrlOrTopicOrWithAnOddTopic() {
        assertThat(new NtfyNotifier(RestClient.builder(), NtfyProperties.off(), alerts("")).send(LEAK, true)).isZero();
        NtfyNotifier noFallback = new NtfyNotifier(RestClient.builder(), new NtfyProperties(URL, "kudil", "", ""), alerts(""));
        assertThat(noFallback.sendFallback(LEAK)).isZero();
        assertThat(new NtfyNotifier(RestClient.builder(), new NtfyProperties(URL, "kudil/../admin", "", ""), alerts(""))
                .configured()).isFalse();
    }
}
