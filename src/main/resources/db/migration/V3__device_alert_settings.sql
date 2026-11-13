-- V3 (#100): the household's change to a device's ready-made alerts, on the Devices list.
-- open_for_minutes: alert when this door, window or garage door is open longer than this; 0 = never; NULL = the
-- default (homehub.alerts.defaults.open-for).
ALTER TABLE devices ADD COLUMN open_for_minutes INTEGER;
