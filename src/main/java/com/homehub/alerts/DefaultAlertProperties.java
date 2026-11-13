package com.homehub.alerts;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Alerts every device of a kind gets on day one, without a rule of its own (#100), bound from
 * {@code homehub.alerts.defaults.*}. Leak, smoke and CO alarms are critical ({@link CriticalAlertProperties}), a battery
 * is low at {@code homehub.alerts.low-battery}; these are the rest.
 *
 * @param openFor    a door, window or garage door open longer than this (unless it has a limit of its own, like the
 *                   fridge, or one set on the Devices list): a notice by day, a warning at night
 * @param nightStart local time the night starts, normally 22:00
 * @param nightEnd   local time it ends, normally 06:00
 */
@ConfigurationProperties("homehub.alerts.defaults")
public record DefaultAlertProperties(
        @DefaultValue("10m") Duration openFor,
        @DefaultValue("22:00") LocalTime nightStart,
        @DefaultValue("06:00") LocalTime nightEnd) {

    public DefaultAlertProperties {
        if (openFor == null || openFor.isNegative()) openFor = Duration.ofMinutes(10);
        if (nightStart == null) nightStart = LocalTime.of(22, 0);
        if (nightEnd == null) nightEnd = LocalTime.of(6, 0);
    }

    public static DefaultAlertProperties defaults() {
        return new DefaultAlertProperties(null, null, null);
    }

    /** Whether this local time is at night (the night may span midnight). */
    boolean isNight(LocalTime time) {
        return nightStart.isAfter(nightEnd)
                ? !time.isBefore(nightStart) || time.isBefore(nightEnd)
                : !time.isBefore(nightStart) && time.isBefore(nightEnd);
    }
}
