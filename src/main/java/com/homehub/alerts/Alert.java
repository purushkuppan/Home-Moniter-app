package com.homehub.alerts;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Something the household should know about.
 *
 * @param key     identifies the situation, e.g. {@code door:refrigerator/fridge}; the "all clear" for a problem has the
 *                same key, so a phone can replace the first notification with the second
 * @param level   a problem, the end of a problem, or plain news such as a finished wash
 * @param device  dashboard device name, e.g. {@code Refrigerator/Fridge}
 * @param message the text people see, e.g. "Fridge door has been open for 1 minute"
 * @param time    when the problem started (active alerts) or when the alert was raised
 */
public record Alert(String key, Level level, String device, String message, Instant time) {

    public enum Level {
        PROBLEM("alert"), CLEARED("alertCleared"), NOTICE("notice");

        /** Event type used when the alert is stored, so it shows in the activity list. */
        final String eventType;

        Level(String eventType) {
            this.eventType = eventType;
        }
    }

    /** Event type of "Purushphone is on it: Water leak in the kitchen" (#101), written when someone answers. */
    static final String ACK_EVENT_TYPE = "alertAck";

    /** Event types written by alerts themselves; they are never device readings. */
    static final Set<String> EVENT_TYPES = Set.of("alert", "alertCleared", "notice", ACK_EVENT_TYPE);

    /** Key of the "can't reach Home Assistant" alert and its all-clear. */
    static final String CONNECTION_KEY = "connection:homeassistant";

    /**
     * Whether this alert can go to phones. Notifications travel through Home Assistant, so "can't reach Home
     * Assistant" is shown on the dashboard only; its "back now" all-clear does go to phones.
     */
    boolean reachesPhone() {
        return !(level == Level.PROBLEM && CONNECTION_KEY.equals(key));
    }

    /**
     * The page a tap on this alert's notification opens (#184): the dashboard, showing this alert, e.g.
     * {@code http://kudil:8080/#alert=finished%3Adryer&at=2026-11-14T18%3A02%3A00Z}. The exact time lets the dashboard
     * find the alert once it is over. Empty when there is no dashboard link.
     */
    String link(String dashboard) {
        if (dashboard == null || dashboard.isBlank()) return "";
        String base = dashboard.trim().replaceFirst("#.*$", "").replaceAll("/+$", "");
        String link = base + "/#alert=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        return time == null ? link : link + "&at=" + URLEncoder.encode(time.toString(), StandardCharsets.UTF_8);
    }

    /** The level an alert was stored with, from its event type ("notice" -> NOTICE); empty for other events. */
    static Optional<Level> levelOf(String eventType) {
        for (Level level : Level.values()) {
            if (level.eventType.equals(eventType)) return Optional.of(level);
        }
        return Optional.empty();
    }
}
