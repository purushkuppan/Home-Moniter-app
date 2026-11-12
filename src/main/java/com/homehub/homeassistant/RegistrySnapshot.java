package com.homehub.homeassistant;

import java.util.List;
import java.util.Map;

/**
 * Home Assistant's area, device and entity registries plus the current states (for each entity's device class),
 * as read over the WebSocket API (#99): {@code config/area_registry/list}, {@code config/device_registry/list},
 * {@code config/entity_registry/list} and {@code GET /api/states}. Raw JSON objects.
 */
public record RegistrySnapshot(List<Map<String, Object>> areas, List<Map<String, Object>> devices,
                               List<Map<String, Object>> entities, List<Map<String, Object>> states) {
}
