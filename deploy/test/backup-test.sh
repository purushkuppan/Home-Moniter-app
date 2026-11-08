#!/usr/bin/env bash
# Tests deploy/backup.sh against throwaway folders. Needs sqlite3 and python3; runs as any user, no root.
#   bash deploy/test/backup-test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BACKUP="$HERE/../backup.sh"
T=$(mktemp -d)
writer=
cleanup() { [[ -n $writer ]] && kill "$writer" 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT

failures=0
check() {   # check "description" command...
    if "${@:2}"; then echo "ok   - $1"; else echo "FAIL - $1"; failures=$((failures + 1)); fi
}
fails() { ! "$@"; }
count() { sqlite3 -batch -init /dev/null "$1" "SELECT count(*) FROM events;"; }

mkdir -p "$T/data" "$T/ha/.storage" "$T/ha/.cache" "$T/out" "$T/usb"
echo '{"data":{"elevation":190}}' > "$T/ha/.storage/core.config"
echo 'default_config:' > "$T/ha/configuration.yaml"
echo 'log line' > "$T/ha/home-assistant.log"
echo 'cache' > "$T/ha/.cache/blob"
sqlite3 "$T/ha/home-assistant_v2.db" "PRAGMA journal_mode=WAL; CREATE TABLE states(id INTEGER); INSERT INTO states VALUES (1);" >/dev/null

# Kudil's database with 500 rows that are still only in the -wal file, held there by an open connection,
# like the running app holds them.
python3 - "$T/data/homehub.db" "$T/ready" <<'PY' &
import sqlite3, sys, time
c = sqlite3.connect(sys.argv[1], isolation_level=None)
c.execute("PRAGMA journal_mode=WAL")
c.execute("PRAGMA wal_autocheckpoint=0")
c.execute("CREATE TABLE events(id INTEGER PRIMARY KEY, value TEXT)")
for i in range(500):
    c.execute("INSERT INTO events(value) VALUES (?)", (str(i),))
open(sys.argv[2], "w").close()
time.sleep(120)
PY
writer=$!
for _ in $(seq 50); do [[ -f $T/ready ]] && break; sleep 0.1; done
[[ -f $T/ready ]] || { echo "FAIL - test database not ready"; exit 1; }

run() {   # runs backup.sh against the test folders; extra NAME=value settings as arguments
    env HOMEHUB_DATA="$T/data" DB_USER="$(id -un)" HA_CONFIG="$T/ha" BACKUP_DIR="$T/out" \
        STATUS_FILE="$T/status/last-success" KEEP=3 "$@" bash "$BACKUP" > "$T/log" 2>&1
}

# 1. A backup of live databases.
today="kudil-backup-$(date +%F).tar.gz"
check "backup succeeds while the database is in use" run
check "archive is named after today" test -f "$T/out/$today"
mkdir "$T/restore" && tar -C "$T/restore" -xzf "$T/out/$today"
check "rows still in the -wal file are in the backup" test "$(count "$T/restore/kudil/homehub.db")" = 500
check "...which a plain copy of the file would miss" \
    test "$(sqlite3 -batch -init /dev/null "file:$T/data/homehub.db?immutable=1" 'SELECT count(*) FROM events;' 2>/dev/null || echo 0)" != 500
check "Home Assistant settings are in the backup" grep -q elevation "$T/restore/homeassistant/.storage/core.config"
check "Home Assistant history database is in the backup" test -f "$T/restore/homeassistant/home-assistant_v2.db"
check "logs and cache are left out" test ! -e "$T/restore/homeassistant/home-assistant.log" -a ! -e "$T/restore/homeassistant/.cache"
check "no SQLite side files in the archive" test -z "$(find "$T/restore" -name '*-wal' -o -name '*-shm')"
check "status file records the success" test -s "$T/status/last-success"
check "status file is readable by Kudil" test "$(stat -c %a "$T/status/last-success")" = 644
check "archive is private" test "$(stat -c %a "$T/out/$today")" = 600
check "no work folders left behind" test -z "$(find "$T/out" -name '.work.*')"

# 2. Only the newest KEEP archives stay, here and in COPY_TO.
for d in 01 02 03 04 05; do touch "$T/out/kudil-backup-2020-01-$d.tar.gz" "$T/usb/kudil-backup-2020-01-$d.tar.gz"; done
touch "$T/out/notes.txt"
check "backup with a copy to a USB folder succeeds" run COPY_TO="$T/usb"
check "keeps the 3 newest archives" \
    test "$(ls "$T/out"/kudil-backup-*.tar.gz | xargs -n1 basename | tr '\n' ' ')" = "kudil-backup-2020-01-04.tar.gz kudil-backup-2020-01-05.tar.gz $today "
check "keeps the 3 newest archives on the USB folder too" \
    test "$(ls "$T/usb"/kudil-backup-*.tar.gz | xargs -n1 basename | tr '\n' ' ')" = "kudil-backup-2020-01-04.tar.gz kudil-backup-2020-01-05.tar.gz $today "
check "the USB copy is complete" cmp -s "$T/out/$today" "$T/usb/$today"
check "other files are left alone" test -f "$T/out/notes.txt"

# 3. Failures: nothing is replaced or removed, and the status file isn't updated.
before=$(cat "$T/status/last-success")
kept=$(ls "$T/out")
check "a missing USB disk fails the backup" fails run COPY_TO="$T/not-mounted"
check "...and says why" grep -q "not mounted" "$T/log"
check "a bad KEEP value fails the backup" fails run KEEP=0
kill "$writer" && wait "$writer" 2>/dev/null || true
writer=
head -c 8192 < <(yes 'not a database') > "$T/data/homehub.db"
rm -f "$T/data/homehub.db-wal" "$T/data/homehub.db-shm"
check "a file that isn't a database fails the backup" fails run
check "...and says why" grep -q "can't copy" "$T/log"
# A real database whose pages are damaged: only the integrity check notices.
rm -f "$T/data/homehub.db"
sqlite3 "$T/data/homehub.db" "CREATE TABLE events(id INTEGER PRIMARY KEY, value TEXT);
    WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 2000)
    INSERT INTO events(value) SELECT printf('reading %d with some text', i) FROM n;
    CREATE INDEX events_value ON events(value);"
head -c 4096 < <(yes 'damage') | dd of="$T/data/homehub.db" bs=4096 seek=3 conv=notrunc status=none
check "a database with damaged pages fails the backup" fails run
check "...and says why" grep -q "damaged" "$T/log"
check "...the existing archives stay" test "$(ls "$T/out")" = "$kept"
check "...the status file keeps the last success" test "$(cat "$T/status/last-success")" = "$before"

echo
if ((failures)); then echo "$failures backup test(s) failed"; cat "$T/log"; exit 1; fi
echo "All backup tests passed"
