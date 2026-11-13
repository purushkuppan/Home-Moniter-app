package com.homehub.devices;

import com.homehub.ApiDocs;
import com.homehub.alerts.AlertProperties;
import com.homehub.alerts.DefaultAlertProperties;
import com.homehub.controls.ControlPin;
import com.homehub.events.EventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
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
@Tag(name = "Devices", description = "The Devices list: what Kudil watches, and each device's alert choices")
public class DeviceController {

    /** One device, as the dashboard shows it. */
    /**
     * One device, as the dashboard shows it.
     *
     * @param alerts     its ready-made alerts in words (#100), e.g. "Critical alert when wet"
     * @param openFor    doors and windows: its "alert if open longer than" in minutes (0 = never), null = the default
     * @param openForChoices doors and windows: the minutes that can be chosen; empty for other devices
     */
    public record DeviceView(long id, String name, String suggestedName, String room, String kind, String kindLabel,
                             String maker, String status, List<String> measures, boolean inHomeAssistant,
                             List<String> alerts, Integer openFor, int defaultOpenFor, List<Integer> openForChoices,
                             List<Setting> settings) {
    }

    /** "confirm" (with name), "hide", "show", or "open-for" (with minutes; null = back to the default). */
    public record Choice(String action, String name, Integer minutes, String key, String value) {
    }

    private static final String PIN_HEADER = "X-HomeHub-Pin";

    private final DeviceCatalog catalog;
    private final ControlPin pin;
    private final DefaultAlertProperties defaults;
    private final AlertProperties configured;
    private final EventRepository events;

    public DeviceController(DeviceCatalog catalog, ControlPin pin, DefaultAlertProperties defaults,
                            AlertProperties configured, EventRepository events) {
        this.catalog = catalog;
        this.pin = pin;
        this.defaults = defaults;
        this.configured = configured;
        this.events = events;
    }

    @GetMapping
    @Operation(summary = "Every device Kudil knows, new ones to review first")
    public List<DeviceView> list() {
        List<DeviceView> views = new ArrayList<>();
        for (Device.Status status : List.of(Device.Status.NEW, Device.Status.CONFIRMED, Device.Status.HIDDEN)) {
            catalog.all().stream().filter(d -> d.status() == status).forEach(d -> views.add(view(d)));
        }
        return views;
    }

    @PostMapping("/{id}")
    @Operation(summary = "Confirm, rename, hide or show a device, or change one of its alert choices",
            description = "Body `action`: `confirm` (with `name`), `hide`, `show`, `open-for` (with `minutes`; none = "
                    + "the default) or `setting` (with `key` and `value`). Needs the household PIN, since it decides "
                    + "what is watched.",
            security = @SecurityRequirement(name = ApiDocs.PIN))
    public ResponseEntity<Map<String, Object>> choose(@PathVariable long id, @RequestBody(required = false) Choice choice,
                                                      @Parameter(hidden = true) @RequestHeader(value = PIN_HEADER, required = false) String attempt) {
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
                case "open-for" -> catalog.setOpenFor(id, choice.minutes());
                case "setting" -> catalog.setChoice(id, choice.key(), choice.value());
                default -> throw new IllegalArgumentException("Choose confirm, hide, show, open-for or setting.");
            };
            String done = action.equals("setting") ? d.name() + ": saved."
                    : action.equals("open-for") ? d.name() + ": " + openForWords(d) + "."
                    : switch (d.status()) {
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

    /**
     * One alert setting of an appliance (#100), as a choice on the Devices list.
     *
     * @param key     what is set: "open-for:Fridge", "too-warm:Freezer", "finished", "offline"
     * @param label   "Fridge door: alert if open"
     * @param value   the household's choice, or "" for the default
     * @param choices what can be chosen; the first ("") is the default, with what it is
     */
    public record Setting(String key, String label, String value, List<Option> choices) {
    }

    public record Option(String value, String label) {
    }

    /** The alert settings of a confirmed appliance: each door, each temperature, its cycles, going offline. */
    List<Setting> settingsOf(Device d) {
        if (d.kind() != DeviceKind.APPLIANCE || d.status() != Device.Status.CONFIRMED) return List.of();
        String prefix = d.name().toLowerCase(Locale.ROOT) + "/";
        Map<String, String> doors = new java.util.TreeMap<>(), temperatures = new java.util.TreeMap<>();
        boolean cycles = false;
        for (com.homehub.events.Event e : events.latestPerReading()) {
            String label = e.device() == null ? "" : e.device();
            String lower = label.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix)) {
                String part = label.substring(prefix.length());
                if ("contact".equals(e.type())) doors.put(part, label);
                if ("temperature".equals(e.type())) temperatures.put(part, unitOf(e.value()));
            } else if (lower.equals(d.name().toLowerCase(Locale.ROOT)) && "machineState".equals(e.type())) {
                cycles = true;
            }
        }
        List<Setting> settings = new ArrayList<>();
        doors.forEach((part, label) -> {
            Duration own = configuredFor(configured.doorOpen(), label);
            long minutes = (own != null ? own : defaults.openFor()).toMinutes();
            List<Option> options = new ArrayList<>(List.of(new Option("", minutes + " min (default)"),
                    new Option("off", "Never")));
            DeviceCatalog.OPEN_FOR_CHOICES.stream().filter(m -> m > 0)
                    .forEach(m -> options.add(new Option(String.valueOf(m), m + " min")));
            settings.add(new Setting("open-for:" + part, part + " door: alert if open longer than", choiceOf(d, "open-for:" + part), options));
        });
        temperatures.forEach((part, unit) -> {
            Double own = configuredFor(configured.tooWarm(), d.name() + "/" + part);
            List<Option> options = new ArrayList<>();
            options.add(new Option("", own == null ? "Never (default)" : plain(own) + unit + " (default)"));
            options.add(new Option("off", "Never"));
            double center = own != null ? own : (unit.contains("C") ? 4 : 40);
            for (int t = (int) Math.round(center) - 5; t <= Math.round(center) + 5; t++) {
                options.add(new Option(String.valueOf(t), t + unit));
            }
            settings.add(new Setting("too-warm:" + part, part + ": alert when warmer than", choiceOf(d, "too-warm:" + part), options));
        });
        if (cycles) {
            boolean own = configured.finished().stream().anyMatch(m -> m.equalsIgnoreCase(d.name()));
            settings.add(new Setting("finished", "When a cycle finishes", choiceOf(d, "finished"), List.of(
                    new Option("", (own ? "Tell me" : "Don't tell me") + " (default)"),
                    new Option("on", "Tell me"), new Option("off", "Don't tell me"))));
        }
        Duration offline = configuredFor(configured.offline(), d.name());
        List<Option> options = new ArrayList<>(List.of(
                new Option("", (offline == null ? "Never" : offline.toMinutes() + " min") + " (default)"),
                new Option("off", "Never")));
        DeviceCatalog.OFFLINE_CHOICES.forEach(m -> options.add(new Option(String.valueOf(m), m + " min")));
        settings.add(new Setting("offline", "Alert when it stops reporting for", choiceOf(d, "offline"), options));
        return settings;
    }

    private static String choiceOf(Device d, String key) {
        return d.alertChoices().entrySet().stream().filter(c -> c.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }

    /** A configured setting by dashboard name, whatever its case ("Refrigerator/Fridge"). */
    private static <T> T configuredFor(Map<String, T> configured, String label) {
        return configured.entrySet().stream().filter(c -> c.getKey().equalsIgnoreCase(label))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** "43 F" -> " °F" */
    private static String unitOf(String value) {
        String unit = value == null ? "" : value.replaceAll("^[-0-9.\\s]+", "").trim();
        return unit.equalsIgnoreCase("F") || unit.equalsIgnoreCase("C") ? " °" + unit.toUpperCase(Locale.ROOT) : unit.isEmpty() ? "" : " " + unit;
    }

    private static String plain(double n) {
        return java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
    }

    /** "Alert when open longer than 10 min (default)", "No alert when left open". */
    private String openForWords(Device d) {
        if (d.openForMinutes() != null && d.openForMinutes() == 0) return "No alert when left open";
        long minutes = d.openForMinutes() != null ? d.openForMinutes() : defaults.openFor().toMinutes();
        return "Alert when open longer than " + minutes + " min" + (d.openForMinutes() == null ? " (default)" : "");
    }

    /** The ready-made alerts of a device (#100), in words. */
    private List<String> alertsOf(Device d) {
        List<String> alerts = new ArrayList<>();
        switch (d.kind()) {
            case LEAK -> alerts.add("Critical alert when wet");
            case SMOKE -> alerts.add("Critical alert on smoke");
            case CO -> alerts.add("Critical alert on carbon monoxide");
            case OPENING, GARAGE -> alerts.add(openForWords(d) + ", a warning at night");
            case LOCK -> alerts.add("Warning when jammed");
            default -> { }
        }
        if (d.readings().stream().anyMatch(r -> r.kindKey().equals("sensor/battery"))) alerts.add("Low battery notice, weekly");
        return alerts;
    }

    DeviceView view(Device d) {
        Set<String> measures = new LinkedHashSet<>();
        for (Device.Reading r : d.readings()) {
            String what = MEASURES.get(r.kindKey());
            if (what == null && r.entityId().endsWith("_machine_state")) what = "Cycle";
            if (what != null && !r.entityId().endsWith("_job_state")) measures.add(what);
        }
        String maker = String.join(" ", java.util.stream.Stream.of(d.manufacturer(), d.model())
                .filter(s -> s != null && !s.isBlank()).toList());
        return new DeviceView(d.id(), d.name(), suggestedName(d), d.room(), d.kind().name().toLowerCase(Locale.ROOT),
                d.kind().label, maker, d.status().name().toLowerCase(Locale.ROOT), List.copyOf(measures), d.inHomeAssistant(),
                alertsOf(d), d.openForMinutes(), (int) defaults.openFor().toMinutes(),
                d.kind() == DeviceKind.OPENING || d.kind() == DeviceKind.GARAGE ? DeviceCatalog.OPEN_FOR_CHOICES : List.of(),
                settingsOf(d));
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
