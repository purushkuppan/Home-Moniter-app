package com.homehub.homeassistant;

import java.time.Instant;

/**
 * Published (as a Spring application event) when Kudil gains or loses its connection to Home Assistant.
 * Reachable means the last poll worked or the live WebSocket is connected; only changes are published.
 *
 * @param reachable whether Home Assistant can be reached now
 * @param time      when that changed
 */
public record HomeAssistantStatus(boolean reachable, Instant time) {
}
