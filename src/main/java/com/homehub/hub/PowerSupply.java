package com.homehub.hub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reads the hub laptop's charger and battery from {@code /sys/class/power_supply}: no root and no extra software.
 * Each entry there is a folder with a {@code type} (Mains, Battery, USB) and its values as one-line files.
 */
public final class PowerSupply {

    /**
     * The power supply at one moment. Any part may be null when the hub doesn't report it.
     *
     * @param onMains       whether the charger delivers power
     * @param percent       battery charge, 0–100
     * @param healthPercent how much the battery holds compared with when it was new
     * @param temperatureC  battery temperature
     */
    public record Reading(Boolean onMains, Integer percent, Integer healthPercent, Double temperatureC) {
    }

    private PowerSupply() {
    }

    /** The current reading, or null when the folder has no charger and no battery (a desktop, a container). */
    public static Reading read(Path dir) {
        List<Path> supplies;
        try (Stream<Path> list = Files.list(dir)) {
            supplies = list.sorted().toList();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        Boolean mains = null;
        Path battery = null;
        for (Path supply : supplies) {
            String type = text(supply.resolve("type"));
            if (type == null) continue;
            switch (type.toLowerCase(Locale.ROOT)) {
                case "mains" -> {
                    Long online = number(supply.resolve("online"));
                    if (online != null) mains = Boolean.TRUE.equals(mains) || online == 1;
                }
                case "battery" -> {
                    if (battery == null && !"0".equals(text(supply.resolve("present")))) battery = supply;
                }
                default -> { }   // USB, wireless: not the laptop's charger
            }
        }
        if (mains == null && battery == null) return null;

        Integer percent = null, health = null;
        Double temperature = null;
        if (battery != null) {
            Long capacity = number(battery.resolve("capacity"));
            if (capacity != null) percent = (int) Math.max(0, Math.min(100, capacity));
            health = health(battery, "energy");
            if (health == null) health = health(battery, "charge");
            Long temp = number(battery.resolve("temp"));   // tenths of a degree Celsius
            if (temp != null) temperature = temp / 10.0;
            if (mains == null) {   // no Mains entry: the battery's status says it
                String status = text(battery.resolve("status"));
                if (status != null) mains = !status.equalsIgnoreCase("discharging");
            }
        }
        return new Reading(mains, percent, health, temperature);
    }

    /** energy_full / energy_full_design (or charge_*), in %. */
    private static Integer health(Path battery, String kind) {
        Long full = number(battery.resolve(kind + "_full"));
        Long design = number(battery.resolve(kind + "_full_design"));
        if (full == null || design == null || design <= 0) return null;
        return (int) Math.round(100.0 * full / design);
    }

    private static String text(Path file) {
        try {
            return Files.readString(file).trim();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static Long number(Path file) {
        String s = text(file);
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
