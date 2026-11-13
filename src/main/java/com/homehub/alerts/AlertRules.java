package com.homehub.alerts;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides when to raise an alert, from the device readings HomeHub stores and the passing of time.
 * <ul>
 *   <li>A door that stays open longer than its limit, and "closed" once it shuts again. Every door, window or garage
 *       door without a limit of its own gets the ready-made one (#100, {@code openFor}, or what the Devices list says):
 *       a notice by day, a warning at night.</li>
 *   <li>A smoke or CO alarm going off, right away (critical), and "clear" afterwards; a lock that jams.</li>
 *   <li>A temperature above its limit for longer than {@code tooWarmFor}, and "back to normal" afterwards.</li>
 *   <li>A washer or dryer whose cycle ends (job state "finish(ed)", or the machine going from run/pause to stop).</li>
 *   <li>A battery at or below {@code lowBattery} percent (or a battery sensor that says "on" = low), right away, and
 *       "fine again" once it is a few points above the limit (a new battery), so a reading that wobbles around the
 *       limit is reported once.</li>
 *   <li>A leak sensor that reads "wet", right away (with its message from {@code leak}, e.g. "Water leak in the
 *       kitchen"), and "dry again" afterwards.</li>
 *   <li>A device whose readings are <em>all</em> "unavailable" for longer than its limit, and "back online" afterwards.
 *       A quiet device is not offline: readings are only stored when they change.</li>
 *   <li>Home Assistant unreachable for longer than {@code connectionLostAfter}, and "back now" afterwards.</li>
 * </ul>
 * Each problem is reported once. No I/O and no clock of its own, so it is easy to test; thread-safe.
 * Device names are compared without regard to case.
 */
final class AlertRules {

    private static final Set<String> RUNNING = Set.of("run", "pause");
    private static final Set<String> JOB_DONE = Set.of("finish", "finished");
    /** "44 F", "-3.5 C", "38" */
    private static final Pattern READING = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*([A-Za-z]*)");
    private static final String HOME_ASSISTANT = "Home Assistant";
    /** "15 %", "15%", "15" */
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%?");
    /** A low battery is fine again only this many points above the limit. */
    static final double BATTERY_CLEAR_MARGIN = 5;

    private final Map<String, Duration> doorLimits = new HashMap<>();
    private final Map<String, Double> warmLimits = new HashMap<>();
    private final Duration warmFor;
    private final Set<String> machines = new HashSet<>();
    private final Map<String, Duration> offlineLimits = new HashMap<>();   // by main device: "refrigerator"
    private final Duration connectionLostAfter;
    private final Double lowBattery;   // null: no battery warnings
    private final Map<String, String> leakMessages = new HashMap<>();   // by leak sensor
    private final DefaultAlertProperties defaults;
    private final ZoneId zone;                    // the hub's: "night" is local time
    private final DeviceAlertSettings deviceSettings;

    /** A door that is open, or a reading that is too warm: since when, and whether the alert has gone out. */
    private static final class Watch {
        final String device;
        final Instant since;
        String reading = "";
        String message;   // set once alerted
        Duration limit;   // doors: how long it may stay open
        boolean byDefault; // doors: the ready-made limit (#100), a notice by day
        double warmLimit;  // temperatures: the limit it is above

        Watch(String device, Instant since) {
            this.device = device;
            this.since = since;
        }
    }

    private final Map<String, Watch> openDoors = new LinkedHashMap<>();
    private final Map<String, Watch> tooWarm = new LinkedHashMap<>();
    private final Set<String> running = new HashSet<>();
    /** Per main device: whether each of its readings (device + type) is "unavailable" right now. */
    private final Map<String, Map<String, Boolean>> unavailable = new HashMap<>();
    private final Map<String, Watch> offline = new LinkedHashMap<>();
    private Watch connectionLost;   // null while Home Assistant is reachable
    private final Map<String, Watch> lowBatteries = new LinkedHashMap<>();   // reported at once, so message is always set
    private final Map<String, Watch> leaks = new LinkedHashMap<>();          // reported at once, so message is always set
    private final Map<String, Watch> alarms = new LinkedHashMap<>();         // smoke and CO, by "smoke:<device>"
    private final Map<String, Watch> jammed = new LinkedHashMap<>();         // locks

    /** Only the configured rules, no ready-made ones (as before #100). */
    AlertRules(AlertProperties properties) {
        this(properties, new DefaultAlertProperties(Duration.ZERO, null, null), ZoneOffset.UTC, DeviceAlertSettings.NONE);
    }

    AlertRules(AlertProperties properties, DefaultAlertProperties defaults, ZoneId zone, DeviceAlertSettings deviceSettings) {
        this.defaults = defaults;
        this.zone = zone;
        this.deviceSettings = deviceSettings;
        properties.doorOpen().forEach((device, limit) -> doorLimits.put(key(device), limit));
        properties.tooWarm().forEach((device, limit) -> warmLimits.put(key(device), limit));
        this.warmFor = properties.tooWarmFor();
        properties.finished().forEach(device -> machines.add(key(device)));
        properties.offline().forEach((device, limit) -> offlineLimits.put(key(device), limit));
        this.connectionLostAfter = properties.connectionLostAfter();
        this.lowBattery = properties.lowBattery();
        properties.leak().forEach((device, message) -> leakMessages.put(key(device), message.trim()));
    }

    /**
     * Picks up where things stood before a restart, from the newest event of every reading. Raises nothing itself;
     * a door that was already reported before the restart is not reported again.
     */
    synchronized void seed(List<Event> latestPerReading) {
        Map<String, Event> lastAlert = new HashMap<>();
        latestPerReading.stream()
                .sorted(Comparator.comparing(Event::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(e -> {
                    if (Level.PROBLEM.eventType.equals(e.type())) lastAlert.put(key(e.device()), e);
                    // A ready-made open-door alert by day is a notice (#100).
                    else if (Level.NOTICE.eventType.equals(e.type()) && e.value() != null && e.value().contains(" open for ")) {
                        lastAlert.put(key(e.device()), e);
                    } else onEvent(e);
                });
        for (Map<String, Watch> watches : List.of(openDoors, tooWarm, offline)) {
            watches.forEach((device, w) -> {
                Event alert = lastAlert.get(device);
                if (alert != null && alert.time() != null && !alert.time().isBefore(w.since)) w.message = alert.value();
            });
        }
    }

    /** A reading was saved. Returns what to tell people right away (an "all clear", or a finished cycle). */
    synchronized List<Alert> onEvent(Event e) {
        if (e.device() == null || e.type() == null || Alert.EVENT_TYPES.contains(e.type())) return List.of();
        String value = e.value() == null ? "" : e.value().trim().toLowerCase(Locale.ROOT);
        Instant time = e.time() != null ? e.time() : Instant.now();
        List<Alert> now = new ArrayList<>(availability(e, value, time));
        now.addAll(readingRules(e, value, time));
        return now;
    }

    /** Home Assistant became reachable or unreachable. Returns the "back now" message after a reported outage. */
    synchronized List<Alert> onConnection(boolean reachable, Instant time) {
        if (!reachable) {
            if (connectionLost == null) connectionLost = new Watch(HOME_ASSISTANT, time);
            return List.of();
        }
        Watch w = connectionLost;
        connectionLost = null;
        if (w == null || w.message == null) return List.of();   // a short blip: nobody was told, nothing to clear
        return List.of(new Alert(Alert.CONNECTION_KEY, Level.CLEARED, HOME_ASSISTANT,
                "Kudil lost Home Assistant for " + openFor(Duration.between(w.since, time)) + ". Back now.", time));
    }

    /** Tracks whether every reading of a watched device is "unavailable" (offline) or not. */
    private List<Alert> availability(Event e, String value, Instant time) {
        String main = mainKey(e.device());
        Duration offlineLimit = offlineLimit(mainName(e.device()));
        if (offlineLimit == null || "command".equals(e.type())) return List.of();
        Map<String, Boolean> readings = unavailable.computeIfAbsent(main, k -> new HashMap<>());
        readings.put(key(e.device()) + "|" + e.type(), value.equals("unavailable"));
        if (!readings.containsValue(false)) {   // everything this device reports is unavailable
            offline.putIfAbsent(main, new Watch(mainName(e.device()), time));
            return List.of();
        }
        Watch w = offline.remove(main);
        if (w == null || w.message == null) return List.of();
        return List.of(new Alert("offline:" + main, Level.CLEARED, w.device,
                w.device + " is back online. It was offline for " + openFor(Duration.between(w.since, time)) + ".", time));
    }

    private List<Alert> readingRules(Event e, String value, Instant time) {
        String device = key(e.device());
        switch (e.type()) {
            case "contact" -> {
                // What the Devices list says, else its own configured limit (the fridge), else the ready-made one (#100).
                Duration own = doorLimits.get(device);
                boolean byDefault = own == null;   // no limit of its own: a notice by day
                Duration limit = deviceSettings.openFor(e.device()).orElse(own != null ? own : defaults.openFor());
                if (limit.isZero()) {
                    openDoors.remove(device);   // no open-door alert for this one (switched off)
                    break;
                }
                if (value.equals("open")) {
                    Watch w = openDoors.computeIfAbsent(device, d -> new Watch(e.device(), time));
                    w.limit = limit;
                    w.byDefault = byDefault;
                } else if (value.equals("closed")) {
                    Watch w = openDoors.remove(device);
                    if (w != null && w.message != null) {
                        return List.of(new Alert("door:" + device, Level.CLEARED, w.device,
                                doorName(w.device) + " closed. It was open for "
                                        + openFor(Duration.between(w.since, time)) + ".", time));
                    }
                }
                // "unavailable" and the like say nothing about the door: keep watching.
            }
            case "smoke", "co" -> {
                String what = e.type().equals("smoke") ? "Smoke" : "Carbon monoxide";
                String alarmKey = e.type() + ":" + device;
                if (value.equals("detected") && !alarms.containsKey(alarmKey)) {
                    Watch w = new Watch(e.device(), time);
                    w.message = what + " detected: " + name(e.device()) + ". Get everyone out.";
                    alarms.put(alarmKey, w);
                    return List.of(new Alert(alarmKey, Level.PROBLEM, e.device(), w.message, time));
                }
                if (value.equals("clear")) {
                    Watch w = alarms.remove(alarmKey);
                    if (w != null) {
                        return List.of(new Alert(alarmKey, Level.CLEARED, w.device,
                                name(w.device) + " is clear again. " + what + " was detected for "
                                        + openFor(Duration.between(w.since, time)) + ".", time));
                    }
                }
            }
            case "lock" -> {
                if (value.equals("jammed") && !jammed.containsKey(device)) {
                    Watch w = new Watch(e.device(), time);
                    w.message = name(e.device()) + " is jammed. Check that it is fully locked.";
                    jammed.put(device, w);
                    return List.of(new Alert("lock:" + device, Level.PROBLEM, e.device(), w.message, time));
                }
                if (value.equals("locked") || value.equals("unlocked")) {
                    Watch w = jammed.remove(device);
                    if (w != null) {
                        return List.of(new Alert("lock:" + device, Level.CLEARED, w.device,
                                name(w.device) + " works again: " + value + ".", time));
                    }
                }
            }
            case "temperature" -> {
                Double limit = deviceSettings.tooWarm(e.device()).orElse(warmLimits.get(device));
                if (limit != null && limit.isNaN()) {   // switched off on the Devices list
                    tooWarm.remove(device);
                    break;
                }
                Matcher m = READING.matcher(value);
                if (limit == null || !m.matches()) break;   // no rule, or "unavailable"
                String reading = new BigDecimal(m.group(1)).stripTrailingZeros().toPlainString() + unit(m.group(2));
                if (Double.parseDouble(m.group(1)) > limit) {
                    Watch w = tooWarm.computeIfAbsent(device, d -> new Watch(e.device(), time));
                    w.reading = reading;
                    w.warmLimit = limit;
                } else {
                    Watch w = tooWarm.remove(device);
                    if (w != null && w.message != null) {
                        return List.of(new Alert("warm:" + device, Level.CLEARED, w.device,
                                name(w.device) + " temperature is back to normal: " + reading, time));
                    }
                }
            }
            case "machineState" -> {
                if (!reportsCycles(e.device())) break;
                if (RUNNING.contains(value)) running.add(device);
                else if (value.equals("stop") && running.remove(device)) return List.of(finished(e.device(), time));
            }
            case "jobState" -> {
                if (reportsCycles(e.device()) && JOB_DONE.contains(value) && running.remove(device)) {
                    return List.of(finished(e.device(), time));
                }
            }
            case "battery" -> {
                if (lowBattery == null) break;
                Matcher m = PERCENT.matcher(value);
                if (m.matches()) {
                    double percent = Double.parseDouble(m.group(1));
                    String reading = new BigDecimal(m.group(1)).stripTrailingZeros().toPlainString() + "%";
                    if (percent <= lowBattery) return batteryLow(e.device(), reading, time);
                    if (percent > lowBattery + BATTERY_CLEAR_MARGIN) return batteryFine(device, reading, time);
                } else if (value.equals("on")) {   // a battery sensor that only says low (on) or normal (off)
                    return batteryLow(e.device(), "", time);
                } else if (value.equals("off")) {
                    return batteryFine(device, "", time);
                }
                // "unavailable", or between the limit and the margin: no change
            }
            case "leak" -> {
                if (value.equals("wet") && !leaks.containsKey(device)) {
                    Watch w = new Watch(e.device(), time);
                    String message = leakMessages.get(device);
                    w.message = message == null || message.isEmpty() ? "Water leak: " + name(e.device()) : message;
                    leaks.put(device, w);
                    return List.of(new Alert("leak:" + device, Level.PROBLEM, e.device(), w.message, time));
                }
                if (value.equals("dry")) {
                    Watch w = leaks.remove(device);
                    if (w != null) {
                        return List.of(new Alert("leak:" + device, Level.CLEARED, w.device,
                                name(w.device) + " is dry again.", time));
                    }
                }
                // "unavailable" says nothing about water: keep the alert until the sensor reads dry
            }
            default -> { }
        }
        return List.of();
    }

    private List<Alert> batteryLow(String device, String reading, Instant time) {
        String key = key(device);
        if (lowBatteries.containsKey(key)) return List.of();   // reported once, however low it goes
        Watch w = new Watch(device, time);
        w.message = name(device) + " battery is low" + (reading.isEmpty() ? "" : ": " + reading) + ". Replace it soon.";
        lowBatteries.put(key, w);
        return List.of(new Alert("battery:" + key, Level.PROBLEM, device, w.message, time));
    }

    private List<Alert> batteryFine(String key, String reading, Instant time) {
        Watch w = lowBatteries.remove(key);
        if (w == null) return List.of();
        return List.of(new Alert("battery:" + key, Level.CLEARED, w.device,
                name(w.device) + " battery is fine again" + (reading.isEmpty() ? "." : ": " + reading + "."), time));
    }

    /** Raises the alerts whose time has come. Call regularly. */
    synchronized List<Alert> check(Instant now) {
        List<Alert> due = new ArrayList<>();
        openDoors.forEach((device, w) -> {
            if (w.message == null && w.limit != null && !now.isBefore(w.since.plus(w.limit))) {
                w.message = doorName(w.device) + " has been open for " + human(Duration.between(w.since, now));
                // A ready-made limit by day is worth a quiet notice; at night, or a limit of its own, a warning.
                boolean night = defaults.isNight(LocalTime.ofInstant(now, zone));
                Level level = w.byDefault && !night ? Level.NOTICE : Level.PROBLEM;
                due.add(new Alert("door:" + device, level, w.device, w.message, now));
            }
        });
        tooWarm.forEach((device, w) -> {
            if (w.message == null && !now.isBefore(w.since.plus(warmFor))) {
                w.message = name(w.device) + " is too warm: " + w.reading + " (above "
                        + BigDecimal.valueOf(w.warmLimit).stripTrailingZeros().toPlainString()
                        + unitOf(w.reading) + " for " + human(Duration.between(w.since, now)) + ")";
                due.add(new Alert("warm:" + device, Level.PROBLEM, w.device, w.message, now));
            }
        });
        offline.forEach((device, w) -> {
            Duration limit = offlineLimit(w.device);
            if (w.message == null && limit != null && !now.isBefore(w.since.plus(limit))) {
                w.message = w.device + " is offline. No data for " + human(Duration.between(w.since, now)) + ".";
                due.add(new Alert("offline:" + device, Level.PROBLEM, w.device, w.message, now));
            }
        });
        Watch lost = connectionLost;
        if (lost != null && lost.message == null && !now.isBefore(lost.since.plus(connectionLostAfter))) {
            lost.message = "Can't reach Home Assistant. No contact for " + human(Duration.between(lost.since, now)) + ".";
            due.add(new Alert(Alert.CONNECTION_KEY, Level.PROBLEM, HOME_ASSISTANT, lost.message, now));
        }
        return due;
    }

    /** Problems that have been reported and are still going on, oldest first. */
    synchronized List<Alert> active() {
        List<Alert> active = new ArrayList<>();
        openDoors.forEach((device, w) -> {
            if (w.message != null) active.add(new Alert("door:" + device, Level.PROBLEM, w.device, w.message, w.since));
        });
        tooWarm.forEach((device, w) -> {
            if (w.message != null) active.add(new Alert("warm:" + device, Level.PROBLEM, w.device, w.message, w.since));
        });
        offline.forEach((device, w) -> {
            if (w.message != null) active.add(new Alert("offline:" + device, Level.PROBLEM, w.device, w.message, w.since));
        });
        lowBatteries.forEach((device, w) ->
                active.add(new Alert("battery:" + device, Level.PROBLEM, w.device, w.message, w.since)));
        leaks.forEach((device, w) -> active.add(new Alert("leak:" + device, Level.PROBLEM, w.device, w.message, w.since)));
        alarms.forEach((key, w) -> active.add(new Alert(key, Level.PROBLEM, w.device, w.message, w.since)));
        jammed.forEach((device, w) -> active.add(new Alert("lock:" + device, Level.PROBLEM, w.device, w.message, w.since)));
        if (connectionLost != null && connectionLost.message != null) {
            active.add(new Alert(Alert.CONNECTION_KEY, Level.PROBLEM, HOME_ASSISTANT, connectionLost.message,
                    connectionLost.since));
        }
        active.sort(Comparator.comparing(Alert::time));
        return active;
    }

    private static Alert finished(String device, Instant time) {
        return new Alert("finished:" + key(device), Level.NOTICE, device, name(device) + " finished", time);
    }

    private static String key(String device) {
        return device.trim().toLowerCase(Locale.ROOT);
    }

    /** "Refrigerator/Fridge" -> "Refrigerator" */
    private static String mainName(String device) {
        int slash = device.indexOf('/');
        return (slash < 0 ? device : device.substring(0, slash)).trim();
    }

    private static String mainKey(String device) {
        return key(mainName(device));
    }

    /** How long this device ("Refrigerator") may be all unavailable before it is offline; null = not watched. */
    private Duration offlineLimit(String mainName) {
        Duration limit = deviceSettings.offlineAfter(mainName).orElse(offlineLimits.get(key(mainName)));
        return limit == null || limit.isZero() ? null : limit;
    }

    /** Whether the end of this washer's or dryer's cycles is reported: the Devices list, else the configured list. */
    private boolean reportsCycles(String device) {
        return deviceSettings.finished(mainName(device)).orElse(machines.contains(key(device)));
    }

    /** "Refrigerator/Fridge" -> "Fridge door", "Back door" -> "Back door", "Bedroom window" -> "Bedroom window" */
    static String doorName(String device) {
        String name = name(device);
        return name.toLowerCase(Locale.ROOT).matches(".*\\b(door|window|gate|garage)$") ? name : name + " door";
    }

    /** "Refrigerator/Fridge" -> "Fridge", "Washer" -> "Washer" */
    private static String name(String device) {
        return device.substring(device.lastIndexOf('/') + 1).trim();
    }

    /** "F" -> " °F", "" -> "" */
    private static String unit(String unit) {
        if (unit.isEmpty()) return "";
        return unit.equalsIgnoreCase("f") || unit.equalsIgnoreCase("c") ? " °" + unit.toUpperCase(Locale.ROOT) : " " + unit;
    }

    /** "47 °F" -> " °F" */
    private static String unitOf(String reading) {
        int space = reading.indexOf(' ');
        return space < 0 ? "" : reading.substring(space);
    }

    /** 0:00:40 -> "less than a minute", 0:03 -> "3 minutes", 1:05 -> "1 hour 5 minutes" */
    static String human(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        if (minutes < 1) return "less than a minute";
        if (minutes < 60) return plural(minutes, "minute");
        long rest = minutes % 60;
        return plural(minutes / 60, "hour") + (rest == 0 ? "" : " " + plural(rest, "minute"));
    }

    /**
     * How long a door was open, for its all-clear. Short openings are exact: 0:45 -> "45 s", 1:50 -> "1 min 50 s",
     * 3:00 -> "3 min". From 10 minutes on, the same wording as {@link #human}: "12 minutes", "1 hour 5 minutes".
     */
    static String openFor(Duration d) {
        long seconds = Math.max(0, d.toSeconds());
        if (seconds >= 600) return human(d);
        long minutes = seconds / 60, rest = seconds % 60;
        if (minutes == 0) return rest + " s";
        return minutes + " min" + (rest == 0 ? "" : " " + rest + " s");
    }

    private static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
