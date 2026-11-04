package com.homehub.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.homehub.events.Event;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SmartThingsConnectorTests {

    private static final String BASE = "https://st.test/v1";
    private static final String DEVICES = """
            {"items":[
              {"deviceId":"f1","label":"Kitchen Fridge"},
              {"deviceId":"w1","label":"Washer"},
              {"deviceId":"x1","label":"Hallway Light"}
            ]}""";

    private static String fridge(String door, int freezerTemp) {
        return """
                {"components":{
                  "main":{"contactSensor":{"contact":{"value":"%s"}}},
                  "freezer":{"temperatureMeasurement":{"temperature":{"value":%d,"unit":"C"}}}
                }}""".formatted(door, freezerTemp);
    }

    private static String washer(String state) {
        return """
                {"components":{"main":{"washerOperatingState":{"machineState":{"value":"%s"}}}}}""".formatted(state);
    }

    private static void expect(MockRestServiceServer server, String path, String json) {
        server.expect(ExpectedCount.once(), requestTo(BASE + path))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void returnsOnlyChangedValuesAndSkipsOtherDevices() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SmartThingsConnector connector = new SmartThingsConnector(builder, "secret", BASE);

        expect(server, "/devices", DEVICES);
        expect(server, "/devices/f1/status", fridge("closed", -18));
        expect(server, "/devices/w1/status", washer("stop"));
        expect(server, "/devices", DEVICES);
        expect(server, "/devices/f1/status", fridge("open", -18));
        expect(server, "/devices/w1/status", washer("stop"));

        List<Event> first = connector.poll();
        List<Event> second = connector.poll();
        server.verify();

        assertThat(first).extracting(Event::device, Event::type, Event::value).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("Kitchen Fridge", "contact", "closed"),
                org.assertj.core.groups.Tuple.tuple("Kitchen Fridge/freezer", "temperature", "-18 C"),
                org.assertj.core.groups.Tuple.tuple("Washer", "machineState", "stop"));
        assertThat(second).extracting(Event::device, Event::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Kitchen Fridge", "open"));
    }

    @Test
    void unauthorizedReturnsNothing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/devices")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(new SmartThingsConnector(builder, "secret", BASE).poll()).isEmpty();
        server.verify();
    }

    @Test
    void missingTokenMakesNoRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThat(new SmartThingsConnector(builder, "  ", BASE).poll()).isEmpty();
        server.verify();
    }
}
