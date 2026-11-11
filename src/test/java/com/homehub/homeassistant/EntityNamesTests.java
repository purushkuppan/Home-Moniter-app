package com.homehub.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.alerts.AlertProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The Z-Wave device names and the leak message (#36) bind from application.properties as written there. */
class EntityNamesTests {

    /** application.properties read by Spring Boot's own loader, exactly as the app reads it. */
    private static Binder binder() throws Exception {
        List<PropertySource<?>> sources = new PropertiesPropertySourceLoader()
                .load("application", new ClassPathResource("application.properties"));
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    @Test
    void zWaveEntitiesGetReadableNamesWithTheDotsInTheirIdsKept() throws Exception {
        EntityNames names = binder().bind("homehub.connectors.homeassistant", EntityNames.class).get();

        assertThat(names.nameOf("binary_sensor.living_room_backdoor_openclose")).isEqualTo("Back door");
        assertThat(names.nameOf("binary_sensor.kitchen_water_leak_xs_sensor_water_leak_detected")).isEqualTo("Kitchen leak sensor");
        assertThat(names.nameOf("sensor.kitchen_water_leak_xs_sensor_battery_level")).isEqualTo("Kitchen leak sensor");
        assertThat(names.nameOf("switch.indoor_smart_plug")).isEqualTo("Indoor Smart Plug");
        assertThat(names.nameOf("sensor.indoor_smart_plug_electric_consumption_w")).isEqualTo("Indoor Smart Plug");
        assertThat(names.nameOf("sensor.kitchen_refrigerator_fridge_temperature")).as("others keep their device name").isNull();
    }

    @Test
    void theKitchenLeakSensorHasItsMessage() throws Exception {
        AlertProperties alerts = binder().bind("homehub.alerts", AlertProperties.class).get();

        assertThat(alerts.leak()).isEqualTo(Map.of("Kitchen leak sensor", "Water leak in the kitchen"));
    }

    @Test
    void blankNamesAreIgnored() {
        assertThat(new EntityNames(Map.of("switch.a", " ", " switch.b ", " Plug ")).names()).isEqualTo(Map.of("switch.b", "Plug"));
        assertThat(new EntityNames(null).names()).isEmpty();
    }
}
