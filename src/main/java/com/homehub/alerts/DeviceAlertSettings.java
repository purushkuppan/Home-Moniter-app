package com.homehub.alerts;

import java.time.Duration;
import java.util.Optional;

/**
 * What the household changed for a device's alerts, on the Devices list (#100). Each one, when present, wins over
 * the configured default ({@code homehub.alerts.*}); empty = the default.
 */
public interface DeviceAlertSettings {

    /**
     * How long this door may stay open ("Back door", or one door of an appliance, "Refrigerator/Fridge");
     * {@link Duration#ZERO} = no open-door alert.
     */
    Optional<Duration> openFor(String device);

    /** The highest normal temperature of this reading ("Refrigerator/Fridge"), in its own unit; NaN = no alert. */
    default Optional<Double> tooWarm(String device) {
        return Optional.empty();
    }

    /** Whether to report the end of this washer's or dryer's cycles ("Washer"). */
    default Optional<Boolean> finished(String device) {
        return Optional.empty();
    }

    /** How long all its readings may be unavailable before this device is offline; {@link Duration#ZERO} = never. */
    default Optional<Duration> offlineAfter(String device) {
        return Optional.empty();
    }

    DeviceAlertSettings NONE = device -> Optional.empty();
}
