package com.homehub.homeassistant;

import java.util.Optional;

/**
 * What Kudil knows about Home Assistant's entities from its device registry (#99): which to record, which to ignore,
 * and the device name to record them under. The Home Assistant connector asks it for every entity and hands it each
 * fresh {@link RegistrySnapshot}.
 */
public interface EntityDirectory {

    /** An entity of a confirmed device that Kudil records (in addition to the configured include patterns). */
    boolean records(String entityId);

    /** An entity of a hidden device: never recorded, even if an include pattern matches it. */
    boolean hidden(String entityId);

    /** Kudil's name for the device this entity belongs to, if Kudil knows the device. */
    Optional<String> deviceName(String entityId);

    /**
     * Whether this entity is its device's only reading of its kind (same domain and device class), so it is recorded
     * under the device name alone ("Kitchen leak sensor") rather than device and part ("Refrigerator/Fridge", one of
     * two doors).
     */
    boolean onlyReadingOfItsKind(String entityId);

    /** Home Assistant's registries were read (at connect, and after each change). */
    void sync(RegistrySnapshot snapshot);

    /** No device registry: only the include patterns count, and Home Assistant's device names are used. */
    EntityDirectory NONE = new EntityDirectory() {
        @Override public boolean records(String entityId) { return false; }
        @Override public boolean hidden(String entityId) { return false; }
        @Override public Optional<String> deviceName(String entityId) { return Optional.empty(); }
        @Override public boolean onlyReadingOfItsKind(String entityId) { return false; }
        @Override public void sync(RegistrySnapshot snapshot) { }
    };
}
