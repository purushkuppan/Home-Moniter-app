package com.homehub.devices;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.devices.DeviceKinds.EntityInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Device kinds and what is recorded (#99), on the devices of the hub's own Home Assistant (Nov 2026). */
class DeviceKindsTests {

    private static EntityInfo e(String id, String deviceClass) {
        return new EntityInfo(id, deviceClass, "", "zwave_js");
    }

    private static EntityInfo diag(String id, String deviceClass) {
        return new EntityInfo(id, deviceClass, "diagnostic", "zwave_js");
    }

    private static EntityInfo setting(String id, String deviceClass) {
        return new EntityInfo(id, deviceClass, "config", "zwave_js");
    }

    private static List<String> ids(List<Device.Reading> readings) {
        return readings.stream().map(Device.Reading::entityId).toList();
    }

    static final List<EntityInfo> LEAK_SENSOR = List.of(
            diag("sensor.kitchen_water_leak_xs_sensor_node_status", "enum"),
            setting("button.kitchen_water_leak_xs_sensor_ping", ""),
            e("binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected", "moisture"),
            diag("sensor.kitchen_water_leak_xs_sensor_battery_level", "battery"),
            setting("update.kitchen_water_leak_xs_sensor_firmware", "firmware"));

    static final List<EntityInfo> DOOR_SENSOR = List.of(
            e("binary_sensor.living_room_backdoor_openclose", "door"),
            diag("sensor.living_room_backdoor_openclose_battery_level", "battery"),
            diag("sensor.living_room_backdoor_openclose_node_status", "enum"));

    static final List<EntityInfo> PLUG = List.of(
            e("switch.indoor_smart_plug", ""),
            e("sensor.indoor_smart_plug_electric_consumption_w", "power"),
            e("sensor.indoor_smart_plug_electric_consumption_v", "voltage"),
            e("sensor.indoor_smart_plug_electric_consumption_kwh", "energy"),
            diag("binary_sensor.indoor_smart_plug_over_current", "safety"),
            setting("button.indoor_smart_plug_reset_accumulated_values", ""));

    static final List<EntityInfo> FRIDGE = List.of(
            e("binary_sensor.kitchen_refrigerator_fridge_door", "door"),
            e("binary_sensor.kitchen_refrigerator_freezer_door", "door"),
            e("sensor.kitchen_refrigerator_fridge_temperature", "temperature"),
            e("sensor.kitchen_refrigerator_freezer_temperature", "temperature"),
            e("sensor.kitchen_refrigerator_power", "power"),
            e("switch.kitchen_refrigerator_power_cool", ""),
            setting("number.kitchen_refrigerator_fridge_temperature", "temperature"));

    static final List<EntityInfo> WASHER = List.of(
            e("sensor.laundry_room_washer_machine_state", "enum"),
            e("sensor.laundry_room_washer_job_state", "enum"),
            e("sensor.laundry_room_washer_power", "power"),
            e("binary_sensor.laundry_room_washer_remote_control", ""),
            e("select.laundry_room_washer", ""));

    @Test
    void theHubsDevicesGetTheRightKind() {
        assertThat(DeviceKinds.kindOf(LEAK_SENSOR)).isEqualTo(DeviceKind.LEAK);
        assertThat(DeviceKinds.kindOf(DOOR_SENSOR)).isEqualTo(DeviceKind.OPENING);
        assertThat(DeviceKinds.kindOf(PLUG)).isEqualTo(DeviceKind.PLUG);
        assertThat(DeviceKinds.kindOf(FRIDGE)).as("doors and a temperature").isEqualTo(DeviceKind.APPLIANCE);
        assertThat(DeviceKinds.kindOf(WASHER)).as("a cycle").isEqualTo(DeviceKind.APPLIANCE);
        assertThat(DeviceKinds.kindOf(List.of(new EntityInfo("sensor.purushphone_battery_level", "battery", "diagnostic", "mobile_app"))))
                .isEqualTo(DeviceKind.PHONE);
        assertThat(DeviceKinds.kindOf(List.of(diag("sensor.zst39_status", "enum"), setting("update.zst39_firmware", "firmware"))))
                .as("the Z-Wave stick: nothing to watch, but not dropped").isEqualTo(DeviceKind.OTHER);
    }

    @Test
    void otherKindsByTheirMainFunction() {
        assertThat(DeviceKinds.kindOf(List.of(e("binary_sensor.hall_smoke", "smoke"), diag("sensor.hall_battery", "battery"))))
                .isEqualTo(DeviceKind.SMOKE);
        assertThat(DeviceKinds.kindOf(List.of(e("binary_sensor.hall_co", "carbon_monoxide")))).isEqualTo(DeviceKind.CO);
        assertThat(DeviceKinds.kindOf(List.of(e("lock.front_door", ""), diag("sensor.front_door_battery", "battery"))))
                .isEqualTo(DeviceKind.LOCK);
        assertThat(DeviceKinds.kindOf(List.of(e("climate.t6", ""), e("sensor.t6_temperature", "temperature"))))
                .isEqualTo(DeviceKind.THERMOSTAT);
        assertThat(DeviceKinds.kindOf(List.of(e("binary_sensor.garage", "garage_door")))).isEqualTo(DeviceKind.GARAGE);
        assertThat(DeviceKinds.kindOf(List.of(e("switch.porch_light", "")))).isEqualTo(DeviceKind.SWITCH);
        assertThat(DeviceKinds.kindOf(List.of(e("camera.porch", "")))).isEqualTo(DeviceKind.CAMERA);
        assertThat(DeviceKinds.kindOf(List.of(setting("switch.child_lock", "")))).as("a setting is not a switch")
                .isEqualTo(DeviceKind.OTHER);
    }

    @Test
    void onlyWhatTheHouseholdCaresAboutIsRecorded() {
        assertThat(ids(DeviceKinds.readingsOf(DeviceKind.LEAK, LEAK_SENSOR))).as("water and battery, no signal or firmware")
                .containsExactly("binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected",
                        "sensor.kitchen_water_leak_xs_sensor_battery_level");
        assertThat(ids(DeviceKinds.readingsOf(DeviceKind.OPENING, DOOR_SENSOR))).containsExactly(
                "binary_sensor.living_room_backdoor_openclose", "sensor.living_room_backdoor_openclose_battery_level");
        assertThat(ids(DeviceKinds.readingsOf(DeviceKind.PLUG, PLUG))).as("on/off and watts, not volts or kWh")
                .containsExactly("switch.indoor_smart_plug", "sensor.indoor_smart_plug_electric_consumption_w");
        assertThat(ids(DeviceKinds.readingsOf(DeviceKind.APPLIANCE, FRIDGE))).as("as before #99: doors and temperatures")
                .containsExactly("binary_sensor.kitchen_refrigerator_fridge_door", "binary_sensor.kitchen_refrigerator_freezer_door",
                        "sensor.kitchen_refrigerator_fridge_temperature", "sensor.kitchen_refrigerator_freezer_temperature");
        assertThat(ids(DeviceKinds.readingsOf(DeviceKind.APPLIANCE, WASHER))).as("as before #99: the cycle")
                .containsExactly("sensor.laundry_room_washer_machine_state", "sensor.laundry_room_washer_job_state");
        assertThat(DeviceKinds.readingsOf(DeviceKind.OTHER, List.of(diag("sensor.zst39_status", "enum")))).isEmpty();
    }

    @Test
    void aReadingKnowsItsKindAndIsStoredWithIt() {
        Device.Reading water = new Device.Reading("binary_sensor.kitchen_leak", "moisture");
        assertThat(water.kindKey()).isEqualTo("binary_sensor/moisture");
        assertThat(Device.Reading.parse(water.stored())).isEqualTo(water);
        assertThat(Device.Reading.parse("switch.plug/")).isEqualTo(new Device.Reading("switch.plug", ""));
    }
}
