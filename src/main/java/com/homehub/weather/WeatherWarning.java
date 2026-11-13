package com.homehub.weather;

import java.time.Instant;

/**
 * An official weather warning for the home's location (#106), from a country's weather service.
 *
 * @param event      what it is, as the service names it: "Tornado Warning", "Freeze Watch"
 * @param level      how Kudil treats it
 * @param headline   the service's one-line summary
 * @param ends       when it ends (or expires), or null
 * @param hailInches the largest hail expected, from the warning itself, or null
 * @param link       a page with the full text for the home's location
 */
public record WeatherWarning(String event, Level level, String headline, Instant ends, Double hailInches, String link) {

    public enum Level {
        /** Life-threatening (tornado, flash flood): rings like a leak, repeats until someone answers (#101). */
        CRITICAL,
        /** Any other warning (winter storm, ice): a warning on phones and the dashboard. */
        WARNING,
        /** Watches, advisories, statements: a quiet notice. */
        NOTICE
    }
}
