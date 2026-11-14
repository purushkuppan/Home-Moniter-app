package com.homehub.alerts;

/**
 * A way to get alerts to the family's phones (ADR 12). Kudil decides when to alert; a notifier only delivers, so the
 * delivery path can change without touching the rules. Today: {@link NtfyNotifier} for Android phones (self-hosted
 * ntfy) and {@link AlertNotifier} for iPhones (Home Assistant's Companion app, the only path to iOS critical alerts).
 * <p>
 * Every alert carries what a notifier needs: severity ({@link Alert#level()} plus whether it is critical), a tag (the
 * alert key, so an all clear can replace the problem), a title ("Kudil"), the body (message and next step, #161) and
 * a link ({@link Alert#link}: the dashboard, opened on that alert, #184).
 */
public interface Notifier {

    /** Sends one alert; returns how many phones (or topics) accepted it. Never throws. */
    int send(Alert alert, boolean critical);

    /** Removes the alert's notification where the notifier can, e.g. once someone said "I'm on it". Never throws. */
    default void clear(String key) {
    }

    /** Whether this notifier has somewhere to send to. */
    boolean configured();
}
