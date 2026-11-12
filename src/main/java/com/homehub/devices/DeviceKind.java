package com.homehub.devices;

/** What a device is, as the household sees it (#99). Decides what Kudil records from it. */
public enum DeviceKind {
    LEAK("Leak sensor"),
    SMOKE("Smoke alarm"),
    CO("CO alarm"),
    LOCK("Lock"),
    THERMOSTAT("Thermostat"),
    CAMERA("Camera"),
    APPLIANCE("Appliance"),
    GARAGE("Garage door"),
    OPENING("Door or window sensor"),
    PLUG("Smart plug"),
    SWITCH("Switch"),
    PHONE("Phone"),
    OTHER("Other");

    /** "Leak sensor": shown on the Devices list. */
    public final String label;

    DeviceKind(String label) {
        this.label = label;
    }
}
