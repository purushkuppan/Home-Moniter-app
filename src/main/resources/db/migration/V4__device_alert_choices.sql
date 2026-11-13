-- V4 (#100): the household's choices for an appliance's alerts on the Devices list, as a small JSON object, e.g.
-- {"open-for:Fridge":"2","too-warm:Freezer":"off","finished":"on","offline":"60"}. A choice that is missing means
-- the configured default (homehub.alerts.*); "off" switches that alert off.
ALTER TABLE devices ADD COLUMN alert_choices TEXT;
