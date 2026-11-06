package com.homehub.controls;

import java.util.List;

/**
 * One thing the dashboard can change, as shown to the browser.
 *
 * @param id      Home Assistant entity id, e.g. {@code number.kitchen_refrigerator_fridge_temperature}
 * @param device  device it belongs to, e.g. "Refrigerator"
 * @param label   what it controls, e.g. "Fridge temperature"
 * @param kind    {@code number}, {@code switch}, {@code select}, {@code cycle} (start/pause/stop) or {@code button}
 * @param value   current value as text
 * @param min     numbers only
 * @param max     numbers only
 * @param step    numbers only
 * @param unit    numbers only, e.g. "°F"
 * @param options select and cycle only
 * @param note    extra information for the user, e.g. why remote start may not work
 */
public record Control(String id, String device, String label, String kind, String value,
                      Double min, Double max, Double step, String unit, List<String> options, String note) {
}
