package com.homehub.events;

import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long events are kept, bound from {@code homehub.events.*}. The usual values (90 and 365 days) are in
 * application.properties; a missing, empty, zero or negative number of days keeps that group forever.
 *
 * @param keepDays      days to keep readings and commands; 0 or empty: never delete them
 * @param keepAlertDays days to keep alerts, all-clears and notices; 0 or empty: never delete them
 * @param cleanupAt     local time of the daily cleanup, normally 03:30
 */
@ConfigurationProperties("homehub.events")
public record EventProperties(
        Integer keepDays,
        Integer keepAlertDays,
        @DefaultValue("03:30") LocalTime cleanupAt) {

    public EventProperties {
        keepDays = keepDays == null || keepDays < 0 ? 0 : keepDays;
        keepAlertDays = keepAlertDays == null || keepAlertDays < 0 ? 0 : keepAlertDays;
        if (cleanupAt == null) cleanupAt = LocalTime.of(3, 30);
    }

    /** Whether anything is ever deleted. */
    boolean cleanupOn() {
        return keepDays > 0 || keepAlertDays > 0;
    }
}
