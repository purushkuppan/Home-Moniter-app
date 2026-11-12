package com.homehub.alerts;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Critical alerts (#101), bound from {@code homehub.alerts.critical.*}: problems that must wake someone, such as a water
 * leak at 3 AM. They ring as an alarm on phones (also on silent and in Do Not Disturb), carry an "I'm on it" button,
 * and repeat until someone taps it or the problem ends.
 *
 * @param kinds        which alerts are critical, by the start of their key: {@code leak} (later smoke, CO)
 * @param repeatEvery  how often an unanswered critical alert is sent again
 * @param maxRepeats   after this many repeats it stops (it stays on the dashboard until the problem ends)
 * @param escalateAfter when nobody has answered by then, it also goes to {@code escalateTo}
 * @param escalateTo   more Home Assistant notify actions (e.g. a relative's phone); empty: no escalation
 */
@ConfigurationProperties("homehub.alerts.critical")
public record CriticalAlertProperties(
        List<String> kinds,
        @DefaultValue("5m") Duration repeatEvery,
        @DefaultValue("24") Integer maxRepeats,
        @DefaultValue("10m") Duration escalateAfter,
        List<String> escalateTo) {

    public CriticalAlertProperties {
        kinds = kinds == null ? List.of("leak") : kinds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (repeatEvery == null || repeatEvery.isZero() || repeatEvery.isNegative()) repeatEvery = Duration.ofMinutes(5);
        if (maxRepeats == null || maxRepeats < 0) maxRepeats = 24;
        if (escalateAfter == null || escalateAfter.isNegative()) escalateAfter = Duration.ofMinutes(10);
        escalateTo = escalateTo == null ? List.of()
                : escalateTo.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** The defaults: water leaks are critical, repeated every 5 minutes, nobody else to escalate to. */
    public static CriticalAlertProperties defaults() {
        return new CriticalAlertProperties(null, null, null, null, null);
    }
}
