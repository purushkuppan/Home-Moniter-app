package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.homehub.alerts.Alert.Level;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AlertNotifierTests {

    private static final String URL = "http://ha.test:8123";
    private static final Alert DOOR = new Alert("door:refrigerator/fridge", Level.PROBLEM, "Refrigerator/Fridge",
            "Fridge door has been open for 1 minute", Instant.parse("2026-11-06T18:01:00Z"));

    private static AlertProperties settings(List<String> notify, String link) {
        return new AlertProperties(notify, link, Map.of(), Map.of(), Duration.ofMinutes(15), List.of(), Map.of(), null,
                null, null, null, null, null, null);
    }

    @Test
    void sendsToEveryPhoneWithALinkToTheAlertOnTheDashboard() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String phone : List.of("mobile_app_phone_one", "mobile_app_phone_two")) {
            server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/" + phone))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer secret"))
                    .andExpect(content().json("""
                            {"title":"Kudil","message":"Fridge door has been open for 1 minute\\nClose it and check that nothing blocks the door.",
                             "data":{"tag":"homehub-door:refrigerator/fridge","group":"homehub",
                                     "url":"http://hub:8080/#alert=door%3Arefrigerator%2Ffridge&at=2026-11-06T18%3A01%3A00Z",
                                     "clickAction":"http://hub:8080/#alert=door%3Arefrigerator%2Ffridge&at=2026-11-06T18%3A01%3A00Z"}}"""))
                    .andRespond(withSuccess());
        }
        AlertNotifier notifier = new AlertNotifier(builder, new HomeAssistantProperties(URL, "secret"),
                settings(List.of("notify.mobile_app_phone_one", "notify.mobile_app_phone_two"), "http://hub:8080"));

        notifier.send(DOOR);

        server.verify();
    }

    @Test
    void aCriticalAlertRingsAsAnAlarmWithAnImOnItButtonButItsAllClearIsOrdinary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String phone : List.of("mobile_app_phone", "mobile_app_amma")) {
            server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/" + phone))
                    .andExpect(content().json("""
                            {"title":"Kudil","message":"Water leak in the kitchen\\nTurn off the water at the main valve, then dry the floor and find where it comes from.",
                             "data":{"tag":"homehub-leak:kitchen leak sensor","priority":"high","ttl":0,
                                     "channel":"alarm_stream",
                                     "push":{"sound":{"name":"default","critical":1,"volume":1.0},
                                             "interruption-level":"critical"},
                                     "actions":[{"action":"KUDIL_ACK|%s|leak:kitchen leak sensor","title":"I'm on it"}]}}"""
                            .formatted(phone)))
                    .andRespond(withSuccess());
        }
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_phone"))
                .andExpect(jsonPath("$.data.channel").doesNotExist())
                .andExpect(jsonPath("$.data.actions").doesNotExist())
                .andExpect(jsonPath("$.data.push.interruption-level").value("active"))
                .andExpect(jsonPath("$.message").value("Kitchen leak sensor is dry again."))
                .andRespond(withSuccess());
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_amma"))
                .andRespond(withSuccess());
        AlertNotifier notifier = new AlertNotifier(builder, new HomeAssistantProperties(URL, "secret"),
                settings(List.of("notify.mobile_app_phone", "notify.mobile_app_amma"), ""));

        assertThat(notifier.send(new Alert("leak:kitchen leak sensor", Level.PROBLEM, "Kitchen leak sensor",
                "Water leak in the kitchen", Instant.parse("2026-11-10T18:00:00Z")), true)).isEqualTo(2);
        notifier.send(new Alert("leak:kitchen leak sensor", Level.CLEARED, "Kitchen leak sensor",
                "Kitchen leak sensor is dry again.", Instant.parse("2026-11-10T18:10:00Z")));

        server.verify();
    }

    @Test
    void onSpeakingPhonesACriticalAlertIsAlsoSpokenAtFullAlarmVolume() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // The Android phone: the notification with its button, then the spoken alert.
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_android"))
                .andExpect(jsonPath("$.data.channel").value("alarm_stream"))
                .andRespond(withSuccess());
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_android"))
                .andExpect(content().json("""
                        {"message":"TTS","data":{"tts_text":"Kudil alert. Water leak in the kitchen. Turn off the water at the main valve, then dry the floor and find where it comes from.",
                         "media_stream":"alarm_stream_max","ttl":0,"priority":"high"}}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess());
        // The iPhone: only the notification (a "TTS" message would show as text there).
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_iphone"))
                .andExpect(jsonPath("$.message").value("Water leak in the kitchen\nTurn off the water at the main valve, then dry the floor and find where it comes from."))
                .andRespond(withSuccess());
        // An ordinary alert is never spoken.
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_android"))
                .andExpect(jsonPath("$.message").value("Fridge door has been open for 1 minute\nClose it and check that nothing blocks the door."))
                .andRespond(withSuccess());
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_iphone"))
                .andRespond(withSuccess());
        AlertNotifier notifier = new AlertNotifier(builder, new HomeAssistantProperties(URL, "secret"),
                settings(List.of("notify.mobile_app_android", "notify.mobile_app_iphone"), ""),
                new CriticalAlertProperties(null, null, null, null, null, List.of("notify.mobile_app_android")));

        assertThat(notifier.send(new Alert("leak:kitchen leak sensor", Level.PROBLEM, "Kitchen leak sensor",
                "Water leak in the kitchen", Instant.parse("2026-11-12T09:00:00Z")), true)).as("phones").isEqualTo(2);
        notifier.send(DOOR);

        server.verify();
    }

    @Test
    void problemsAreTimeSensitiveAndNoticesPassiveOnIphones() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_phone"))
                .andExpect(jsonPath("$.data.push.interruption-level").value("time-sensitive"))
                .andExpect(jsonPath("$.data.channel").doesNotExist())
                .andRespond(withSuccess());
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_phone"))
                .andExpect(jsonPath("$.data.push.interruption-level").value("passive"))
                .andRespond(withSuccess());
        AlertNotifier notifier = new AlertNotifier(builder, new HomeAssistantProperties(URL, "secret"),
                settings(List.of("notify.mobile_app_phone"), ""));

        notifier.send(DOOR);
        notifier.send(new Alert("finished:washer", Level.NOTICE, "Washer", "Washer finished", Instant.now()));

        server.verify();
    }

    @Test
    void clearingRemovesTheNotificationFromTheGivenPhones() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_amma"))
                .andExpect(content().json("""
                        {"message":"clear_notification","data":{"tag":"homehub-leak:kitchen leak sensor"}}"""))
                .andRespond(withSuccess());
        AlertNotifier notifier = new AlertNotifier(builder, new HomeAssistantProperties(URL, "secret"),
                settings(List.of("notify.mobile_app_phone", "notify.mobile_app_amma"), ""));

        notifier.clear("leak:kitchen leak sensor", List.of("mobile_app_amma"));

        server.verify();
    }

    @Test
    void aFailingPhoneDoesNotStopTheOthersAndNothingIsThrown() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_gone"))
                .andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/services/notify/mobile_app_phone"))
                .andRespond(withSuccess());
        AlertNotifier notifier = new AlertNotifier(builder, new HomeAssistantProperties(URL, "secret"),
                settings(List.of("notify.mobile_app_gone", "notify.mobile_app_phone"), ""));

        notifier.send(DOOR);

        server.verify();
    }

    @Test
    void sendsNothingWithoutPhonesOrHomeAssistantOrWithABadTarget() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();   // expects no requests

        new AlertNotifier(builder, new HomeAssistantProperties(URL, "secret"), settings(List.of(), "")).send(DOOR);
        new AlertNotifier(builder, new HomeAssistantProperties(null, null),
                settings(List.of("notify.mobile_app_phone"), "")).send(DOOR);
        new AlertNotifier(builder, new HomeAssistantProperties(URL, "secret"),
                settings(List.of("light.turn_on", "notify.../../states"), "")).send(DOOR);

        server.verify();
    }
}
