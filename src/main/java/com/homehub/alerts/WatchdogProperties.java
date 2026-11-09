package com.homehub.alerts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of {@link SmartThingsWatchdog}, bound from {@code homehub.watchdog.*}.
 *
 * @param entity     a Home Assistant entity that reports every few minutes, day and night, e.g. the fridge's power
 *                   sensor; its integration is the one reloaded. Empty: no watchdog.
 * @param staleAfter how long that entity may stay silent before the integration is reloaded; the fridge reports
 *                   about every 16 minutes (longest normal gap seen: 23.5), so 45 minutes is about three missed reports
 */
@ConfigurationProperties("homehub.watchdog")
public record WatchdogProperties(String entity, @DefaultValue("45m") Duration staleAfter) {

    public WatchdogProperties {
        entity = entity == null ? "" : entity.trim();
        if (staleAfter == null) staleAfter = Duration.ofMinutes(45);
    }
}
