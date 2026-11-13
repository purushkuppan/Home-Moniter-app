package com.homehub.alerts;

import com.homehub.alerts.Alert.Level;
import java.util.Locale;
import java.util.Optional;

/**
 * What to do next, for every kind of alert (#161), so an alert says more than what happened: "Water leak in the
 * kitchen" comes with "Turn off the water at the main valve, then dry the floor."
 * <p>
 * All next steps live here, one per alert kind (the start of the alert key, {@code leak} in
 * {@code leak:kitchen leak sensor}), so the wording can be checked in one place against the writing guide (#117):
 * plain words, one action first, no jargon. An all clear and plain news have none: there is nothing to do.
 */
public final class NextSteps {

    private NextSteps() {
    }

    /** The next step for this alert, if it has one. */
    public static Optional<String> of(Alert alert) {
        if (alert == null || alert.level() == Level.CLEARED || alert.key() == null) return Optional.empty();
        String key = alert.key();
        int colon = key.indexOf(':');
        String kind = colon < 0 ? key : key.substring(0, colon);
        String device = alert.device() == null ? "" : alert.device().toLowerCase(Locale.ROOT);
        String step = switch (kind) {
            case "leak" -> "Turn off the water at the main valve, then dry the floor and find where it comes from.";
            case "smoke" -> "Get everyone out now, then call 911 from outside.";
            case "co" -> "Get everyone out into fresh air now, then call 911 from outside.";
            case "door" -> door(device);
            case "warm" -> warm(device);
            case "lock" -> "Check that it is fully locked. Lock it by hand with the key if it is not.";
            case "battery" -> "Replace the battery soon, so the device keeps working.";
            case "offline" -> "Check that it has power. Unplug it for 10 seconds and plug it back in if it has.";
            case "connection" -> "Check that the hub laptop is on and connected. Restart Home Assistant if it is.";
            case "stale" -> "Open the SmartThings app and check that the appliances are online.";
            case "backup" -> "Check the hub's backup disk. Run the backup by hand if the disk is fine.";
            case "finished" -> device.contains("dryer")
                    ? "Take the clothes out soon, so they don't wrinkle."
                    : "Move the clothes to the dryer soon, so they don't smell.";
            case "weather", "severe" -> weather(key, alert.device());
            case "test" -> "Tap \"I'm on it\" to stop it ringing.";
            default -> null;
        };
        return Optional.ofNullable(step);
    }

    /** The message with its next step after it, for a phone notification: "Water leak in the kitchen\nTurn off ..." */
    static String withStep(Alert alert) {
        return of(alert).map(step -> alert.message() + "\n" + step).orElse(alert.message());
    }

    /** The same, read aloud on a phone (#150): "Water leak in the kitchen. Turn off ..." */
    static String spoken(Alert alert) {
        String message = alert.message().strip();
        return of(alert).map(step -> message + (message.matches(".*[.!?]$") ? " " : ". ") + step).orElse(message);
    }

    private static String door(String device) {
        if (device.contains("fridge") || device.contains("refrigerator") || device.contains("freezer")) {
            return "Close it and check that nothing blocks the door.";
        }
        if (device.contains("garage")) return "Check whether someone is still using it, then close it.";
        if (device.contains("window")) return "Close it if no one opened it on purpose.";
        return "Close it if no one is using it. Check who is home if you don't expect it open.";
    }

    private static String warm(String device) {
        if (device.contains("freezer")) {
            return "Check that the freezer door is shut and the fan runs. Move food to another freezer if it stays warm.";
        }
        if (device.contains("fridge") || device.contains("refrigerator")) {
            return "Check that the fridge door is shut. Move milk and meat to a cooler if it stays warm.";
        }
        return "Check the thermostat and that the heating or cooling is running.";
    }

    /** Freeze and rain notices by key; official warnings by their name ("Tornado Warning", the alert's device). */
    private static String weather(String key, String event) {
        return switch (key) {
            case "weather:freeze" -> "Bring in plants and pets, and cover outdoor taps tonight.";
            case "weather:rain" -> "Take an umbrella, and bring in anything that should stay dry.";
            default -> switch (event == null ? "" : event) {
                case "Tornado Warning", "Tornado Emergency" -> "Go to your safe room now.";
                case "Severe Thunderstorm Warning" -> "Stay inside, away from windows.";
                case "Flash Flood Warning", "Flash Flood Emergency" -> "Never drive through flooded roads.";
                case "Extreme Wind Warning" -> "Take shelter in an inside room.";
                case "Hurricane Warning", "Storm Surge Warning" -> "Follow local evacuation orders.";
                default -> "Follow what local officials say, and check the forecast for updates.";
            };
        };
    }
}
