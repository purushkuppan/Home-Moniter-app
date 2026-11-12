package com.homehub.devices;

import com.homehub.controls.ControlPin;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Devices list (#99).
 * <ul>
 *   <li>{@code GET /api/devices}: every device Kudil knows, new ones to review first. No entity ids: the household
 *       sees names, rooms and what each device measures.</li>
 *   <li>{@code POST /api/devices/{id}} with {@code {"action": "confirm", "name": "Kitchen leak sensor"}}, {@code hide}
 *       or {@code show}: needs the household PIN (header {@code X-HomeHub-Pin}), since it decides what is watched.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    /** One device, as the dashboard shows it. */
    public record DeviceView(long id, String name, String suggestedName, String room, String kind, String kindLabel,
                             String maker, String status, List<String> measures, boolean inHomeAssistant) {
    }

    public record Choice(String action, String name) {
    }

    private static final String PIN_HEADER = "X-HomeHub-Pin";

    private final DeviceCatalog catalog;
    private final ControlPin pin;

    public DeviceController(DeviceCatalog catalog, ControlPin pin) {
        this.catalog = catalog;
        this.pin = pin;
    }

    @GetMapping
    public List<DeviceView> list() {
        List<DeviceView> views = new ArrayList<>();
        for (Device.Status status : List.of(Device.Status.NEW, Device.Status.CONFIRMED, Device.Status.HIDDEN)) {
            catalog.all().stream().filter(d -> d.status() == status).forEach(d -> views.add(view(d)));
        }
        return views;
    }

    @PostMapping("/{id}")
    public ResponseEntity<Map<String, Object>> choose(@PathVariable long id, @RequestBody(required = false) Choice choice,
                                                      @RequestHeader(value = PIN_HEADER, required = false) String attempt) {
        switch (pin.check(attempt)) {
            case OK -> { }
            case WRONG -> { return message(HttpStatus.UNAUTHORIZED, "Wrong PIN."); }
            case LOCKED -> {
                return message(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many wrong PINs. Try again in " + Math.max(1, (pin.lockedSeconds() + 59) / 60) + " minutes.");
            }
            case NOT_CONFIGURED -> { return message(HttpStatus.FORBIDDEN, "Set a PIN (homehub.control.pin) first."); }
        }
        String action = choice == null || choice.action() == null ? "" : choice.action().trim().toLowerCase(Locale.ROOT);
        try {
            Device d = switch (action) {
                case "confirm" -> catalog.confirm(id, choice.name());
                case "hide" -> catalog.hide(id);
                case "show" -> catalog.unhide(id);
                default -> throw new IllegalArgumentException("Choose confirm, hide or show.");
            };
            String done = switch (d.status()) {
                case CONFIRMED -> d.name() + " is watched now.";
                case HIDDEN -> d.name() + " is hidden.";
                case NEW -> d.name() + " is back on the list to review.";
            };
            return ResponseEntity.ok(Map.of("message", done, "device", view(d)));
        } catch (NoSuchElementException ex) {
            return message(HttpStatus.NOT_FOUND, "No such device.");
        } catch (IllegalArgumentException ex) {
            return message(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    static DeviceView view(Device d) {
        Set<String> measures = new LinkedHashSet<>();
        for (Device.Reading r : d.readings()) {
            String what = MEASURES.get(r.kindKey());
            if (what == null && r.entityId().endsWith("_machine_state")) what = "Cycle";
            if (what != null && !r.entityId().endsWith("_job_state")) measures.add(what);
        }
        String maker = String.join(" ", java.util.stream.Stream.of(d.manufacturer(), d.model())
                .filter(s -> s != null && !s.isBlank()).toList());
        return new DeviceView(d.id(), d.name(), suggestedName(d), d.room(), d.kind().name().toLowerCase(Locale.ROOT),
                d.kind().label, maker, d.status().name().toLowerCase(Locale.ROOT), List.copyOf(measures), d.inHomeAssistant());
    }

    /** "Kitchen leak sensor", from the room and the kind; the Home Assistant name when there is no room. */
    static String suggestedName(Device d) {
        if (d.status() != Device.Status.NEW) return d.name();
        if (d.room() == null || d.room().isBlank() || d.kind() == DeviceKind.OTHER || d.kind() == DeviceKind.PHONE) {
            return d.haName();
        }
        String kind = switch (d.kind()) {
            case OPENING -> "door sensor";
            default -> d.kind().label.toLowerCase(Locale.ROOT);
        };
        String room = d.room().trim();
        return Character.toUpperCase(room.charAt(0)) + room.substring(1) + " " + kind;
    }

    private static final Map<String, String> MEASURES = Map.ofEntries(
            Map.entry("binary_sensor/moisture", "Water"),
            Map.entry("binary_sensor/smoke", "Smoke"),
            Map.entry("binary_sensor/carbon_monoxide", "Carbon monoxide"),
            Map.entry("binary_sensor/gas", "Gas"),
            Map.entry("binary_sensor/door", "Open or closed"),
            Map.entry("binary_sensor/window", "Open or closed"),
            Map.entry("binary_sensor/opening", "Open or closed"),
            Map.entry("binary_sensor/garage_door", "Open or closed"),
            Map.entry("sensor/battery", "Battery"),
            Map.entry("sensor/temperature", "Temperature"),
            Map.entry("sensor/humidity", "Humidity"),
            Map.entry("sensor/power", "Power use"),
            Map.entry("switch/", "On or off"),
            Map.entry("lock/", "Locked or unlocked"),
            Map.entry("climate/", "Heating and cooling"));

    private static ResponseEntity<Map<String, Object>> message(HttpStatus status, String text) {
        return ResponseEntity.status(status).body(Map.of("message", text));
    }
}
