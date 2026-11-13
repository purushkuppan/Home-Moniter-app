package com.homehub.hub;

import com.homehub.alerts.Alert;
import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Power cuts, internet outages and the hub laptop's own battery (#103). The hub is a laptop, so it keeps running on
 * battery when the power goes out, and can tell the family about it.
 * <ul>
 *   <li><b>Power:</b> the charger stops delivering power → "Power is out at home"; it comes back → "Power is back. It
 *       was out 1 hour 12 minutes, from 2:05 AM", and after more than {@code freezerCheckAfter} (2 hours) a reminder to
 *       check the freezer, with its temperature when Kudil knows it.</li>
 *   <li><b>Hub battery:</b> while on battery, a warning at each level in {@code batteryWarnings} (30 %, then 10 %).</li>
 *   <li><b>Internet:</b> no answer from any check host for {@code internetLostAfter} → "Internet is down". Phones
 *       can't be reached then, so power and hub battery alerts are <em>held</em> and sent when the internet is back,
 *       followed by "Internet is back. It was down 40 minutes, from 2:05 AM".</li>
 *   <li><b>Battery health</b> (issue comment): capacity against design capacity and temperature, logged weekly; a
 *       notice when capacity falls below {@code healthBelow} (60 %) or the battery runs hot.</li>
 * </ul>
 * What happened is logged at once as events of the device {@value #HOME} ({@code power}: lost/restored,
 * {@code internet}: lost/restored), whatever happens to the alerts. No I/O and no clock of its own; not thread-safe
 * (one scheduler thread calls it).
 */
public final class HubMonitor {

    static final String HOME = "Home";
    static final String POWER_DEVICE = "Home power";
    static final String INTERNET_DEVICE = "Internet";
    static final String BATTERY_DEVICE = "Hub battery";
    static final String POWER_KEY = "power:home";
    static final String INTERNET_KEY = "internet:home";
    static final String BATTERY_KEY = "hubbattery:hub";
    static final String HEALTH_KEY = "batteryhealth:hub";
    static final String HEALTH_TYPE = "batteryHealth";
    static final Duration HEALTH_EVERY = Duration.ofDays(7);
    /** A battery warmer than this (°C) is worth a look: heat is what wears and swells laptop batteries. */
    static final double HOT_C = 50;

    /** What to store and what to tell people after one check. */
    public record Outcome(List<Event> log, List<Alert> alerts) {
        static Outcome empty() {
            return new Outcome(new ArrayList<>(), new ArrayList<>());
        }
    }

    private final HubProperties settings;
    private final ZoneId zone;

    private Instant powerLostAt;          // null: on mains (or not known)
    private boolean powerKnown;
    private int warnedBelow = 101;        // the lowest battery warning sent during this outage
    private Instant internetDownSince;    // first failed check; null while reachable
    private boolean internetReported;     // "Internet is down" was raised
    private final List<Alert> held = new ArrayList<>();   // waiting for the internet
    private Instant lastHealth;
    private boolean healthWarned;
    private Instant lastCheck = Instant.EPOCH;   // "today" for times in messages

    public HubMonitor(HubProperties settings, ZoneId zone) {
        this.settings = settings;
        this.zone = zone;
    }

    /** After a restart: when battery health was last logged, so it stays weekly. */
    public void seed(List<Event> latestPerReading) {
        for (Event e : latestPerReading) {
            if (BATTERY_DEVICE.equals(e.device()) && HEALTH_TYPE.equals(e.type())) lastHealth = e.time();
        }
    }

    /**
     * One check.
     *
     * @param power    the power supply now, or null when the hub has no readable power supply
     * @param internet whether any check host answered, or null when the internet check is off
     */
    public Outcome check(Instant now, PowerSupply.Reading power, Boolean internet, Double freezerTemperature,
                         String freezerUnit) {
        lastCheck = now;
        Outcome out = Outcome.empty();
        if (power != null) power(now, power, freezerTemperature, freezerUnit, out);
        if (power != null) health(now, power, out);
        if (internet != null) internet(now, internet, out);
        else releaseHeld(out);
        return out;
    }

    /** Problems going on now, for the dashboard banner. */
    public List<Alert> active() {
        List<Alert> active = new ArrayList<>();
        if (powerLostAt != null) {
            active.add(new Alert(POWER_KEY, Level.PROBLEM, POWER_DEVICE, powerOutMessage(null), powerLostAt));
        }
        if (internetReported) {
            active.add(new Alert(INTERNET_KEY, Level.PROBLEM, INTERNET_DEVICE,
                    "Internet is down at home since " + time(internetDownSince) + ".", internetDownSince));
        }
        return active;
    }

    private void power(Instant now, PowerSupply.Reading power, Double freezer, String unit, Outcome out) {
        Boolean onMains = power.onMains();
        if (onMains == null) return;
        if (!powerKnown) {   // first reading after start: only the state, no news
            powerKnown = true;
            if (!onMains) powerLostAt = now;
            return;
        }
        if (!onMains && powerLostAt == null) {
            powerLostAt = now;
            warnedBelow = 101;
            out.log().add(new Event(null, HOME, "power", "lost", now));
            alert(out, new Alert(POWER_KEY, Level.PROBLEM, POWER_DEVICE, powerOutMessage(power.percent()), now));
        } else if (onMains && powerLostAt != null) {
            Duration outFor = Duration.between(powerLostAt, now);
            String message = "Power is back at home. It was out " + human(outFor) + ", from " + time(powerLostAt) + ".";
            if (outFor.compareTo(settings.freezerCheckAfter()) > 0) {
                message += freezer == null
                        ? " That's long enough to check the freezer."
                        : " That's long enough to check the freezer: it reads " + number(freezer) + unit + " now.";
            }
            out.log().add(new Event(null, HOME, "power", "restored after " + human(outFor), now));
            alert(out, new Alert(POWER_KEY, Level.CLEARED, POWER_DEVICE, message, now));
            if (warnedBelow <= 100) {
                alert(out, new Alert(BATTERY_KEY, Level.CLEARED, BATTERY_DEVICE, "The hub is charging again.", now));
            }
            powerLostAt = null;
            warnedBelow = 101;
        }
        if (powerLostAt != null && power.percent() != null) {
            for (int level : settings.batteryWarnings()) {   // highest first: 30, then 10
                if (power.percent() <= level && level < warnedBelow) {
                    warnedBelow = level;
                    alert(out, new Alert(BATTERY_KEY, Level.PROBLEM, BATTERY_DEVICE,
                            "The hub's battery is at " + power.percent() + "%. It shuts down when it runs out.", now));
                    break;
                }
            }
        }
    }

    private String powerOutMessage(Integer percent) {
        return "Power is out at home since " + time(powerLostAt) + ". The hub is running on its battery"
                + (percent == null ? "." : ": " + percent + "%.");
    }

    private void health(Instant now, PowerSupply.Reading power, Outcome out) {
        if (power.healthPercent() == null && power.temperatureC() == null) return;
        if (lastHealth != null && now.isBefore(lastHealth.plus(HEALTH_EVERY))) return;
        lastHealth = now;
        List<String> parts = new ArrayList<>();
        if (power.healthPercent() != null) parts.add(power.healthPercent() + "% of its design capacity");
        if (power.temperatureC() != null) parts.add(number(power.temperatureC()) + " °C");
        out.log().add(new Event(null, BATTERY_DEVICE, HEALTH_TYPE, String.join(", ", parts), now));

        boolean worn = power.healthPercent() != null && power.healthPercent() < settings.healthBelow();
        boolean hot = power.temperatureC() != null && power.temperatureC() >= HOT_C;
        if ((worn || hot) && !healthWarned) {
            healthWarned = true;
            String why = worn ? "holds only " + power.healthPercent() + "% of its original charge" : "is running hot ("
                    + number(power.temperatureC()) + " °C)";
            alert(out, new Alert(HEALTH_KEY, Level.NOTICE, BATTERY_DEVICE, "The hub laptop's battery " + why + ".", now));
        }
    }

    private void internet(Instant now, boolean reachable, Outcome out) {
        if (!reachable) {
            if (internetDownSince == null) internetDownSince = now;
            if (!internetReported && !now.isBefore(internetDownSince.plus(settings.internetLostAfter()))) {
                internetReported = true;
                out.log().add(new Event(null, HOME, "internet", "lost", internetDownSince));
                // Shown on the dashboard at home; phones can't be reached without the internet anyway.
                out.alerts().add(new Alert(INTERNET_KEY, Level.PROBLEM, INTERNET_DEVICE,
                        "Internet is down at home since " + time(internetDownSince) + ".", now));
            }
            return;
        }
        if (internetReported) {
            Duration downFor = Duration.between(internetDownSince, now);
            out.log().add(new Event(null, HOME, "internet", "restored after " + human(downFor), now));
            releaseHeld(out);
            out.alerts().add(new Alert(INTERNET_KEY, Level.CLEARED, INTERNET_DEVICE, "Internet is back at home. It was down "
                    + human(downFor) + ", from " + time(internetDownSince) + ".", now));
        } else {
            releaseHeld(out);
        }
        internetDownSince = null;
        internetReported = false;
    }

    /** Raises an alert now, or holds it while the internet is down (phones can't get it then). */
    private void alert(Outcome out, Alert alert) {
        if (internetReported) held.add(alert);
        else out.alerts().add(alert);
    }

    private void releaseHeld(Outcome out) {
        out.alerts().addAll(held);
        held.clear();
    }

    /** "2:05 AM" today, "Sun 2:05 AM" otherwise. */
    private String time(Instant t) {
        var local = t.atZone(zone);
        boolean today = local.toLocalDate().equals(lastCheck.atZone(zone).toLocalDate());
        return local.format(DateTimeFormatter.ofPattern(today ? "h:mm a" : "EEE h:mm a", Locale.US));
    }

    /** 0:00:40 -> "less than a minute", 0:03 -> "3 minutes", 1:05 -> "1 hour 5 minutes" */
    static String human(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        if (minutes < 1) return "less than a minute";
        if (minutes < 60) return plural(minutes, "minute");
        long rest = minutes % 60;
        return plural(minutes / 60, "hour") + (rest == 0 ? "" : " " + plural(rest, "minute"));
    }

    private static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static String number(double n) {
        return Math.abs(n - Math.rint(n)) < 0.05 ? String.valueOf(Math.round(n)) : String.format(Locale.ROOT, "%.1f", n);
    }
}
