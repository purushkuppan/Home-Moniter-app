package com.homehub.homeassistant;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dashboard names for single Home Assistant entities, bound from {@code homehub.connectors.homeassistant.names}.
 * Normally Kudil names a reading after its Home Assistant device ("Refrigerator/Fridge"), but some devices have names
 * that read badly ("Backdoor openclose", "Water Leak XS Sensor Water leak detected"). Keep the [brackets] so the dot
 * in the entity id survives:
 * <pre>
 * homehub.connectors.homeassistant.names.[binary_sensor.living_room_backdoor_openclose]=Back door
 * </pre>
 * A name may contain one "/" to name a part of a device, like "Refrigerator/Fridge".
 */
@ConfigurationProperties("homehub.connectors.homeassistant")
public record EntityNames(Map<String, String> names) {

    public EntityNames {
        Map<String, String> clean = new LinkedHashMap<>();
        if (names != null) {
            names.forEach((id, name) -> {
                if (id != null && name != null && !id.isBlank() && !name.isBlank()) clean.put(id.trim(), name.trim());
            });
        }
        names = Map.copyOf(clean);
    }

    public static EntityNames none() {
        return new EntityNames(Map.of());
    }

    /** The configured name for this entity, or null. */
    public String nameOf(String entityId) {
        return names.get(entityId);
    }
}
