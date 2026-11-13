package com.homehub.alerts;

import java.time.Duration;
import java.util.Optional;

/**
 * What the household changed for one device's ready-made alerts (#100), on the Devices list. Looked up by the
 * dashboard name the readings are recorded under ("Back door").
 */
public interface DeviceAlertSettings {

    /** Its own "open longer than" limit; {@link Duration#ZERO} = no open-door alert; empty = the default. */
    Optional<Duration> openFor(String device);

    DeviceAlertSettings NONE = device -> Optional.empty();
}
