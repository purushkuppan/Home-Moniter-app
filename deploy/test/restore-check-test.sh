#!/usr/bin/env bash
# Tests deploy/restore-check.sh against small archives built here, laid out like backup.sh's. Needs sqlite3; runs as
# any user, no root.
#   bash deploy/test/restore-check-test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../restore-check.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

failures=0
check() {   # check "description" command...
    if "${@:2}"; then echo "ok   - $1"; else echo "FAIL - $1"; failures=$((failures + 1)); fi
}
fails() { ! "$@"; }
has() { grep -q -F -- "$1" "$T/log"; }

# Runs restore-check.sh with its temporary folder in $T/tmp; output in $T/log (and all of it in $T/all), exit code
# in $status.
try() {
    status=0
    env TMPDIR="$T/tmp" bash "$SCRIPT" "$@" > "$T/log" 2>&1 || status=$?
    cat "$T/log" >> "$T/all"
}
# Packs folder $T/$1 (with the parts that exist in it) into archive $T/$1.tar.gz.
pack() {
    local parts=() part
    for part in kudil homeassistant zwave-js-ui; do [[ -e $T/$1/$part ]] && parts+=("$part"); done
    tar -C "$T/$1" -czf "$T/$1.tar.gz" "${parts[@]}"
}
# Copies the good archive's folder to $T/$1, to change something in it before packing.
variant() { cp -a "$T/good" "$T/$1"; }
# A database whose pages are damaged at $1: only the integrity check notices.
damaged_db() {
    rm -f "$1"
    sqlite3 "$1" "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT, type TEXT, value TEXT, time TEXT);
        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 2000)
        INSERT INTO events(device, type, value, time) SELECT 'Back door', 'door', printf('reading %d with some text', i),
            '2026-11-12T08:00:00Z' FROM n;
        CREATE INDEX events_value ON events(value);"
    head -c 4096 < <(yes 'damage') | dd of="$1" bs=4096 seek=3 conv=notrunc status=none
}

mkdir -p "$T/tmp" "$T/good/kudil" "$T/good/homeassistant/.storage" "$T/good/zwave-js-ui"
: > "$T/all"
# Kudil's database as the app makes it (V1, V2), in WAL mode like the hub's.
sqlite3 "$T/good/kudil/homehub.db" >/dev/null <<'SQL'
PRAGMA journal_mode=WAL;
CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, type TEXT NOT NULL, value TEXT,
    time TEXT NOT NULL);
CREATE INDEX idx_events_device_type ON events (device, type, id);
CREATE TABLE devices (id INTEGER PRIMARY KEY AUTOINCREMENT, ha_device_id TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
    status TEXT NOT NULL);
INSERT INTO events (device, type, value, time) VALUES
    ('Back door', 'door', 'open', '2026-11-12T08:00:00Z'),
    ('Back door', 'door', 'closed', '2026-11-12T08:01:00Z'),
    ('Refrigerator/Fridge', 'temperature', '38', '2026-11-12T09:00:00Z'),
    ('Refrigerator/Fridge', 'temperature', '37', '2026-11-12T21:30:00Z'),
    ('Kitchen leak sensor', 'moisture', 'dry', '2026-11-11T10:00:00Z');
INSERT INTO devices (ha_device_id, name, status) VALUES ('a1', 'Back door', 'confirmed'), ('b2', 'Lamp', 'new');
SQL
sqlite3 "$T/good/homeassistant/home-assistant_v2.db" "CREATE TABLE states(id INTEGER); INSERT INTO states VALUES (1);"
echo '{"data":{"elevation":190}}' > "$T/good/homeassistant/.storage/core.config"
echo 'api_password: TEST-SECRET' > "$T/good/homeassistant/secrets.yaml"
echo '{"zwave":{"securityKeys":{"S2_Authenticated":"TEST-KEY"}}}' > "$T/good/zwave-js-ui/settings.json"
echo '[{"id":2,"name":"Indoor Smart Plug"}]' > "$T/good/zwave-js-ui/nodes.json"
pack good
cp "$T/good.tar.gz" "$T/good-copy.tar.gz"

# 1. A good archive.
try "$T/good.tar.gz"
check "a good archive passes" test "$status" = 0
check "...prints the number of events" has "events: 5, newest: 2026-11-12T21:30:00Z"
check "...and the devices" has "devices: 1 confirmed, 2 known"
check "...each device's latest reading" has "Back door | door | closed | 2026-11-12T08:01:00Z"
check "...for every device" has "Refrigerator/Fridge | temperature | 37 | 2026-11-12T21:30:00Z"
check "...including one that has been quiet" has "Kitchen leak sensor | moisture | dry | 2026-11-11T10:00:00Z"
check "...and not older readings" fails has "| open |"
check "...checks both databases" test "$(grep -c "passes the integrity check" "$T/log")" = 2
check "...finds the Z-Wave settings" has "Z-Wave JS UI settings (with the security keys) and node list are there"
check "...and says it is good" has "The archive is good: 5 events"
check "the archive itself is unchanged" cmp -s "$T/good.tar.gz" "$T/good-copy.tar.gz"

# 2. A hub without a Z-Wave stick.
variant no-zwave && rm -r "$T/no-zwave/zwave-js-ui" && pack no-zwave
try "$T/no-zwave.tar.gz"
check "an archive without Z-Wave passes" test "$status" = 0
check "...and says so" has "no Z-Wave JS UI folder"

# 3. Missing parts fail, and say what is missing.
variant no-kudil && rm "$T/no-kudil/kudil/homehub.db" && pack no-kudil
try "$T/no-kudil.tar.gz"
check "a missing homehub.db fails" test "$status" != 0
check "...and says so" has "Kudil's database (kudil/homehub.db) is missing"

variant no-storage && rm -r "$T/no-storage/homeassistant/.storage" && pack no-storage
try "$T/no-storage.tar.gz"
check "missing Home Assistant settings fail" test "$status" != 0
check "...and say so" has "homeassistant/.storage) are missing"

variant no-ha-db && rm "$T/no-ha-db/homeassistant/home-assistant_v2.db" && pack no-ha-db
try "$T/no-ha-db.tar.gz"
check "a missing Home Assistant database fails" test "$status" != 0
check "...and says so" has "Home Assistant's history database (homeassistant/home-assistant_v2.db) is missing"

variant no-nodes && rm "$T/no-nodes/zwave-js-ui/nodes.json" && pack no-nodes
try "$T/no-nodes.tar.gz"
check "a Z-Wave folder without nodes.json fails" test "$status" != 0
check "...and says so" has "zwave-js-ui/nodes.json) is missing"

variant no-settings && rm "$T/no-settings/zwave-js-ui/settings.json" && pack no-settings
try "$T/no-settings.tar.gz"
check "a Z-Wave folder without settings.json fails" test "$status" != 0
check "...and says so" has "zwave-js-ui/settings.json) is missing"

variant no-events && sqlite3 "$T/no-events/kudil/homehub.db" "DROP TABLE events;" && pack no-events
try "$T/no-events.tar.gz"
check "a database without an events table fails" test "$status" != 0
check "...and says so" has "has no events table"

# 4. Damaged databases and archives fail.
variant damaged && damaged_db "$T/damaged/kudil/homehub.db" && pack damaged
try "$T/damaged.tar.gz"
check "a homehub.db with damaged pages fails" test "$status" != 0
check "...and says so" has "Kudil's database is damaged"

variant not-db && head -c 8192 < <(yes 'not a database') > "$T/not-db/kudil/homehub.db" && pack not-db
try "$T/not-db.tar.gz"
check "a homehub.db that isn't a database fails" test "$status" != 0
check "...and says so" has "Kudil's database is damaged"

variant ha-damaged && damaged_db "$T/ha-damaged/homeassistant/home-assistant_v2.db" && pack ha-damaged
try "$T/ha-damaged.tar.gz"
check "a damaged Home Assistant database fails" test "$status" != 0
check "...and says so" has "Home Assistant's history database is damaged"

head -c "$(($(stat -c %s "$T/good.tar.gz") / 2))" "$T/good.tar.gz" > "$T/cut.tar.gz"
try "$T/cut.tar.gz"
check "a cut-off archive fails" test "$status" != 0
check "...and says so" has "is damaged or not a .tar.gz archive"

# 5. Wrong use.
try
check "no archive given fails" test "$status" != 0
check "...and says how to use it" has "give one archive"
try "$T/nothing-here.tar.gz"
check "an archive that isn't there fails" test "$status" != 0
check "...and says so" has "there is no archive at"
if [[ $(id -u) != 0 ]]; then   # root reads any file
    cp "$T/good.tar.gz" "$T/root-only.tar.gz" && chmod 000 "$T/root-only.tar.gz"
    try "$T/root-only.tar.gz"
    check "an archive it can't read fails" test "$status" != 0
    check "...and says to use sudo" has "run it with sudo"
fi

# 6. Secrets never appear, and nothing is left behind.
check "the Z-Wave security key is never printed" fails grep -q TEST-KEY "$T/all"
check "Home Assistant's secrets are never printed" fails grep -q TEST-SECRET "$T/all"
check "no temporary folders left behind" test -z "$(find "$T/tmp" -mindepth 1)"

echo
if ((failures)); then echo "$failures restore check test(s) failed"; cat "$T/all"; exit 1; fi
echo "All restore check tests passed"
