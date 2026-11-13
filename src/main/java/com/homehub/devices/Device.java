package com.homehub.devices;

import java.time.Instant;
import java.util.List;

/**
 * A device Kudil knows from Home Assistant (#99).
 *
 * @param id              Kudil's id (used by the dashboard)
 * @param haDeviceId      Home Assistant's device id
 * @param name            Kudil's name, e.g. "Kitchen leak sensor"; readings are recorded under it
 * @param haName          Home Assistant's name, e.g. "Water Leak XS Sensor"
 * @param room            Home Assistant area, or null
 * @param status          new (to review), confirmed (recorded, alerts) or hidden (ignored)
 * @param entities        all its entity ids; internal, never shown
 * @param readings        what is recorded once confirmed; internal, never shown
 * @param inHomeAssistant false once it was removed from Home Assistant
 * @param openForMinutes  doors and windows: alert when open longer than this; 0 = never; null = the default (#100)
 * @param alertChoices    appliances: the household's alert choices by key ("open-for:Fridge" -> "2"); missing = default
 */
public record Device(long id, String haDeviceId, String name, String haName, String room, String manufacturer,
                     String model, DeviceKind kind, Status status, List<String> entities, List<Reading> readings,
                     boolean inHomeAssistant, Instant firstSeen, Instant confirmedAt, Integer openForMinutes,
                     java.util.Map<String, String> alertChoices) {

    public enum Status { NEW, CONFIRMED, HIDDEN }

    /**
     * One entity Kudil records, with what it measures: domain and device class, e.g. {@code binary_sensor/moisture}.
     * Two readings with the same {@code kindKey} (a fridge's two doors) are told apart by their part names.
     */
    public record Reading(String entityId, String deviceClass) {

        public String kindKey() {
            int dot = entityId.indexOf('.');
            return (dot < 0 ? "" : entityId.substring(0, dot)) + "/" + (deviceClass == null ? "" : deviceClass);
        }

        /** Stored as "entity_id/device_class". */
        String stored() {
            return entityId + "/" + (deviceClass == null ? "" : deviceClass);
        }

        static Reading parse(String stored) {
            int slash = stored.indexOf('/');
            return slash < 0 ? new Reading(stored, "") : new Reading(stored.substring(0, slash), stored.substring(slash + 1));
        }
    }
}
