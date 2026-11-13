package com.homehub.brief;

import com.homehub.events.Event;
import com.homehub.weather.Weather;
import com.homehub.weather.WeatherWarning;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The evening summary of the day at home (#21), written from the day's events: doors, appliances, plugs, leaks,
 * alerts, and tomorrow's weather. Plain sentences, built by rules (no language model): instant, free, and the same
 * facts every time. Pure logic, so it is easy to test.
 */
final class DailyBrief {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private DailyBrief() {
    }

    /**
     * @param events   the day's events, oldest first
     * @param weather  the forecast, or null
     * @param warnings official weather warnings in force
     * @param day      the day being summed up, in the hub's time zone
     * @return the lines of the brief; the first is the heading
     */
    static List<String> lines(List<Event> events, Weather weather, List<WeatherWarning> warnings, LocalDate day, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        lines.add("Today at home, " + day.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)) + ":");

        String doors = doors(events);
        if (!doors.isEmpty()) lines.add("• " + doors);
        String cycles = cycles(events);
        if (!cycles.isEmpty()) lines.add("• " + cycles);
        switches(events).forEach(s -> lines.add("• " + s));

        List<String> leaks = events.stream().filter(e -> "leak".equals(e.type()) && "wet".equalsIgnoreCase(e.value()))
                .map(e -> "Water detected by " + e.device() + " at " + CLOCK.format(e.time().atZone(zone))).toList();
        boolean hasLeakSensor = events.stream().anyMatch(e -> "leak".equals(e.type()));
        List<Event> alerts = events.stream().filter(e -> "alert".equals(e.type())).toList();
        if (!leaks.isEmpty()) leaks.forEach(l -> lines.add("• " + l));
        if (alerts.isEmpty()) {
            lines.add("• " + (hasLeakSensor && leaks.isEmpty() ? "No leaks, no alerts" : "No alerts"));
        } else {
            String first = alerts.get(0).value();
            lines.add("• " + (alerts.size() == 1 ? "1 alert: " + first
                    : alerts.size() + " alerts, the first: " + first));
        }

        for (WeatherWarning w : warnings) {
            if (w.level() == WeatherWarning.Level.NOTICE) continue;
            lines.add("• In force: " + w.event() + (w.ends() == null ? "" : " until "
                    + w.ends().atZone(zone).format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US))));
        }
        String tomorrow = tomorrow(weather, day.plusDays(1));
        if (!tomorrow.isEmpty()) lines.add("Tomorrow: " + tomorrow);
        return lines;
    }

    /** "Fridge door opened 31 times (longest 2 min 20 s); back door 4 times" */
    static String doors(List<Event> events) {
        Map<String, int[]> opened = new LinkedHashMap<>();         // per door: times opened
        Map<String, Duration> longest = new LinkedHashMap<>();
        Map<String, Instant> openSince = new LinkedHashMap<>();
        for (Event e : events) {
            if (!"contact".equals(e.type()) || e.value() == null) continue;
            String door = doorName(e.device());
            if (e.value().equalsIgnoreCase("open")) {
                opened.computeIfAbsent(door, d -> new int[1])[0]++;
                openSince.putIfAbsent(door, e.time());
            } else if (e.value().equalsIgnoreCase("closed")) {
                Instant since = openSince.remove(door);
                if (since != null) longest.merge(door, Duration.between(since, e.time()), (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        List<String> parts = new ArrayList<>();
        opened.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))   // the busiest first
                .forEach(entry -> {
                    int n = entry.getValue()[0];
                    String what = parts.isEmpty() ? entry.getKey() + " opened " : lowerFirst(entry.getKey()) + " ";
                    Duration max = longest.get(entry.getKey());
                    // The longest opening is news only when it was a while (a quick look in the fridge isn't).
                    boolean worthSaying = max != null && parts.isEmpty() && max.toSeconds() >= 60;
                    parts.add(what + times(n) + (worthSaying ? " (longest " + duration(max) + ")" : ""));
                });
        return String.join("; ", parts);
    }

    /** "Washer ran 2 cycles, dryer 1" from the "Washer finished" notices. */
    static String cycles(List<Event> events) {
        Map<String, Integer> runs = new LinkedHashMap<>();
        for (Event e : events) {
            if ("notice".equals(e.type()) && e.value() != null && e.value().endsWith(" finished")) {
                runs.merge(e.value().substring(0, e.value().length() - " finished".length()), 1, Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        runs.forEach((machine, n) -> parts.add(parts.isEmpty()
                ? machine + " ran " + n + (n == 1 ? " cycle" : " cycles")
                : lowerFirst(machine) + " " + n));
        return String.join(", ", parts);
    }

    /** "Indoor Smart Plug was on for 2 h 15 min" for each switch turned on today. */
    static List<String> switches(List<Event> events) {
        Map<String, Duration> on = new LinkedHashMap<>();
        Map<String, Instant> since = new LinkedHashMap<>();
        Instant last = events.isEmpty() ? null : events.get(events.size() - 1).time();
        for (Event e : events) {
            if (!"switch".equals(e.type()) || e.value() == null) continue;
            if (e.value().equalsIgnoreCase("on")) {
                since.putIfAbsent(e.device(), e.time());
                on.putIfAbsent(e.device(), Duration.ZERO);
            } else if (e.value().equalsIgnoreCase("off")) {
                Instant start = since.remove(e.device());
                if (start != null) on.merge(e.device(), Duration.between(start, e.time()), Duration::plus);
            }
        }
        since.forEach((device, start) -> on.merge(device, Duration.between(start, last), Duration::plus));   // still on
        List<String> lines = new ArrayList<>();
        on.forEach((device, d) -> {
            boolean stillOn = since.containsKey(device);
            if (stillOn && d.toSeconds() < 60) lines.add(device + " is on now");   // just switched on
            else if (d.toSeconds() < 60) lines.add(device + " was on for less than a minute");
            else lines.add(device + " was on for " + duration(d) + (stillOn ? ", still on" : ""));
        });
        return lines;
    }

    /** "Sunny, 72°/52°, rain 0.2 in" for the forecast day after today. */
    static String tomorrow(Weather weather, LocalDate tomorrow) {
        if (weather == null || weather.days() == null) return "";
        for (Weather.Day d : weather.days()) {
            LocalDate date;
            try {
                date = OffsetDateTime.parse(d.time()).toLocalDate();
            } catch (DateTimeParseException | NullPointerException ex) {
                continue;
            }
            if (!date.equals(tomorrow)) continue;
            List<String> parts = new ArrayList<>();
            if (d.condition() != null && !d.condition().isBlank()) parts.add(condition(d.condition()));
            if (d.high() != null && d.low() != null) parts.add(Math.round(d.high()) + "°/" + Math.round(d.low()) + "°");
            if (d.precipitation() != null && d.precipitation() > 0) {
                parts.add("rain " + BigDecimal.valueOf(d.precipitation()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
                        .toPlainString() + " " + (weather.precipitationUnit() == null ? "" : weather.precipitationUnit()).trim());
            }
            return String.join(", ", parts).trim();
        }
        return "";
    }

    /** Home Assistant's condition in words: "partlycloudy" -> "Partly cloudy". */
    static String condition(String condition) {
        String words = switch (condition) {
            case "partlycloudy" -> "partly cloudy";
            case "clear-night" -> "clear";
            case "lightning-rainy" -> "thunderstorms";
            case "snowy-rainy" -> "sleet";
            case "windy-variant" -> "windy";
            default -> condition.replace('-', ' ').replace('_', ' ');
        };
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** "Refrigerator/Fridge" -> "Fridge door", "Back door" -> "Back door" */
    private static String doorName(String device) {
        String name = device.substring(device.lastIndexOf('/') + 1).trim();
        return name.toLowerCase(Locale.ROOT).matches(".*\\b(door|window|gate|garage)$") ? name : name + " door";
    }

    private static String times(int n) {
        return n == 1 ? "once" : n == 2 ? "twice" : n + " times";
    }

    /** 0:45 -> "45 s", 2:20 -> "2 min 20 s", 1:12:00 -> "1 h 12 min" */
    static String duration(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 60) return s + " s";
        if (s < 600) return (s / 60) + " min" + (s % 60 == 0 ? "" : " " + (s % 60) + " s");
        long minutes = Math.round(s / 60.0);
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " h" + (minutes % 60 == 0 ? "" : " " + (minutes % 60) + " min");
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
