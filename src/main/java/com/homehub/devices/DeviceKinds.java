package com.homehub.devices;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tells what kind of device a Home Assistant device is, and which of its entities are readings worth recording
 * (#99). Pure logic: from each entity's domain, device class, entity category and platform.
 */
final class DeviceKinds {

    private DeviceKinds() {
    }

    /** One entity of a device, as far as classifying it goes. */
    record EntityInfo(String entityId, String deviceClass, String category, String platform) {

        String domain() {
            int dot = entityId.indexOf('.');
            return dot < 0 ? "" : entityId.substring(0, dot);
        }

        boolean is(String domain, String... classes) {
            return domain().equals(domain) && (classes.length == 0 || Set.of(classes).contains(deviceClass));
        }

        /** A setting (entity category "config"), not something to watch. */
        boolean isSetting() {
            return "config".equals(category);
        }

        /** Diagnostic entities (signal, firmware) are left out; battery levels are diagnostic on Z-Wave but wanted. */
        boolean isDiagnostic() {
            return "diagnostic".equals(category);
        }

        boolean isApplianceState() {
            return domain().equals("sensor") && (entityId.endsWith("_machine_state") || entityId.endsWith("_job_state"));
        }
    }

    private static final String[] OPENINGS = {"door", "window", "opening", "garage_door"};

    /** The kind, by the most important thing the device does: a leak sensor's battery doesn't make it a battery. */
    static DeviceKind kindOf(List<EntityInfo> entities) {
        if (entities.stream().anyMatch(e -> "mobile_app".equals(e.platform()))) return DeviceKind.PHONE;
        if (any(entities, e -> e.is("binary_sensor", "moisture"))) return DeviceKind.LEAK;
        if (any(entities, e -> e.is("binary_sensor", "smoke"))) return DeviceKind.SMOKE;
        if (any(entities, e -> e.is("binary_sensor", "carbon_monoxide", "gas"))) return DeviceKind.CO;
        if (any(entities, e -> e.is("lock"))) return DeviceKind.LOCK;
        if (any(entities, e -> e.is("climate"))) return DeviceKind.THERMOSTAT;
        if (any(entities, e -> e.is("camera"))) return DeviceKind.CAMERA;
        // A washer or dryer reports its cycle; a fridge has doors and a temperature.
        if (any(entities, EntityInfo::isApplianceState)
                || (any(entities, e -> e.is("binary_sensor", OPENINGS)) && any(entities, e -> e.is("sensor", "temperature")))) {
            return DeviceKind.APPLIANCE;
        }
        if (any(entities, e -> e.is("binary_sensor", "garage_door") || e.is("cover", "garage"))) return DeviceKind.GARAGE;
        if (any(entities, e -> e.is("binary_sensor", OPENINGS))) return DeviceKind.OPENING;
        boolean switches = any(entities, e -> e.is("switch") && !e.isSetting());
        if (switches && any(entities, e -> e.is("sensor", "power"))) return DeviceKind.PLUG;
        if (switches) return DeviceKind.SWITCH;
        return DeviceKind.OTHER;
    }

    /**
     * The entities Kudil records for a device of this kind: what the household cares about, not settings or signal
     * strength. Appliances keep exactly what Kudil recorded for them before (#99 changes nothing for them).
     */
    static List<Device.Reading> readingsOf(DeviceKind kind, List<EntityInfo> entities) {
        List<Device.Reading> readings = new ArrayList<>();
        for (EntityInfo e : entities) {
            if (e.isSetting()) continue;
            boolean battery = e.is("sensor", "battery");
            if (e.isDiagnostic() && !battery) continue;
            boolean wanted = switch (kind) {
                case LEAK -> e.is("binary_sensor", "moisture") || battery;
                case SMOKE -> e.is("binary_sensor", "smoke") || battery;
                case CO -> e.is("binary_sensor", "carbon_monoxide", "gas") || battery;
                case LOCK -> e.is("lock") || battery;
                case THERMOSTAT -> e.is("climate") || e.is("sensor", "temperature", "humidity") || battery;
                case APPLIANCE -> e.is("binary_sensor", OPENINGS) || e.is("sensor", "temperature") || e.isApplianceState();
                case GARAGE, OPENING -> e.is("binary_sensor", OPENINGS) || battery;
                case PLUG -> e.is("switch") || e.is("sensor", "power");
                case SWITCH -> e.is("switch") || battery;
                case CAMERA, PHONE, OTHER -> false;   // nothing to record yet
            };
            if (wanted) readings.add(new Device.Reading(e.entityId(), e.deviceClass()));
        }
        return readings;
    }

    private static boolean any(List<EntityInfo> entities, java.util.function.Predicate<EntityInfo> test) {
        return entities.stream().anyMatch(test);
    }
}
