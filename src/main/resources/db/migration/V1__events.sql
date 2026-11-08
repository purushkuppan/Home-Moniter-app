-- V1: the events table exactly as HomeHub created it at startup before Flyway (in EventRepository).
-- Databases made by that code are baselined at version 1 (spring.flyway.baseline-on-migrate), so this
-- script only runs on a new database. IF NOT EXISTS keeps it harmless on an old one all the same.
-- INTEGER PRIMARY KEY AUTOINCREMENT is SQLite's; PostgreSQL gets its own V1 when we move (see ADR 2).

CREATE TABLE IF NOT EXISTS events (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    device TEXT NOT NULL,
    type   TEXT NOT NULL,
    value  TEXT,
    time   TEXT NOT NULL
);

-- Makes "latest value of this device reading" a quick lookup for EventRepository.saveIfChanged.
CREATE INDEX IF NOT EXISTS idx_events_device_type ON events (device, type, id);
