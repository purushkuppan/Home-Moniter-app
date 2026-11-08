package com.homehub.alerts;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import com.homehub.weather.Weather;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Two daily notices from the weather forecast:
 * <ul>
 *   <li>at {@code freezeCheck} (19:00): "Freeze warning: low of 28 °F tonight" when tomorrow's low is at or below
 *       {@code freezeBelow};</li>
 *   <li>at {@code rainCheck} (07:00): "Rain expected today: 0.31 in" when today's forecast has at least
 *       {@code rainFrom} of rain or snow.</li>
 * </ul>
 * Each check runs once a day. If Kudil was down at the check time it runs as soon as it is back, but not more than
 * {@link #LATE_LIMIT} late. Without a forecast the check is tried again on the next call. No I/O and no clock of its
 * own; thread-safe.
 */
final class WeatherAlerts {

    /** Device names the notices are stored under, so a restart can tell which checks were already done today. */
    static final String FREEZE_DEVICE = "Weather/Freeze";
    static final String RAIN_DEVICE = "Weather/Rain";
    static final Duration LATE_LIMIT = Duration.ofHours(3);

    /** One daily check: when it runs and on which day it last ran. */
    private static final class Check {
        final Double limit;
        final LocalTime at;
        LocalDate done;

        Check(Double limit, LocalTime at) {
            this.limit = limit;
            this.at = at;
        }
    }

    private final ZoneId zone;
    private final Check freeze;
    private final Check rain;

    WeatherAlerts(AlertProperties properties, ZoneId zone) {
        this.zone = zone;
        this.freeze = new Check(properties.freezeBelow(), properties.freezeCheck());
        this.rain = new Check(properties.rainFrom(), properties.rainCheck());
    }

    /** Remembers the notices already sent today (newest event of every reading), so a restart doesn't repeat them. */
    synchronized void seed(List<Event> latestPerReading) {
        for (Event e : latestPerReading) {
            if (!Level.NOTICE.eventType.equals(e.type()) || e.time() == null) continue;
            LocalDate day = e.time().atZone(zone).toLocalDate();
            if (FREEZE_DEVICE.equals(e.device())) freeze.done = day;
            else if (RAIN_DEVICE.equals(e.device())) rain.done = day;
        }
    }

    /** Whether a check is due, so the forecast is only read when it's needed. */
    synchronized boolean due(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        return due(freeze, local) || due(rain, local);
    }

    /**
     * Runs the checks that are due against the forecast. A check counts as done once the forecast has its day,
     * whether or not that day needs a notice; without the day (no forecast, stale result) it is tried again later.
     */
    synchronized List<Alert> check(Instant now, Weather weather) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        List<Alert> notices = new ArrayList<>();
        if (weather == null || weather.stale()) return notices;

        if (due(freeze, local)) {
            day(weather, today.plusDays(1)).ifPresent(tomorrow -> {
                freeze.done = today;
                if (tomorrow.low() != null && tomorrow.low() <= freeze.limit) {
                    notices.add(new Alert("weather:freeze", Level.NOTICE, FREEZE_DEVICE,
                            "Freeze warning: low of " + number(tomorrow.low()) + unit(weather.temperatureUnit())
                                    + " tonight", now));
                }
            });
        }
        if (due(rain, local)) {
            day(weather, today).ifPresent(day -> {
                rain.done = today;
                if (day.precipitation() != null && day.precipitation() >= rain.limit) {
                    notices.add(new Alert("weather:rain", Level.NOTICE, RAIN_DEVICE,
                            what(day.condition()) + " expected today: " + number(day.precipitation())
                                    + unit(weather.precipitationUnit()), now));
                }
            });
        }
        return notices;
    }

    private static boolean due(Check check, ZonedDateTime now) {
        if (check.limit == null || now.toLocalDate().equals(check.done)) return false;
        ZonedDateTime at = now.toLocalDate().atTime(check.at).atZone(now.getZone());
        return !now.isBefore(at) && now.isBefore(at.plus(LATE_LIMIT));
    }

    /** The forecast for a local date. Forecast times are matched in the hub's time zone. */
    private Optional<Weather.Day> day(Weather weather, LocalDate date) {
        for (Weather.Day day : weather.days()) {
            try {
                if (day.time() != null && OffsetDateTime.parse(day.time()).atZoneSameInstant(zone).toLocalDate().equals(date)) {
                    return Optional.of(day);
                }
            } catch (DateTimeParseException ex) {
                // skip a day without a readable time
            }
        }
        return Optional.empty();
    }

    private static String what(String condition) {
        return switch (condition == null ? "" : condition) {
            case "snowy" -> "Snow";
            case "snowy-rainy" -> "Rain or snow";
            default -> "Rain";
        };
    }

    /** 28.0 -> "28", 0.314 -> "0.31" */
    private static String number(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** "°F" -> " °F", "in" -> " in" */
    private static String unit(String unit) {
        return unit == null || unit.isBlank() ? "" : " " + unit.trim();
    }
}
