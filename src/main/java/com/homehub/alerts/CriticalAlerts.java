package com.homehub.alerts;

import com.homehub.alerts.Alert.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps track of critical alerts that were sent (#101): which still wait for someone to say "I'm on it", when to send
 * them again, and when to also reach the {@code escalateTo} phones. No I/O and no clock of its own; thread-safe.
 */
final class CriticalAlerts {

    /** Key of the test alert from Settings: rings like a real one, but is sent only once. */
    static final String TEST_KEY = "test:critical";
    /** A test alert nobody answered is forgotten after this long. */
    static final Duration TEST_KEPT = Duration.ofMinutes(15);

    enum StepKind { REPEAT, ESCALATE, GAVE_UP }

    /** Something to do now for an unanswered alert; {@code count} is the repeat number (1 for the first repeat). */
    record Step(StepKind kind, Alert alert, int count) { }

    /** Who said "I'm on it", and when. */
    record Ack(String who, Instant at) { }

    private static final class Pending {
        final Alert alert;
        final Instant first;
        Instant last;
        int repeats;
        boolean escalated;
        boolean gaveUp;
        Ack ack;

        Pending(Alert alert, Instant first) {
            this.alert = alert;
            this.first = first;
            this.last = first;
        }
    }

    private final CriticalAlertProperties settings;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    CriticalAlerts(CriticalAlertProperties settings) {
        this.settings = settings;
    }

    /** A problem whose kind ({@code leak} in {@code leak:kitchen leak sensor}) is critical, or the test alert. */
    boolean isCritical(Alert alert) {
        if (alert.level() != Level.PROBLEM) return false;
        if (TEST_KEY.equals(alert.key())) return true;
        int colon = alert.key().indexOf(':');
        return colon > 0 && settings.kinds().contains(alert.key().substring(0, colon));
    }

    /** A critical alert just went out. Sending the same one again (same key) keeps the first time. */
    synchronized void sent(Alert alert, Instant now) {
        pending.putIfAbsent(alert.key(), new Pending(alert, now));
    }

    /**
     * After a restart: a critical alert still going on. Repeats count from {@code now}; with {@code ack} (someone
     * answered before the restart) it isn't repeated at all.
     */
    synchronized void restore(Alert alert, Instant now, Ack ack) {
        Pending p = new Pending(alert, now);
        p.ack = ack;
        pending.putIfAbsent(alert.key(), p);
    }

    /** The problem is over (its all-clear went out): nothing more to repeat or acknowledge. */
    synchronized void ended(String key) {
        pending.remove(key);
    }

    /**
     * Someone said "I'm on it". Returns the alert when this is the first answer, so it can be logged once; empty when
     * the alert is unknown (over, or never critical) or someone already answered.
     */
    synchronized Optional<Alert> acknowledge(String key, String who, Instant now) {
        Pending p = pending.get(key);
        if (p == null || p.ack != null) return Optional.empty();
        p.ack = new Ack(who, now);
        if (TEST_KEY.equals(key)) pending.remove(key);   // nothing stays on the dashboard for a test
        return Optional.of(p.alert);
    }

    /** Who answered this alert, if anyone. */
    synchronized Optional<Ack> ackOf(String key) {
        Pending p = pending.get(key);
        return p == null ? Optional.empty() : Optional.ofNullable(p.ack);
    }

    /** Whether this alert waits for an answer (sent, critical, not over, nobody on it yet). */
    synchronized boolean waiting(String key) {
        Pending p = pending.get(key);
        return p != null && p.ack == null;
    }

    /** What to do now: repeats of unanswered alerts, the escalation, and giving up after {@code maxRepeats}. */
    synchronized List<Step> due(Instant now) {
        List<Step> steps = new ArrayList<>();
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (TEST_KEY.equals(p.alert.key())) {   // a test is sent once, never repeated
                if (!now.isBefore(p.first.plus(TEST_KEPT))) it.remove();
                continue;
            }
            if (p.ack != null || p.gaveUp) continue;
            if (!p.escalated && !settings.escalateTo().isEmpty() && !now.isBefore(p.first.plus(settings.escalateAfter()))) {
                p.escalated = true;
                steps.add(new Step(StepKind.ESCALATE, p.alert, p.repeats));
            }
            if (!now.isBefore(p.last.plus(settings.repeatEvery()))) {
                if (p.repeats >= settings.maxRepeats()) {
                    p.gaveUp = true;
                    steps.add(new Step(StepKind.GAVE_UP, p.alert, p.repeats));
                } else {
                    p.repeats++;
                    p.last = now;
                    steps.add(new Step(StepKind.REPEAT, p.alert, p.repeats));
                }
            }
        }
        return steps;
    }
}
