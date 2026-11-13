package com.homehub.zwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ZwaveSessionTests {

    private static final Instant T = Instant.parse("2026-11-13T18:00:00Z");
    static final String VERSION = """
            {"type":"version","driverVersion":"15.3.0","serverVersion":"3.1.0","homeId":3967834353,
             "minSchemaVersion":0,"maxSchemaVersion":44}""";
    static final String NODES = """
            {"type":"result","messageId":"kudil-start-listening","success":true,"result":{"state":{
              "driver":{"logConfig":{}},"controller":{"homeId":3967834353},
              "nodes":[
                {"nodeId":1,"isControllerNode":true,"status":4,"ready":true,"deviceConfig":{"label":"ZST39"}},
                {"nodeId":5,"name":"Back door","location":"Kitchen","status":4,"ready":true,"dsk":"12345-SECRET-DSK"},
                {"nodeId":7,"status":1,"ready":false,"label":"ZSE41"},
                {"nodeId":9,"status":0,"ready":false}
              ]}}}""";

    private final ZwaveSession session = new ZwaveSession();

    private void handshake() {
        assertThat(session.onMessage(VERSION, T)).containsExactly(
                "{\"messageId\":\"kudil-set-api-schema\",\"command\":\"set_api_schema\",\"schemaVersion\":35}");
        assertThat(session.onMessage("{\"type\":\"result\",\"messageId\":\"kudil-set-api-schema\",\"success\":true,\"result\":{}}", T))
                .containsExactly("{\"messageId\":\"kudil-start-listening\",\"command\":\"start_listening\"}");
        assertThat(session.onMessage(NODES, T)).isEmpty();
    }

    @Test
    void versionThenFixedSchemaThenStartListeningGivesTheNodeList() {
        assertThat(session.isReady()).isFalse();
        handshake();

        ZwaveSession.State s = session.state();
        assertThat(s.ready()).isTrue();
        assertThat(s.serverVersion()).isEqualTo("3.1.0");
        assertThat(s.driverVersion()).isEqualTo("15.3.0");
        assertThat(s.since()).isEqualTo(T);
        assertThat(s.problem()).isNull();
        assertThat(s.nodes()).extracting(ZwaveNode::id, ZwaveNode::name, ZwaveNode::location, ZwaveNode::status,
                        ZwaveNode::ready, ZwaveNode::controller)
                .containsExactly(
                        tuple(1, "ZST39", "", "alive", true, true),
                        tuple(5, "Back door", "Kitchen", "alive", true, false),
                        tuple(7, "ZSE41", "", "asleep", false, false),
                        tuple(9, "Node 9", "", "unknown", false, false));
        assertThat(s.toString()).as("no DSK kept").doesNotContain("SECRET");
    }

    @Test
    void eventsKeepTheListCurrent() {
        handshake();
        session.onMessage("{\"type\":\"event\",\"event\":{\"source\":\"node\",\"event\":\"dead\",\"nodeId\":5}}", T);
        session.onMessage("{\"type\":\"event\",\"event\":{\"source\":\"node\",\"event\":\"wake up\",\"nodeId\":7}}", T);
        session.onMessage("""
                {"type":"event","event":{"source":"node","event":"ready","nodeId":9,
                 "nodeState":{"nodeId":9,"name":"Garage door","status":4,"ready":true}}}""", T);
        session.onMessage("""
                {"type":"event","event":{"source":"controller","event":"node added",
                 "node":{"nodeId":12,"status":0,"ready":false},"result":{}}}""", T);
        session.onMessage("""
                {"type":"event","event":{"source":"controller","event":"node removed","node":{"nodeId":1},"reason":0}}""", T);
        session.onMessage("{\"type\":\"event\",\"event\":{\"source\":\"node\",\"event\":\"value updated\",\"nodeId\":5,\"args\":{}}}", T);

        assertThat(session.state().nodes()).extracting(ZwaveNode::id, ZwaveNode::name, ZwaveNode::status, ZwaveNode::ready)
                .containsExactly(
                        tuple(5, "Back door", "dead", true),
                        tuple(7, "ZSE41", "awake", false),
                        tuple(9, "Garage door", "alive", true),
                        tuple(12, "Node 12", "unknown", false));
    }

    @Test
    void aServerWithoutKudilsSchemaIsReportedNotGuessedAround() {
        assertThat(session.onMessage("""
                {"type":"version","serverVersion":"1.10.0","driverVersion":"9.0.0","minSchemaVersion":0,"maxSchemaVersion":14}""", T))
                .isEmpty();
        assertThat(session.state().problem()).isEqualTo("The Z-Wave JS server 1.10.0 speaks API schema 0–14; Kudil needs 35.");
        assertThat(session.isReady()).isFalse();
    }

    @Test
    void aRefusalIsReported() {
        session.onMessage(VERSION, T);
        assertThat(session.onMessage("""
                {"type":"result","messageId":"kudil-set-api-schema","success":false,"errorCode":"schema_incompatible"}""", T))
                .isEmpty();
        assertThat(session.state().problem()).isEqualTo("The Z-Wave JS server refused API schema 35 (schema_incompatible).");
    }

    @Test
    void oddMessagesAreIgnoredAndResetStartsOver() {
        assertThat(session.onMessage("not json", T)).isEmpty();
        assertThat(session.onMessage("{\"type\":\"something new\"}", T)).isEmpty();
        handshake();
        session.reset();
        assertThat(session.state().nodes()).isEmpty();
        assertThat(session.isReady()).isFalse();
    }
}
