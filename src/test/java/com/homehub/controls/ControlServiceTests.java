package com.homehub.controls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.homehub.events.Event;
import com.homehub.homeassistant.HomeAssistantProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ControlServiceTests {

    private static final String URL = "http://ha.test:8123";
    private static final String ALLOWED =
            "number.kitchen_refrigerator_*,switch.kitchen_refrigerator_*,button.kitchen_refrigerator_*,select.laundry_room_*";

    private static final String FRIDGE_TEMP = """
            {"entity_id":"number.kitchen_refrigerator_fridge_temperature","state":"44",
             "attributes":{"min":34,"max":44,"step":1,"unit_of_measurement":"°F","friendly_name":"Refrigerator Fridge temperature"}}""";
    private static final String POWER_COOL = """
            {"entity_id":"switch.kitchen_refrigerator_power_cool","state":"off",
             "attributes":{"friendly_name":"Refrigerator Power cool"}}""";
    private static final String WASHER = """
            {"entity_id":"select.laundry_room_washer","state":"stop",
             "attributes":{"options":["stop","run","pause"],"friendly_name":"Washer"}}""";
    private static final String SPIN = """
            {"entity_id":"select.laundry_room_washer_spin_level","state":"high",
             "attributes":{"options":["rinse_hold","no_spin","low","medium","high"],"friendly_name":"Washer Spin level"}}""";
    private static final String FILTER = """
            {"entity_id":"button.kitchen_refrigerator_reset_water_filter","state":"unknown",
             "attributes":{"friendly_name":"Refrigerator Reset water filter"}}""";
    private static final String OTHER_SWITCH = """
            {"entity_id":"switch.garage_door_opener","state":"off","attributes":{"friendly_name":"Garage"}}""";
    private static final String REMOTE_OFF = """
            {"entity_id":"binary_sensor.laundry_room_washer_remote_control","state":"off","attributes":{}}""";
    private static final String DEVICE_NAMES = """
            number.kitchen_refrigerator_fridge_temperature|Refrigerator
            switch.kitchen_refrigerator_power_cool|Refrigerator
            select.laundry_room_washer|Washer
            select.laundry_room_washer_spin_level|Washer
            button.kitchen_refrigerator_reset_water_filter|Refrigerator
            """;

    private MockRestServiceServer server;
    private ControlService service;
    private final List<Event> audit = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ControlService(builder, new HomeAssistantProperties(URL, "secret"), ALLOWED, audit::add);
    }

    private void expectState(String json) {
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(URL + "/api/states/")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void expectDeviceNames() {
        server.expect(ExpectedCount.once(), requestTo(URL + "/api/template"))
                .andRespond(withSuccess(DEVICE_NAMES, MediaType.TEXT_PLAIN));
    }

    @Test
    void listsOnlyAllowlistedControlsWithTheirLimits() {
        server.expect(requestTo(URL + "/api/states")).andRespond(withSuccess(
                "[" + String.join(",", FRIDGE_TEMP, POWER_COOL, WASHER, SPIN, FILTER, OTHER_SWITCH, REMOTE_OFF) + "]",
                MediaType.APPLICATION_JSON));
        expectDeviceNames();

        List<Control> controls = service.list();
        server.verify();

        assertThat(controls).extracting(Control::device, Control::label, Control::kind, Control::value).containsExactly(
                tuple("Refrigerator", "Fridge temperature", "number", "44"),
                tuple("Refrigerator", "Power cool", "switch", "off"),
                tuple("Refrigerator", "Reset water filter", "button", "unknown"),
                tuple("Washer", "Cycle", "cycle", "stop"),
                tuple("Washer", "Spin level", "select", "high"));   // the garage switch is not allowlisted
        Control fridge = controls.get(0);
        assertThat(fridge.min()).isEqualTo(34.0);
        assertThat(fridge.max()).isEqualTo(44.0);
        assertThat(fridge.unit()).isEqualTo("°F");
        assertThat(controls.get(3).note()).contains("Remote Control");
    }

    @Test
    void setsANumberWithinItsRangeAndRecordsTheCommand() {
        expectState(FRIDGE_TEMP);
        expectDeviceNames();
        server.expect(requestTo(URL + "/api/services/number/set_value"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"entity_id\":\"number.kitchen_refrigerator_fridge_temperature\",\"value\":38}"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String message = service.apply("number.kitchen_refrigerator_fridge_temperature", "38");
        server.verify();

        assertThat(message).isEqualTo("Fridge temperature set to 38 °F");
        assertThat(audit).extracting(Event::device, Event::type, Event::value)
                .containsExactly(tuple("Refrigerator", "command", "Fridge temperature set to 38 °F"));
    }

    @Test
    void refusesValuesOutsideTheRangeOrStepWithoutCallingTheDevice() {
        List<String> badValues = List.of("30", "45", "38.5", "warm", "");
        expectState(FRIDGE_TEMP);
        expectDeviceNames();   // looked up once, then remembered
        for (int i = 1; i < badValues.size(); i++) expectState(FRIDGE_TEMP);
        for (String bad : badValues) {
            assertThatThrownBy(() -> service.apply("number.kitchen_refrigerator_fridge_temperature", bad))
                    .isInstanceOfSatisfying(ControlException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        server.verify();   // only state lookups happened, never a service call
        assertThat(audit).isEmpty();
    }

    @Test
    void refusesEntitiesThatAreNotAllowlistedWithoutAnyRequest() {
        for (String id : List.of("switch.garage_door_opener", "lock.front_door", "number.kitchen_refrigerator_x/../y", "")) {
            assertThatThrownBy(() -> service.apply(id, "on"))
                    .isInstanceOfSatisfying(ControlException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        server.verify();
    }

    @Test
    void switchesSelectsCyclesAndButtonsCallTheRightService() {
        expectState(POWER_COOL);
        expectDeviceNames();
        server.expect(requestTo(URL + "/api/services/switch/turn_on"))
                .andExpect(content().json("{\"entity_id\":\"switch.kitchen_refrigerator_power_cool\"}"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        expectState(WASHER);
        server.expect(requestTo(URL + "/api/services/select/select_option"))
                .andExpect(content().json("{\"entity_id\":\"select.laundry_room_washer\",\"option\":\"pause\"}"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        expectState(FILTER);
        server.expect(requestTo(URL + "/api/services/button/press"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(service.apply("switch.kitchen_refrigerator_power_cool", "on")).isEqualTo("Power cool turned on");
        assertThat(service.apply("select.laundry_room_washer", "pause")).isEqualTo("Cycle set to pause");
        assertThat(service.apply("button.kitchen_refrigerator_reset_water_filter", null)).isEqualTo("Reset water filter pressed");
        server.verify();
        assertThat(audit).extracting(Event::device).containsExactly("Refrigerator", "Washer", "Refrigerator");
    }

    @Test
    void theSmartPlugIsOnTheAllowlistAndTurnsItsPowerOnAndOff() throws Exception {
        var properties = new java.util.Properties();
        try (var in = new org.springframework.core.io.ClassPathResource("application.properties").getInputStream()) {
            properties.load(in);
        }
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer plugServer = MockRestServiceServer.bindTo(builder).build();
        var plug = new ControlService(builder, new HomeAssistantProperties(URL, "secret"),
                properties.getProperty("homehub.controls.entities"), audit::add);
        assertThat(plug.isAllowed("switch.indoor_smart_plug")).isTrue();
        assertThat(plug.isAllowed("switch.indoor_smart_plug_2")).as("only that plug").isFalse();

        String state = """
                {"entity_id":"switch.indoor_smart_plug","state":"off","attributes":{"friendly_name":"Indoor Smart Plug"}}""";
        for (String value : List.of("on", "off")) {
            plugServer.expect(ExpectedCount.once(), requestTo(URL + "/api/states/switch.indoor_smart_plug"))
                    .andRespond(withSuccess(state, MediaType.APPLICATION_JSON));
            if (value.equals("on")) {   // the device name is looked up once
                plugServer.expect(ExpectedCount.once(), requestTo(URL + "/api/template"))
                        .andRespond(withSuccess("switch.indoor_smart_plug|Indoor Smart Plug\n", MediaType.TEXT_PLAIN));
            }
            plugServer.expect(ExpectedCount.once(), requestTo(URL + "/api/services/switch/turn_" + value))
                    .andExpect(content().json("{\"entity_id\":\"switch.indoor_smart_plug\"}"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        assertThat(plug.apply("switch.indoor_smart_plug", "on")).isEqualTo("Power turned on");
        assertThat(plug.apply("switch.indoor_smart_plug", "off")).isEqualTo("Power turned off");
        plugServer.verify();
        // Logged as commands of the plug, next to its on/off and watts readings.
        assertThat(audit).extracting(Event::device, Event::type, Event::value).containsExactly(
                tuple("Indoor Smart Plug", "command", "Power turned on"),
                tuple("Indoor Smart Plug", "command", "Power turned off"));
    }

    @Test
    void refusesUnknownOptions() {
        expectState(WASHER);
        expectDeviceNames();
        assertThatThrownBy(() -> service.apply("select.laundry_room_washer", "explode"))
                .isInstanceOfSatisfying(ControlException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(audit).isEmpty();
    }

    @Test
    void reportsWhenTheDeviceRefusesTheCommand() {
        expectState(WASHER);
        expectDeviceNames();
        server.expect(requestTo(URL + "/api/services/select/select_option"))
                .andRespond(withServerError().body("{\"message\":\"Remote control is not enabled\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.apply("select.laundry_room_washer", "run"))
                .isInstanceOfSatisfying(ControlException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).contains("Remote control is not enabled");
                });
        assertThat(audit).isEmpty();   // nothing recorded when it didn't happen
    }
}
