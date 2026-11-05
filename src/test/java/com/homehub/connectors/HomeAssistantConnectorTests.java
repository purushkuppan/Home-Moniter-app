package com.homehub.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.homehub.events.Event;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HomeAssistantConnectorTests {

    private static final String URL = "http://ha.test:8123";
    private static final HomeAssistantProperties PROPS = new HomeAssistantProperties(URL + "/", "secret");

    private static String states(String fridgeDoor, String washerState) {
        return """
                [
                 {"entity_id":"binary_sensor.kitchen_refrigerator_fridge_door","state":"%s",
                  "last_changed":"2026-11-05T02:00:00+00:00",
                  "attributes":{"device_class":"door","friendly_name":"Refrigerator Fridge door"}},
                 {"entity_id":"sensor.kitchen_refrigerator_freezer_temperature","state":"5",
                  "last_changed":"2026-11-05T02:28:19.297011+00:00",
                  "attributes":{"device_class":"temperature","unit_of_measurement":"°F","friendly_name":"Refrigerator Freezer temperature"}},
                 {"entity_id":"sensor.laundry_room_washer_machine_state","state":"%s",
                  "last_changed":"2026-11-05T01:00:00+00:00",
                  "attributes":{"device_class":"enum","friendly_name":"Washer Machine state"}},
                 {"entity_id":"sensor.laundry_room_washer_power","state":"0",
                  "attributes":{"device_class":"power","unit_of_measurement":"W","friendly_name":"Washer Power"}}
                ]""".formatted(fridgeDoor, washerState);
    }

    private static final String DEVICE_NAMES = """
            binary_sensor.kitchen_refrigerator_fridge_door|Refrigerator
            sensor.kitchen_refrigerator_freezer_temperature|Refrigerator
            sensor.laundry_room_washer_machine_state|Washer
            """;

    private static void expectStates(MockRestServiceServer server, String json) {
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/states"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void mapsEntitiesToEventsAndReturnsOnlyChanges() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var connector = new HomeAssistantConnector(builder, PROPS, HomeAssistantConnector.DEFAULT_INCLUDE, null);

        expectStates(server, states("off", "stop"));
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/template"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(DEVICE_NAMES, MediaType.TEXT_PLAIN));
        // device names are cached: no second template call. The door changed after the first poll.
        expectStates(server, states("on", "stop").replace("2026-11-05T02:00:00+00:00", "2099-01-01T00:00:00+00:00"));

        Instant before = Instant.now();
        List<Event> first = connector.poll();
        List<Event> second = connector.poll();
        server.verify();

        assertThat(first).extracting(Event::device, Event::type, Event::value).containsExactlyInAnyOrder(
                tuple("Refrigerator/Fridge", "contact", "closed"),
                tuple("Refrigerator/Freezer", "temperature", "5 F"),
                tuple("Washer", "machineState", "stop"));   // the power sensor is not included
        // The first poll records the current state "now", not Home Assistant's old change time.
        assertThat(first).allSatisfy(e -> assertThat(e.time()).isAfterOrEqualTo(before));
        // A change seen by a later poll keeps Home Assistant's change time.
        assertThat(second).extracting(Event::device, Event::value, Event::time)
                .containsExactly(tuple("Refrigerator/Fridge", "open", Instant.parse("2099-01-01T00:00:00Z")));
    }

    // ---------------------------------------------------------------- live updates

    private static String stateChanged(String entityId, String state, String deviceClass, String friendly, String changed) {
        return """
                {"id":1,"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"%1$s",
                 "new_state":{"entity_id":"%1$s","state":"%2$s","last_changed":"%5$s",
                  "attributes":{"device_class":"%3$s","friendly_name":"%4$s"}}}}}
                """.formatted(entityId, state, deviceClass, friendly, changed);
    }

    @Test
    void liveAuthenticatesSubscribesAndSavesEveryChange() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/template"))
                .andRespond(withSuccess("binary_sensor.kitchen_refrigerator_fridge_door|Refrigerator\n", MediaType.TEXT_PLAIN));
        List<Event> saved = new ArrayList<>();
        var connector = new HomeAssistantConnector(builder, PROPS, HomeAssistantConnector.DEFAULT_INCLUDE, saved::add);
        WebSocket ws = mock(WebSocket.class);

        connector.handleMessage(ws, "{\"type\":\"auth_required\",\"ha_version\":\"2026.11.0\"}");
        connector.handleMessage(ws, "{\"type\":\"auth_ok\"}");
        var sent = ArgumentCaptor.forClass(CharSequence.class);
        verify(ws, times(2)).sendText(sent.capture(), eq(true));
        assertThat(sent.getAllValues().get(0).toString()).contains("\"type\":\"auth\"").contains("\"access_token\":\"secret\"");
        assertThat(sent.getAllValues().get(1).toString()).contains("subscribe_events").contains("state_changed");

        String door = "binary_sensor.kitchen_refrigerator_fridge_door";
        connector.handleMessage(ws, stateChanged(door, "on", "door", "Refrigerator Fridge door", "2026-11-05T03:17:39+00:00"));
        connector.handleMessage(ws, stateChanged(door, "off", "door", "Refrigerator Fridge door", "2026-11-05T03:17:42+00:00"));
        connector.handleMessage(ws, stateChanged(door, "off", "door", "Refrigerator Fridge door", "2026-11-05T03:17:50+00:00")); // no change
        connector.handleMessage(ws, stateChanged("sensor.washer_power", "12", "power", "Washer Power", "2026-11-05T03:18:00+00:00")); // not included
        server.verify();

        // A door opened for 3 seconds is caught, with Home Assistant's exact times.
        assertThat(saved).extracting(Event::device, Event::type, Event::value, Event::time).containsExactly(
                tuple("Refrigerator/Fridge", "contact", "open", Instant.parse("2026-11-05T03:17:39Z")),
                tuple("Refrigerator/Fridge", "contact", "closed", Instant.parse("2026-11-05T03:17:42Z")));
    }

    @Test
    void liveIgnoresNonJsonAndUnknownMessages() {
        List<Event> saved = new ArrayList<>();
        var connector = new HomeAssistantConnector(RestClient.builder(), PROPS, HomeAssistantConnector.DEFAULT_INCLUDE, saved::add);
        WebSocket ws = mock(WebSocket.class);

        connector.handleMessage(ws, "not json");
        connector.handleMessage(ws, "{\"type\":\"pong\",\"id\":5}");
        connector.handleMessage(ws, "{\"id\":1,\"type\":\"result\",\"success\":true,\"result\":null}");

        assertThat(saved).isEmpty();
        verifyNoInteractions(ws);
    }

    @Test
    void fallsBackToFriendlyNamesWhenDeviceLookupFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectStates(server, states("off", "run"));
        server.expect(requestTo(URL + "/api/template")).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        List<Event> events = new HomeAssistantConnector(builder, PROPS, "sensor.*_machine_state", null).poll();

        assertThat(events).extracting(Event::device, Event::value).containsExactly(tuple("Washer Machine state", "run"));
    }

    @Test
    void unauthorizedReturnsNothing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/api/states")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(new HomeAssistantConnector(builder, PROPS, HomeAssistantConnector.DEFAULT_INCLUDE, null).poll()).isEmpty();
        server.verify();
    }

    @Test
    void missingSettingsMakeNoRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        var connector = new HomeAssistantConnector(builder, new HomeAssistantProperties(URL, " "), "lock.*", null);
        assertThat(connector.poll()).isEmpty();
        server.verify();
    }
}
