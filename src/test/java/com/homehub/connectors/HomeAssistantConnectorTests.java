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
import com.homehub.homeassistant.EntityNames;
import com.homehub.homeassistant.HomeAssistantProperties;
import com.homehub.homeassistant.HomeAssistantStatus;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void batteryLevelsAreReadByDefault() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectStates(server, """
                [{"entity_id":"sensor.laundry_leak_sensor_battery","state":"15",
                  "attributes":{"device_class":"battery","unit_of_measurement":"%","friendly_name":"Leak sensor Battery"}},
                 {"entity_id":"sensor.oneplus_13_battery_level","state":"80",
                  "attributes":{"device_class":"battery","unit_of_measurement":"%","friendly_name":"OnePlus 13 Battery level"}}]
                """);
        server.expect(requestTo(URL + "/api/template"))
                .andRespond(withSuccess("sensor.laundry_leak_sensor_battery|Leak sensor\n", MediaType.TEXT_PLAIN));

        List<Event> events = new HomeAssistantConnector(builder, PROPS, HomeAssistantConnector.DEFAULT_INCLUDE, null).poll();

        // The phone's own battery level (Companion app) is not a home device: not included.
        assertThat(events).extracting(Event::device, Event::type, Event::value)
                .containsExactly(tuple("Leak sensor", "battery", "15 %"));
    }

    // ---------------------------------------------------------------- Z-Wave devices (#36)

    private static final String Z_WAVE = """
            binary_sensor.living_room_backdoor_openclose,binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected,\
            switch.indoor_smart_plug,sensor.indoor_smart_plug_electric_consumption_w,\
            sensor.kitchen_water_leak_xs_sensor_battery_level""";

    private static final EntityNames Z_WAVE_NAMES = new EntityNames(Map.of(
            "binary_sensor.living_room_backdoor_openclose", "Back door",
            "binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected", "Kitchen leak sensor",
            "sensor.kitchen_water_leak_xs_sensor_battery_level", "Kitchen leak sensor",
            "sensor.indoor_smart_plug_electric_consumption_w", "Indoor Smart Plug"));

    /** The Z-Wave entities as Home Assistant reports them (real ids, device classes and names from the hub). */
    private static String zWaveStates(String door, String leak, String plug, String watts) {
        return """
                [{"entity_id":"binary_sensor.living_room_backdoor_openclose","state":"%s",
                  "attributes":{"device_class":"door","friendly_name":"Backdoor openclose"}},
                 {"entity_id":"binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected","state":"%s",
                  "attributes":{"device_class":"moisture","friendly_name":"Water Leak XS Sensor Water leak detected"}},
                 {"entity_id":"sensor.kitchen_water_leak_xs_sensor_battery_level","state":"90",
                  "attributes":{"device_class":"battery","unit_of_measurement":"%%","friendly_name":"Water Leak XS Sensor Battery level"}},
                 {"entity_id":"switch.indoor_smart_plug","state":"%s",
                  "attributes":{"friendly_name":"Indoor Smart Plug"}},
                 {"entity_id":"sensor.indoor_smart_plug_electric_consumption_w","state":"%s",
                  "attributes":{"device_class":"power","unit_of_measurement":"W","friendly_name":"Indoor Smart Plug Electric Consumption [W]"}},
                 {"entity_id":"sensor.indoor_smart_plug_voltage","state":"121",
                  "attributes":{"device_class":"voltage","unit_of_measurement":"V","friendly_name":"Indoor Smart Plug Electric Consumption [V]"}}]
                """.formatted(door, leak, plug, watts);
    }

    private static final String Z_WAVE_DEVICES = """
            binary_sensor.living_room_backdoor_openclose|Backdoor openclose
            binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected|Water Leak XS Sensor
            sensor.kitchen_water_leak_xs_sensor_battery_level|Water Leak XS Sensor
            switch.indoor_smart_plug|Indoor Smart Plug
            sensor.indoor_smart_plug_electric_consumption_w|Indoor Smart Plug
            """;

    private static HomeAssistantConnector zWave(MockRestServiceServer server, RestClient.Builder builder, String... polls) {
        expectStates(server, polls[0]);
        server.expect(requestTo(URL + "/api/template")).andRespond(withSuccess(Z_WAVE_DEVICES, MediaType.TEXT_PLAIN));
        for (int i = 1; i < polls.length; i++) expectStates(server, polls[i]);
        return new HomeAssistantConnector(builder, PROPS, Z_WAVE, null, status -> { }, Duration.ZERO, Clock.systemUTC(),
                Duration.ZERO, Z_WAVE_NAMES);
    }

    @Test
    void zWaveDevicesGetTheirDashboardNamesAndReadableValues() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var connector = zWave(server, builder, zWaveStates("off", "off", "on", "12.5"),
                zWaveStates("on", "on", "off", "0.0"));

        assertThat(connector.poll()).extracting(Event::device, Event::type, Event::value).containsExactlyInAnyOrder(
                tuple("Back door", "contact", "closed"),
                tuple("Kitchen leak sensor", "leak", "dry"),
                tuple("Kitchen leak sensor", "battery", "90 %"),
                tuple("Indoor Smart Plug", "switch", "on"),
                tuple("Indoor Smart Plug", "power", "12.5 W"));   // the voltage sensor is not included
        assertThat(connector.poll()).extracting(Event::device, Event::type, Event::value).containsExactlyInAnyOrder(
                tuple("Back door", "contact", "open"),
                tuple("Kitchen leak sensor", "leak", "wet"),
                tuple("Indoor Smart Plug", "switch", "off"),
                tuple("Indoor Smart Plug", "power", "0.0 W"));
        server.verify();
    }

    @Test
    void smallPowerChangesAreNotStored() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String[] watts = {"60.0", "62.1", "57.9", "64.9", "66.0", "0.0", "0.4", "3.0", "unavailable", "3.0"};
        String[] polls = new String[watts.length];
        for (int i = 0; i < watts.length; i++) polls[i] = zWaveStates("off", "off", "on", watts[i]);
        var connector = zWave(server, builder, polls);

        List<String> stored = new ArrayList<>();
        for (int i = 0; i < watts.length; i++) {
            connector.poll().stream().filter(e -> e.type().equals("power")).forEach(e -> stored.add(e.value()));
        }
        server.verify();

        // Within 5 W of the last stored value: skipped (62.1, 57.9, 64.9 against 60). 66 is 6 W more. Off (0) and back on
        // (0.4, however small) always count; 3.0 is within 5 W of 0.4; after "unavailable" any value counts again.
        assertThat(stored).containsExactly("60.0 W", "66.0 W", "0.0 W", "0.4 W", "unavailable", "3.0 W");
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
    void reportsOnlyChangesInReachability() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/api/states")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(URL + "/api/states")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        expectStates(server, "[]");
        expectStates(server, "[]");
        List<HomeAssistantStatus> statuses = new ArrayList<>();
        var connector = new HomeAssistantConnector(builder, PROPS, "lock.*", null, statuses::add);

        connector.poll();   // fails: unreachable
        connector.poll();   // still failing: no new report
        connector.poll();   // works again
        connector.poll();

        server.verify();
        assertThat(statuses).extracting(HomeAssistantStatus::reachable).containsExactly(false, true);
    }

    @Test
    void aWorkingLiveConnectionMeansReachableEvenIfAPollFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/api/states")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        List<HomeAssistantStatus> statuses = new ArrayList<>();
        var connector = new HomeAssistantConnector(builder, PROPS, "lock.*", event -> { }, statuses::add);

        connector.handleMessage(mock(WebSocket.class), "{\"type\":\"auth_ok\"}");
        connector.poll();

        assertThat(statuses).extracting(HomeAssistantStatus::reachable).containsExactly(true);
    }

    /** A clock the test moves forward by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-08T18:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void whileLiveUpdatesWorkPollingIsOnlyASafetyNet() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectStates(server, "[]");   // at startup
        expectStates(server, "[]");   // 5 minutes later
        expectStates(server, "[]");   // live updates dropped: back to every run
        expectStates(server, "[]");
        TestClock clock = new TestClock();
        var connector = new HomeAssistantConnector(builder, PROPS, "lock.*", event -> { }, status -> { },
                Duration.ofMinutes(5), clock);
        WebSocket ws = mock(WebSocket.class);

        connector.handleMessage(ws, "{\"type\":\"auth_ok\"}");
        connector.poll();
        clock.now = clock.now.plusSeconds(30);
        connector.poll();                          // skipped
        clock.now = clock.now.plusSeconds(240);
        connector.poll();                          // skipped: 4.5 minutes after the last poll
        clock.now = clock.now.plusSeconds(30);
        connector.poll();                          // 5 minutes: polls

        connector.handleMessage(ws, "{\"type\":\"auth_invalid\"}");
        clock.now = clock.now.plusSeconds(30);
        connector.poll();
        clock.now = clock.now.plusSeconds(30);
        connector.poll();

        server.verify();   // exactly four requests
    }

    // ---------------------------------------------------------------- "unavailable" grace period (#92)

    private static final String POWER = "sensor.kitchen_refrigerator_power";

    private static String power(String state, String changed) {
        return """
                {"id":1,"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"%1$s",
                 "new_state":{"entity_id":"%1$s","state":"%2$s","last_changed":"%3$s",
                  "attributes":{"device_class":"power","unit_of_measurement":"W","friendly_name":"Refrigerator Power"}}}}}
                """.formatted(POWER, state, changed);
    }

    /** A live connector with a 2-minute grace period whose polls skip Home Assistant (live updates are working). */
    private static HomeAssistantConnector liveWithGrace(List<Event> saved, TestClock clock, WebSocket ws) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectStates(server, "[]");
        server.expect(ExpectedCount.manyTimes(), requestTo(URL + "/api/template"))
                .andRespond(withSuccess(POWER + "|Refrigerator\n", MediaType.TEXT_PLAIN));
        var connector = new HomeAssistantConnector(builder, PROPS, "sensor.*_power", saved::add, status -> { },
                Duration.ofHours(1), clock, Duration.ofMinutes(2));
        connector.handleMessage(ws, "{\"type\":\"auth_ok\"}");
        assertThat(connector.poll()).isEmpty();   // the startup poll
        return connector;
    }

    @Test
    void aBriefUnavailableLikeASmartThingsReloadLeavesNoTrace() {
        List<Event> saved = new ArrayList<>();
        TestClock clock = new TestClock();
        WebSocket ws = mock(WebSocket.class);
        var connector = liveWithGrace(saved, clock, ws);

        connector.handleMessage(ws, power("120", "2026-11-08T18:00:00+00:00"));
        connector.handleMessage(ws, power("unavailable", "2026-11-08T18:00:10+00:00"));   // the reload
        clock.now = clock.now.plusSeconds(15);
        connector.handleMessage(ws, power("120", "2026-11-08T18:00:25+00:00"));           // back, same value
        clock.now = clock.now.plusSeconds(300);

        assertThat(connector.poll()).isEmpty();
        assertThat(saved).extracting(Event::value).containsExactly("120 W");
    }

    @Test
    void comingBackWithANewValueIsStoredButTheGapIsNot() {
        List<Event> saved = new ArrayList<>();
        TestClock clock = new TestClock();
        WebSocket ws = mock(WebSocket.class);
        var connector = liveWithGrace(saved, clock, ws);

        connector.handleMessage(ws, power("120", "2026-11-08T18:00:00+00:00"));
        connector.handleMessage(ws, power("unknown", "2026-11-08T18:00:10+00:00"));
        clock.now = clock.now.plusSeconds(20);
        connector.handleMessage(ws, power("3", "2026-11-08T18:00:30+00:00"));

        assertThat(saved).extracting(Event::value).containsExactly("120 W", "3 W");
    }

    @Test
    void aDeviceThatStaysUnavailableIsStoredAfterTheGracePeriodWithItsOriginalTime() {
        List<Event> saved = new ArrayList<>();
        TestClock clock = new TestClock();
        WebSocket ws = mock(WebSocket.class);
        var connector = liveWithGrace(saved, clock, ws);

        connector.handleMessage(ws, power("120", "2026-11-08T18:00:00+00:00"));
        connector.handleMessage(ws, power("unavailable", "2026-11-08T18:00:10+00:00"));
        clock.now = clock.now.plusSeconds(90);
        assertThat(connector.poll()).as("still within 2 minutes").isEmpty();
        clock.now = clock.now.plusSeconds(30);
        assertThat(connector.poll()).extracting(Event::device, Event::value, Event::time)
                .containsExactly(tuple("Refrigerator", "unavailable", Instant.parse("2026-11-08T18:00:10Z")));
        clock.now = clock.now.plusSeconds(30);
        assertThat(connector.poll()).as("stored once").isEmpty();

        // Coming back later is a change again.
        connector.handleMessage(ws, power("120", "2026-11-08T19:00:00+00:00"));
        assertThat(saved).extracting(Event::value).containsExactly("120 W", "120 W");
    }

    @Test
    void pollingAlsoHoldsUnavailableBack() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectStates(server, states("off", "run"));
        server.expect(requestTo(URL + "/api/template"))
                .andRespond(withSuccess("sensor.laundry_room_washer_machine_state|Washer\n", MediaType.TEXT_PLAIN));
        expectStates(server, states("off", "unavailable"));
        expectStates(server, states("off", "run"));
        TestClock clock = new TestClock();
        var connector = new HomeAssistantConnector(builder, PROPS, "sensor.*_machine_state", null, status -> { },
                Duration.ZERO, clock, Duration.ofMinutes(2));

        assertThat(connector.poll()).extracting(Event::value).containsExactly("run");
        clock.now = clock.now.plusSeconds(30);
        assertThat(connector.poll()).as("unavailable is held").isEmpty();
        clock.now = clock.now.plusSeconds(30);
        assertThat(connector.poll()).as("back to the same value: nothing changed").isEmpty();
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
