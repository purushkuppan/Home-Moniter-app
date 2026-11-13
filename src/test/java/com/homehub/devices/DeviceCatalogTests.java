package com.homehub.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.RegistrySnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Devices from Home Assistant's registries (#99), with the include patterns from application.properties. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/devices.db",
        "homehub.connectors.enabled=false",
        "homehub.alerts.enabled=false"
})
@RecordApplicationEvents
class DeviceCatalogTests {

    @Autowired DeviceCatalog catalog;
    @Autowired EventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents published;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM devices");
        jdbc.update("DELETE FROM events");
    }

    /** Builds a registry snapshot like Home Assistant's WebSocket answers. */
    static final class Snapshot {
        final List<Map<String, Object>> areas = new ArrayList<>(), devices = new ArrayList<>(),
                entities = new ArrayList<>(), states = new ArrayList<>();

        Snapshot area(String id, String name) {
            areas.add(Map.of("area_id", id, "name", name));
            return this;
        }

        Snapshot device(String id, String name, String maker, String model, String area, String entryType) {
            Map<String, Object> d = new HashMap<>();
            d.put("id", id);
            d.put("name", name);
            d.put("name_by_user", null);
            d.put("manufacturer", maker);
            d.put("model", model);
            d.put("area_id", area);
            d.put("entry_type", entryType);
            d.put("disabled_by", null);
            devices.add(d);
            return this;
        }

        Snapshot entity(String entityId, String deviceId, String deviceClass, String category, String platform) {
            Map<String, Object> e = new HashMap<>();
            e.put("entity_id", entityId);
            e.put("device_id", deviceId);
            e.put("entity_category", category);
            e.put("platform", platform);
            e.put("disabled_by", null);
            entities.add(e);
            states.add(Map.of("entity_id", entityId, "state", "on", "attributes", Map.of("device_class", deviceClass)));
            return this;
        }

        Snapshot entity(String entityId, String deviceId, String deviceClass) {
            return entity(entityId, deviceId, deviceClass, null, "zwave_js");
        }

        RegistrySnapshot build() {
            return new RegistrySnapshot(areas, devices, entities, states);
        }
    }

    /** The hub's Home Assistant, Nov 2026 (abridged). */
    static Snapshot hub() {
        return new Snapshot()
                .area("kitchen", "Kitchen").area("living_room", "Living Room").area("bedroom", "Bedroom")
                .area("laundry_room", "Laundry room").area("office", "office")
                .device("sun", "Sun", null, null, null, "service")
                .entity("sensor.sun_next_dawn", "sun", "timestamp", "diagnostic", "sun")
                .device("hci0", "hci0 (50:BB:B5:47:C3:D3)", null, "Unknown", null, null)
                .device("fridge", "Refrigerator", "Samsung Electronics", "TP1X_REF_21K", "kitchen", null)
                .entity("binary_sensor.kitchen_refrigerator_fridge_door", "fridge", "door", null, "smartthings")
                .entity("binary_sensor.kitchen_refrigerator_freezer_door", "fridge", "door", null, "smartthings")
                .entity("sensor.kitchen_refrigerator_fridge_temperature", "fridge", "temperature", null, "smartthings")
                .entity("sensor.kitchen_refrigerator_power", "fridge", "power", null, "smartthings")
                .device("leak", "Water Leak XS Sensor", "Zooz", "ZSE42", "kitchen", null)
                .entity("binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected", "leak", "moisture")
                .entity("sensor.kitchen_water_leak_xs_sensor_battery_level", "leak", "battery", "diagnostic", "zwave_js")
                .entity("sensor.kitchen_water_leak_xs_sensor_node_status", "leak", "enum", "diagnostic", "zwave_js")
                .device("plug", "Indoor Smart Plug", "Zooz", "ZEN04", "bedroom", null)
                .entity("switch.indoor_smart_plug", "plug", "")
                .entity("sensor.indoor_smart_plug_electric_consumption_w", "plug", "power")
                .device("phone", "purushphone", "OnePlus", "CPH2655", null, null)
                .entity("sensor.purushphone_battery_level", "phone", "battery", "diagnostic", "mobile_app")
                .device("stick", "800 Series Long Range USB Controller", "Zooz", "ZST39 LR", "office", null)
                .entity("sensor.zst39_status", "stick", "enum", "diagnostic", "zwave_js");
    }

    private Device named(String haName) {
        return catalog.all().stream().filter(d -> d.haName().equals(haName)).findFirst().orElseThrow();
    }

    @Test
    void whatKudilAlreadyWatchedIsConfirmedAndTheRestIsNewToReview() {
        catalog.sync(hub().build());

        assertThat(catalog.all()).extracting(Device::haName, Device::kind, Device::status, Device::room).containsExactlyInAnyOrder(
                tuple("Refrigerator", DeviceKind.APPLIANCE, Device.Status.CONFIRMED, "Kitchen"),
                tuple("Water Leak XS Sensor", DeviceKind.LEAK, Device.Status.CONFIRMED, "Kitchen"),
                tuple("Indoor Smart Plug", DeviceKind.PLUG, Device.Status.CONFIRMED, "Bedroom"),
                tuple("purushphone", DeviceKind.PHONE, Device.Status.NEW, null),
                tuple("800 Series Long Range USB Controller", DeviceKind.OTHER, Device.Status.NEW, "office"));
        // Sun is a service and hci0 has no entities: not things in the house.
        // Recorded under the same names as before #99.
        assertThat(catalog.records("binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected")).isTrue();
        assertThat(catalog.records("sensor.indoor_smart_plug_electric_consumption_w")).isTrue();
        assertThat(catalog.records("sensor.kitchen_refrigerator_power")).as("appliances: as before, no power").isFalse();
        assertThat(catalog.records("sensor.purushphone_battery_level")).as("new: nothing recorded").isFalse();
        assertThat(catalog.deviceName("sensor.kitchen_refrigerator_fridge_temperature")).contains("Refrigerator");
        assertThat(catalog.onlyReadingOfItsKind("binary_sensor.kitchen_refrigerator_fridge_door")).as("one of two doors").isFalse();
        assertThat(catalog.onlyReadingOfItsKind("binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected")).isTrue();
        assertThat(events.latest(10)).as("the first sync finds everything already there: no news").isEmpty();
    }

    @Test
    void aNewDeviceIsNewsAndRecordsNothingUntilSomeoneConfirmsIt() {
        catalog.sync(hub().build());
        catalog.sync(hub()
                .device("leak2", "Water Leak XS Sensor", "Zooz", "ZSE42", "laundry_room", null)
                .entity("binary_sensor.water_leak_xs_sensor_water_leak_detected", "leak2", "moisture")
                .entity("sensor.water_leak_xs_sensor_battery_level", "leak2", "battery", "diagnostic", "zwave_js")
                .build());

        Device found = catalog.all().stream().filter(d -> d.haDeviceId().equals("leak2")).findFirst().orElseThrow();
        assertThat(found.status()).isEqualTo(Device.Status.NEW);
        assertThat(found.room()).isEqualTo("Laundry room");
        assertThat(catalog.records("binary_sensor.water_leak_xs_sensor_water_leak_detected")).isFalse();
        assertThat(events.latest(10)).extracting(Event::device, Event::type, Event::value).containsExactly(tuple("Kudil", "notice",
                "New device found in the laundry room: Water Leak XS Sensor (leak sensor). Review it under Settings > Devices."));
        assertThat(DeviceController.suggestedName(found)).isEqualTo("Laundry room leak sensor");

        published.clear();
        Device confirmed = catalog.confirm(found.id(), "  Laundry   leak sensor ");
        assertThat(confirmed.name()).isEqualTo("Laundry leak sensor");
        assertThat(confirmed.status()).isEqualTo(Device.Status.CONFIRMED);
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(catalog.records("binary_sensor.water_leak_xs_sensor_water_leak_detected")).isTrue();
        assertThat(catalog.records("sensor.water_leak_xs_sensor_battery_level")).isTrue();
        assertThat(catalog.deviceName("binary_sensor.water_leak_xs_sensor_water_leak_detected")).contains("Laundry leak sensor");
        assertThat(published.stream(DeviceCatalog.DevicesChanged.class)).as("the connector reads the states again").hasSize(1);

        catalog.sync(hub().device("leak2", "Water Leak XS Sensor", "Zooz", "ZSE42", "laundry_room", null)
                .entity("binary_sensor.water_leak_xs_sensor_water_leak_detected", "leak2", "moisture").build());
        assertThat(events.latest(10)).as("news once").hasSize(1);
    }

    @Test
    void aNameIsNeededToConfirmAndAConfirmedDeviceKeepsItsName() {
        catalog.sync(hub().build());
        Device phone = named("purushphone");
        assertThatThrownBy(() -> catalog.confirm(phone.id(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.confirm(phone.id(), "Phone/Purush")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.confirm(phone.id(), "x".repeat(41))).isInstanceOf(IllegalArgumentException.class);

        Device fridge = named("Refrigerator");
        assertThat(catalog.confirm(fridge.id(), "Fridge").name()).as("its history and alert settings use the name")
                .isEqualTo("Refrigerator");
    }

    @Test
    void aHiddenDeviceIsNeverRecordedNotEvenThroughAnIncludePattern() {
        catalog.sync(hub().build());
        Device plug = named("Indoor Smart Plug");

        catalog.hide(plug.id());
        assertThat(catalog.hidden("switch.indoor_smart_plug")).isTrue();
        assertThat(catalog.records("switch.indoor_smart_plug")).isFalse();
        assertThat(catalog.deviceName("switch.indoor_smart_plug")).isEmpty();

        assertThat(catalog.unhide(plug.id()).status()).as("back to watched: it was confirmed").isEqualTo(Device.Status.CONFIRMED);
        assertThat(catalog.hidden("switch.indoor_smart_plug")).isFalse();
        Device phone = named("purushphone");
        catalog.hide(phone.id());
        assertThat(catalog.unhide(phone.id()).status()).as("back to review: it never was").isEqualTo(Device.Status.NEW);
    }

    /** The hub plus the back door, shown as "Back door" through homehub.connectors.homeassistant.names. */
    static Snapshot hubWithBackDoor() {
        return hub().device("door", "Backdoor openclose", "Zooz", "ZSE41", "living_room", null)
                .entity("binary_sensor.living_room_backdoor_openclose", "door", "door")
                .entity("sensor.living_room_backdoor_openclose_battery_level", "door", "battery", "diagnostic", "zwave_js");
    }

    @Test
    void devicesAreNamedAsTheirReadingsAreAlreadyShown() {
        catalog.sync(hubWithBackDoor().build());

        assertThat(named("Backdoor openclose").name()).isEqualTo("Back door");
        assertThat(named("Water Leak XS Sensor").name()).isEqualTo("Kitchen leak sensor");
        assertThat(named("Refrigerator").name()).as("no name of its own: Home Assistant's").isEqualTo("Refrigerator");
    }

    @Test
    void aDoorsOpenAlertCanBeChangedOrSwitchedOff() {
        catalog.sync(hubWithBackDoor().build());
        Device door = named("Backdoor openclose");
        assertThat(catalog.openFor("Back door")).as("the default until changed").isEmpty();

        catalog.setOpenFor(door.id(), 2);
        assertThat(catalog.openFor("Back door")).contains(java.time.Duration.ofMinutes(2));
        assertThat(catalog.openFor("back door")).as("any case").contains(java.time.Duration.ofMinutes(2));
        catalog.setOpenFor(door.id(), 0);
        assertThat(catalog.openFor("Back door")).contains(java.time.Duration.ZERO);
        catalog.setOpenFor(door.id(), null);
        assertThat(catalog.openFor("Back door")).isEmpty();

        assertThatThrownBy(() -> catalog.setOpenFor(door.id(), 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.setOpenFor(named("Refrigerator").id(), 5))
                .as("the fridge has its own limits").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void homeAssistantChangesUpdateTheRoomButNotKudilsChoices() {
        catalog.sync(hub().build());
        Device phone = named("purushphone");
        catalog.confirm(phone.id(), "Purush's phone");

        Snapshot moved = hub();
        moved.devices.stream().filter(d -> d.get("id").equals("phone")).forEach(d -> d.put("area_id", "office"));
        moved.devices.removeIf(d -> d.get("id").equals("plug"));   // removed from Home Assistant
        catalog.sync(moved.build());

        Device after = catalog.all().stream().filter(d -> d.haDeviceId().equals("phone")).findFirst().orElseThrow();
        assertThat(after.room()).isEqualTo("office");
        assertThat(after.name()).isEqualTo("Purush's phone");
        assertThat(after.status()).isEqualTo(Device.Status.CONFIRMED);
        Device plug = named("Indoor Smart Plug");
        assertThat(plug.inHomeAssistant()).isFalse();
        assertThat(catalog.records("switch.indoor_smart_plug")).as("gone from Home Assistant: not recorded by the catalog")
                .isFalse();
    }
}
