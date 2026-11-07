package com.homehub.alerts;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 *   <li>A door that stays open longer than its limit, and "closed" once it shuts again.</li>
 *   <li>A temperature above its limit for longer than {@code tooWarmFor}, and "back to normal" afterwards.</li>
 *   <li>A washer or dryer whose cycle ends (job state "finish(ed)", or the machine going from run/pause to stop).</li>
 * </ul>
 * Each problem is reported once. No I/O and no clock of its own, so it is easy to test; thread-safe.
 * Device names are compared without regard to case.
 */
final class AlertRules {

    private static final Set<String> RUNNING = Set.of("run", "pause");
    private static final Set<String> JOB_DONE = Set.of("finish", "finished");
    /** "44 F", "-3.5 C", "38" */
    private static final Pattern READING = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*([A-Za-z]*)");

    private final Map<String, Duration> doorLimits = new HashMap<>();
    private final Map<String, Double> warmLimits = new HashMap<>();
    private final Duration warmFor;
    private final Set<String> machines = new HashSet<>();

    /** A door that is open, or a reading that is too warm: since when, and whether the alert has gone out. */
    private static final class Watch {
        final String device;
        final Instant since;
        String reading = "";
        String message;   // set once alerted

        Watch(String device, Instant since) {
            this.device = device;
            this.since = since;
        }
    }

    private final Map<String, Watch> openDoors = new LinkedHashMap<>();
    private final Map<String, Watch> tooWarm = new LinkedHashMap<>();
    private final Set<String> running = new HashSet<>();

    AlertRules(AlertProperties properties) {
        properties.doorOpen().forEach((device, limit) -> doorLimits.put(key(device), limit));
        properties.tooWarm().forEach((device, limit) -> warmLimits.put(key(device), limit));
        this.warmFor = properties.tooWarmFor();
        properties.finished().forEach(device -> machines.add(key(device)));
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
                    else onEvent(e);
                });
        for (Map<String, Watch> watches : List.of(openDoors, tooWarm)) {
            watches.forEach((device, w) -> {
                Event alert = lastAlert.get(device);
                if (alert != null && alert.time() != null && !alert.time().isBefore(w.since)) w.message = alert.value();
            });
        }
    }

    /** A reading was saved. Returns what to tell people right away (an "all clear", or a finished cycle). */
    synchronized List<Alert> onEvent(Event e) {
        if (e.device() == null || e.type() == null || Alert.EVENT_TYPES.contains(e.type())) return List.of();
        String device = key(e.device());
        String value = e.value() == null ? "" : e.value().trim().toLowerCase(Locale.ROOT);
        Instant time = e.time() != null ? e.time() : Instant.now();

        switch (e.type()) {
            case "contact" -> {
                if (!doorLimits.containsKey(device)) break;
                if (value.equals("open")) {
                    openDoors.putIfAbsent(device, new Watch(e.device(), time));
                } else if (value.equals("closed")) {
                    Watch w = openDoors.remove(device);
                    if (w != null && w.message != null) {
                        return List.of(new Alert("door:" + device, Level.CLEARED, w.device,
                                name(w.device) + " door closed. It was open for "
                                        + openFor(Duration.between(w.since, time)) + ".", time));
                    }
                }
                // "unavailable" and the like say nothing about the door: keep watching.
            }
            case "temperature" -> {
                Double limit = warmLimits.get(device);
                Matcher m = READING.matcher(value);
                if (limit == null || !m.matches()) break;   // no rule, or "unavailable"
                String reading = new BigDecimal(m.group(1)).stripTrailingZeros().toPlainString() + unit(m.group(2));
                if (Double.parseDouble(m.group(1)) > limit) {
                    tooWarm.computeIfAbsent(device, d -> new Watch(e.device(), time)).reading = reading;
                } else {
                    Watch w = tooWarm.remove(device);
                    if (w != null && w.message != null) {
                        return List.of(new Alert("warm:" + device, Level.CLEARED, w.device,
                                name(w.device) + " temperature is back to normal: " + reading, time));
                    }
                }
            }
            case "machineState" -> {
                if (!machines.contains(device)) break;
                if (RUNNING.contains(value)) running.add(device);
                else if (value.equals("stop") && running.remove(device)) return List.of(finished(e.device(), time));
            }
            case "jobState" -> {
                if (machines.contains(device) && JOB_DONE.contains(value) && running.remove(device)) {
                    return List.of(finished(e.device(), time));
                }
            }
            default -> { }
        }
        return List.of();
    }

    /** Raises the alerts whose time has come. Call regularly. */
    synchronized List<Alert> check(Instant now) {
        List<Alert> due = new ArrayList<>();
        openDoors.forEach((device, w) -> {
            if (w.message == null && !now.isBefore(w.since.plus(doorLimits.get(device)))) {
                w.message = name(w.device) + " door has been open for " + human(Duration.between(w.since, now));
                due.add(new Alert("door:" + device, Level.PROBLEM, w.device, w.message, now));
            }
        });
        tooWarm.forEach((device, w) -> {
            if (w.message == null && !now.isBefore(w.since.plus(warmFor))) {
                w.message = name(w.device) + " is too warm: " + w.reading + " (above "
                        + BigDecimal.valueOf(warmLimits.get(device)).stripTrailingZeros().toPlainString()
                        + unitOf(w.reading) + " for " + human(Duration.between(w.since, now)) + ")";
                due.add(new Alert("warm:" + device, Level.PROBLEM, w.device, w.message, now));
            }
        });
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
        active.sort(Comparator.comparing(Alert::time));
        return active;
    }

    private static Alert finished(String device, Instant time) {
        return new Alert("finished:" + key(device), Level.NOTICE, device, name(device) + " finished", time);
    }

    private static String key(String device) {
        return device.trim().toLowerCase(Locale.ROOT);
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
