-- V2 (#99): the devices Kudil knows, read from Home Assistant's device, area and entity registries.
-- A device is new until someone in the household confirms (names) it or hides it; only confirmed devices are
-- recorded and alert. Entity ids stay here, internal: the dashboard never shows them.

CREATE TABLE IF NOT EXISTS devices (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    ha_device_id      TEXT NOT NULL UNIQUE,   -- Home Assistant's device id
    name              TEXT NOT NULL,          -- Kudil's name, chosen when confirmed ("Kitchen leak sensor")
    ha_name           TEXT NOT NULL,          -- Home Assistant's name, kept up to date
    room              TEXT,                   -- Home Assistant area
    manufacturer      TEXT,
    model             TEXT,
    kind              TEXT NOT NULL,          -- DeviceKind
    status            TEXT NOT NULL,          -- new, confirmed, hidden
    entities          TEXT NOT NULL,          -- all its entity ids, comma-separated (hiding hides them all)
    readings          TEXT NOT NULL,          -- the entity ids Kudil records once confirmed
    in_home_assistant INTEGER NOT NULL DEFAULT 1,
    first_seen        TEXT NOT NULL,
    confirmed_at      TEXT,
    -- For maintenance reminders (#109) and battery alerts (#15); filled in later.
    install_date      TEXT,
    battery_type      TEXT,
    warranty_end      TEXT,
    location          TEXT
);
