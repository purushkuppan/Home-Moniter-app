package com.homehub.zwave;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Z-Wave JS server's protocol, without the network (#134), so it is easy to test: give it each message the server
 * sends and it says what to send back.
 * <ol>
 *   <li>The server greets with {@code version} (its version and the API schemas it supports).</li>
 *   <li>Kudil asks for schema {@value #SCHEMA_VERSION} ({@code set_api_schema}): a fixed one, so a server update can't
 *       change the messages under us. A server that doesn't support it is reported, not guessed around.</li>
 *   <li>Kudil sends {@code start_listening}; the answer holds the whole state, from which only the node list is
 *       kept.</li>
 *   <li>Afterwards, events keep the list current: nodes added or removed, alive, dead, asleep, awake, ready.</li>
 * </ol>
 * Read-only: no command here changes anything on the Z-Wave network. Thread-safe.
 */
public final class ZwaveSession {

    /** The API schema Kudil speaks. Raised on purpose, after reading the server's changelog. */
    public static final int SCHEMA_VERSION = 35;
    static final String SET_SCHEMA_ID = "kudil-set-api-schema";
    static final String START_LISTENING_ID = "kudil-start-listening";

    private static final JsonMapper JSON = JsonMapper.shared();

    /** What the status page shows. */
    public record State(boolean ready, String serverVersion, String driverVersion, String problem, Instant since,
                        List<ZwaveNode> nodes) {
    }

    private final Map<Integer, ZwaveNode> nodes = new TreeMap<>();
    private String serverVersion = "";
    private String driverVersion = "";
    private boolean ready;
    private String problem;
    private Instant since;

    /** A new connection: forget the old one's state. */
    public synchronized void reset() {
        nodes.clear();
        serverVersion = "";
        driverVersion = "";
        ready = false;
        problem = null;
        since = null;
    }

    /**
     * One complete message from the server. Returns the messages to send back, in order (none for most messages).
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> onMessage(String text, Instant now) {
        Map<String, Object> msg;
        try {
            msg = JSON.readValue(text, Map.class);
        } catch (RuntimeException ex) {
            return List.of();   // not JSON: ignored
        }
        switch (text(msg.get("type"))) {
            case "version" -> {
                serverVersion = text(msg.get("serverVersion"));
                driverVersion = text(msg.get("driverVersion"));
                int min = number(msg.get("minSchemaVersion"), 0);
                int max = number(msg.get("maxSchemaVersion"), 0);
                if (SCHEMA_VERSION < min || SCHEMA_VERSION > max) {
                    problem = "The Z-Wave JS server " + serverVersion + " speaks API schema " + min + "–" + max
                            + "; Kudil needs " + SCHEMA_VERSION + ".";
                    return List.of();
                }
                return List.of(command(SET_SCHEMA_ID, "set_api_schema", Map.of("schemaVersion", SCHEMA_VERSION)));
            }
            case "result" -> {
                String id = text(msg.get("messageId"));
                if (!Boolean.TRUE.equals(msg.get("success"))) {
                    problem = "The Z-Wave JS server refused " + (SET_SCHEMA_ID.equals(id) ? "API schema " + SCHEMA_VERSION
                            : "to start listening") + " (" + text(msg.get("errorCode")) + ").";
                    return List.of();
                }
                if (SET_SCHEMA_ID.equals(id)) return List.of(command(START_LISTENING_ID, "start_listening", Map.of()));
                if (START_LISTENING_ID.equals(id)) {
                    Map<String, Object> state = map(map(msg.get("result")).get("state"));
                    nodes.clear();
                    for (Object n : list(state.get("nodes"))) put(map(n));
                    ready = true;
                    problem = null;
                    since = now;
                }
            }
            case "event" -> event(map(msg.get("event")));
            default -> { }
        }
        return List.of();
    }

    public synchronized State state() {
        return new State(ready, serverVersion, driverVersion, problem, since, List.copyOf(nodes.values()));
    }

    /** Whether the handshake finished and the node list is current. */
    public synchronized boolean isReady() {
        return ready;
    }

    private void event(Map<String, Object> event) {
        String source = text(event.get("source"));
        String name = text(event.get("event"));
        if ("controller".equals(source)) {
            Map<String, Object> node = map(event.get("node"));
            switch (name) {
                case "node added" -> put(node);
                case "node removed" -> nodes.remove(number(node.get("nodeId"), -1));
                default -> { }
            }
            return;
        }
        if (!"node".equals(source)) return;
        int id = number(event.get("nodeId"), -1);
        ZwaveNode known = nodes.get(id);
        switch (name) {
            case "ready" -> {   // carries the node's full state, e.g. a name it learned in its interview
                Map<String, Object> state = map(event.get("nodeState"));
                if (!state.isEmpty()) put(state);
                else if (known != null) nodes.put(id, with(known, known.status(), true));
            }
            case "alive", "dead", "sleep", "wake up" -> {
                if (known != null) nodes.put(id, with(known, switch (name) {
                    case "sleep" -> "asleep";
                    case "wake up" -> "awake";
                    default -> name;
                }, known.ready()));
            }
            default -> { }
        }
    }

    private void put(Map<String, Object> n) {
        int id = number(n.get("nodeId"), -1);
        if (id < 0) return;
        String name = text(n.get("name"));
        if (name.isEmpty()) name = text(map(n.get("deviceConfig")).get("label"));
        if (name.isEmpty()) name = text(n.get("label"));
        if (name.isEmpty()) name = "Node " + id;
        nodes.put(id, new ZwaveNode(id, name, text(n.get("location")), status(n.get("status")),
                Boolean.TRUE.equals(n.get("ready")), Boolean.TRUE.equals(n.get("isControllerNode"))));
    }

    private static ZwaveNode with(ZwaveNode n, String status, boolean ready) {
        return new ZwaveNode(n.id(), n.name(), n.location(), status, ready, n.controller());
    }

    /** Z-Wave JS's NodeStatus: 0 unknown, 1 asleep, 2 awake, 3 dead, 4 alive. */
    private static String status(Object value) {
        return switch (number(value, 0)) {
            case 1 -> "asleep";
            case 2 -> "awake";
            case 3 -> "dead";
            case 4 -> "alive";
            default -> "unknown";
        };
    }

    private static String command(String id, String command, Map<String, Object> args) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("messageId", id);
        m.put("command", command);
        m.putAll(args);
        return JSON.writeValueAsString(m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Collection<?> list(Object o) {
        return o instanceof Collection<?> c ? c : new ArrayList<>();
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int number(Object o, int otherwise) {
        return o instanceof Number n ? n.intValue() : otherwise;
    }
}
