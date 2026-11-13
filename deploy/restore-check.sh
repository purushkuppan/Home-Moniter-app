#!/usr/bin/env bash
# Checks one nightly backup archive (#123), without changing anything on this machine. Run it on the hub or on any
# Linux machine that has sqlite3:
#   sudo bash restore-check.sh /var/backups/homehub/kudil-backup-2026-11-12.tar.gz
# (sudo only because the archives on the hub are root-only; a readable copy needs no sudo.)
#
# It unpacks the archive into a private temporary folder, which is removed when it ends, and:
#  - checks Kudil's homehub.db and Home Assistant's home-assistant_v2.db with SQLite's integrity check
#  - prints the number of events, the newest event time, and the latest reading of every device
#    (the same list Kudil shows as the current state, /api/events/latest)
#  - checks that Home Assistant's settings (.storage) and, when the hub has Z-Wave, Z-Wave JS UI's settings.json and
#    nodes.json are there. Nothing from those files is printed: they hold secrets.yaml and the Z-Wave security keys.
# Exits non-zero, saying what is wrong, if anything is missing or damaged.
# The temporary folder goes in $TMPDIR (default /tmp); it needs about twice the archive's unpacked size.
set -euo pipefail
umask 077

fail() { echo "Restore check failed: $*" >&2; exit 1; }

[[ $# -eq 1 && -n $1 ]] || fail "give one archive: bash $0 /var/backups/homehub/kudil-backup-YYYY-MM-DD.tar.gz"
archive=$1
[[ -f $archive ]] || fail "there is no archive at $archive"
[[ -r $archive ]] || fail "can't read $archive (the archives on the hub are root-only: run it with sudo)"
command -v sqlite3 >/dev/null || fail "sqlite3 is missing (sudo apt-get install sqlite3)"

work=$(mktemp -d "${TMPDIR:-/tmp}/kudil-restore-check.XXXXXX")
trap 'rm -rf "$work"' EXIT

sql() { sqlite3 -batch -init /dev/null "$@"; }

# Fails unless database $1 (a path inside $work), described as $2, is there and passes SQLite's integrity check.
check_db() {
    local result
    [[ -f $1 && ! -L $1 ]] || fail "$2 (${1#"$work/"}) is missing from the archive"
    result=$(sql "$1" "PRAGMA integrity_check;" 2>&1) || fail "$2 is damaged: $result"
    [[ $result == ok ]] || fail "$2 is damaged: $result"
    echo "ok   $2 passes the integrity check"
}

echo "Checking $archive ($(du -h "$archive" | cut -f1))"
gzip -t "$archive" 2>/dev/null || fail "$archive is damaged or not a .tar.gz archive"
# --no-same-owner: as root, tar would otherwise recreate the archive's owners in the temporary folder.
tar -C "$work" --no-same-owner -xzf "$archive" || fail "can't unpack $archive"

# Kudil
db="$work/kudil/homehub.db"
check_db "$db" "Kudil's database"
[[ $(sql "$db" "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'events';") == 1 ]] \
    || fail "Kudil's database has no events table"
events=$(sql "$db" "SELECT count(*) FROM events;")
newest=$(sql "$db" "SELECT coalesce(max(time), 'none') FROM events;")
echo "     events: $events, newest: $newest"
if [[ $(sql "$db" "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'devices';") == 1 ]]; then
    echo "     devices: $(sql "$db" "SELECT count(*) FROM devices WHERE status = 'confirmed';") confirmed," \
        "$(sql "$db" "SELECT count(*) FROM devices;") known"
fi
echo "     latest reading of each device (device | reading | value | time):"
sql -separator ' | ' "$db" "SELECT e.device, e.type, coalesce(e.value, '(none)'), e.time FROM events e
    JOIN (SELECT max(id) AS id FROM events GROUP BY device, type) newest ON newest.id = e.id
    ORDER BY e.device, e.type;" | sed 's/^/       /'

# Home Assistant
[[ -d $work/homeassistant/.storage ]] || fail "Home Assistant's settings (homeassistant/.storage) are missing from the archive"
echo "ok   Home Assistant settings (.storage) are there"
check_db "$work/homeassistant/home-assistant_v2.db" "Home Assistant's history database"

# Z-Wave JS UI (only on a hub with a Z-Wave stick). Only whether the files are there is checked: settings.json holds
# the Z-Wave security keys, so nothing from it is read or printed.
if [[ -d $work/zwave-js-ui ]]; then
    for file in settings.json nodes.json; do
        [[ -s $work/zwave-js-ui/$file ]] || fail "Z-Wave JS UI's $file (zwave-js-ui/$file) is missing from the archive"
    done
    echo "ok   Z-Wave JS UI settings (with the security keys) and node list are there"
else
    echo "--   no Z-Wave JS UI folder (fine on a hub without a Z-Wave stick)"
fi

echo
echo "The archive is good: $events events, newest $newest."
