package com.homehub.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerSupplyTests {

    @TempDir
    Path dir;

    private void supply(String name, Map<String, String> files) throws IOException {
        Path folder = Files.createDirectories(dir.resolve(name));
        for (var f : files.entrySet()) Files.writeString(folder.resolve(f.getKey()), f.getValue() + "\n");
    }

    @Test
    void readsTheChargerAndTheBattery() throws IOException {
        supply("AC", Map.of("type", "Mains", "online", "1"));
        supply("BAT0", Map.of("type", "Battery", "present", "1", "capacity", "87", "status", "Charging",
                "energy_full", "31200000", "energy_full_design", "41600000", "temp", "312"));
        supply("ucsi-source-psy-USBC000:001", Map.of("type", "USB", "online", "0"));

        assertThat(PowerSupply.read(dir)).isEqualTo(new PowerSupply.Reading(true, 87, 75, 31.2));
    }

    @Test
    void chargerUnpluggedAndChargeCountersInsteadOfEnergy() throws IOException {
        supply("ADP1", Map.of("type", "Mains", "online", "0"));
        supply("BAT1", Map.of("type", "Battery", "capacity", "64", "charge_full", "3000000", "charge_full_design", "4000000"));

        assertThat(PowerSupply.read(dir)).isEqualTo(new PowerSupply.Reading(false, 64, 75, null));
    }

    @Test
    void withoutAMainsEntryTheBatteryStatusSaysIt() throws IOException {
        supply("BAT0", Map.of("type", "Battery", "capacity", "50", "status", "Discharging"));
        assertThat(PowerSupply.read(dir).onMains()).isFalse();
    }

    @Test
    void nothingToReadOnADesktopOrAMissingFolder() throws IOException {
        assertThat(PowerSupply.read(dir)).as("empty").isNull();
        assertThat(PowerSupply.read(dir.resolve("missing"))).isNull();
        supply("BAT0", Map.of("type", "Battery", "present", "0"));
        assertThat(PowerSupply.read(dir)).as("no battery in the bay").isNull();
    }
}
