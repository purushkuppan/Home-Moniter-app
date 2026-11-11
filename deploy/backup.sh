#!/usr/bin/env bash
# Nightly backup of Kudil's database, Home Assistant's configuration and Z-Wave JS UI's network data and keys,
# as one archive:
#   /var/backups/homehub/kudil-backup-YYYY-MM-DD.tar.gz
# Run as root by homehub-backup.timer (installed by setup-hub.sh); by hand: sudo systemctl start homehub-backup
#
# Safe while both apps run: databases are copied with SQLite's online backup, never with cp, so writes still
# held in the -wal file are included. Each copy must pass an integrity check before the archive is kept, and
# old archives are removed only after a new one is complete.
#
# Settings (optional) in /etc/homehub/backup.env:
#   COPY_TO=/media/backup   also keep the archives here, for example on a USB disk
#   KEEP=14                 archives to keep in each place
set -euo pipefail
umask 077

HOMEHUB_DATA=${HOMEHUB_DATA:-/opt/homehub/data}
DB_USER=${DB_USER:-homehub}        # owns homehub.db; reads it, so SQLite's side files stay owned by it
HA_CONFIG=${HA_CONFIG:-/opt/homeassistant}
ZWAVE_DIR=${ZWAVE_DIR:-/opt/zwave-js-ui}   # optional: only on a hub with a Z-Wave stick
BACKUP_DIR=${BACKUP_DIR:-/var/backups/homehub}
STATUS_FILE=${STATUS_FILE:-/var/lib/homehub-backup/last-success}
COPY_TO=${COPY_TO:-}
KEEP=${KEEP:-14}

fail() { echo "Backup failed: $*" >&2; exit 1; }

[[ $KEEP =~ ^[1-9][0-9]{0,3}$ ]] || fail "KEEP must be a number from 1 to 9999, not '$KEEP'"
for dir in "$HOMEHUB_DATA" "$HA_CONFIG" "$ZWAVE_DIR" "$BACKUP_DIR" ${COPY_TO:+"$COPY_TO"}; do
    # Paths end up inside SQLite dot-commands, so keep them plain.
    [[ $dir =~ ^/[A-Za-z0-9._/-]+$ ]] || fail "unexpected characters in path '$dir'"
done
[[ -f $HOMEHUB_DATA/homehub.db ]] || fail "no database at $HOMEHUB_DATA/homehub.db"
[[ -d $HA_CONFIG ]] || fail "no Home Assistant config at $HA_CONFIG"
[[ -z $COPY_TO || -d $COPY_TO ]] || fail "COPY_TO folder $COPY_TO doesn't exist (USB disk not mounted?)"

mkdir -p "$BACKUP_DIR"
work=$(mktemp -d "$BACKUP_DIR/.work.XXXXXX")
trap 'rm -rf "$work"' EXIT

sql() { sqlite3 -batch -init /dev/null "$@"; }

# Fails unless database copy $1 passes SQLite's integrity check.
check_db() {
    local result
    result=$(sql "$1" "PRAGMA integrity_check;" 2>&1) || fail "copy $1 is damaged: $result"
    [[ $result == ok ]] || fail "copy $1 is damaged: $result"
}

# Kudil's database, read as its owner so a WAL or shared-memory file SQLite creates isn't owned by root.
mkdir "$work/kudil"
if [[ $(id -un) == "$DB_USER" ]]; then
    sql "$HOMEHUB_DATA/homehub.db" ".backup '$work/kudil/homehub.db'" || fail "can't copy $HOMEHUB_DATA/homehub.db"
else
    as_owner=(setpriv --reuid="$DB_USER" --regid="$DB_USER" --init-groups)
    owned=$("${as_owner[@]}" mktemp -d)
    "${as_owner[@]}" sqlite3 -batch -init /dev/null "$HOMEHUB_DATA/homehub.db" ".backup '$owned/homehub.db'" \
        || { rm -rf "$owned"; fail "can't copy $HOMEHUB_DATA/homehub.db"; }
    mv "$owned/homehub.db" "$work/kudil/homehub.db"
    rm -rf "$owned"
fi
check_db "$work/kudil/homehub.db"

# Home Assistant: its settings (.storage, YAML files) plus a safe copy of its history database.
mkdir "$work/homeassistant"
tar -C "$HA_CONFIG" -cf - \
    --exclude='./home-assistant_v2.db*' --exclude='./home-assistant.log*' --exclude='./.cache' --exclude='./tts' . \
    | tar -C "$work/homeassistant" -xf - || fail "can't copy $HA_CONFIG"
[[ -d $work/homeassistant/.storage ]] || fail "no .storage folder in $HA_CONFIG"
if [[ -f $HA_CONFIG/home-assistant_v2.db ]]; then
    sql "$HA_CONFIG/home-assistant_v2.db" ".backup '$work/homeassistant/home-assistant_v2.db'" \
        || fail "can't copy $HA_CONFIG/home-assistant_v2.db"
    check_db "$work/homeassistant/home-assistant_v2.db"
fi

# Z-Wave JS UI (#130): its settings hold the Z-Wave security keys, and with nodes.json and the *.jsonl caches they
# let a new install take over the stick's network without re-pairing. Logs, lock folders and web sessions are left out.
parts=(kudil homeassistant)
if [[ -d $ZWAVE_DIR ]]; then
    mkdir "$work/zwave-js-ui"
    tar -C "$ZWAVE_DIR" -cf - --exclude='./logs' --exclude='./sessions' --exclude='*.lock' . \
        | tar -C "$work/zwave-js-ui" -xf - || fail "can't copy $ZWAVE_DIR"
    [[ -f $work/zwave-js-ui/settings.json ]] || fail "no settings.json in $ZWAVE_DIR"
    parts+=(zwave-js-ui)
fi

name="kudil-backup-$(date +%F).tar.gz"
tar -C "$work" -czf "$work/$name" "${parts[@]}" || fail "can't write the archive"
gzip -t "$work/$name" || fail "the archive is damaged"
mv -f "$work/$name" "$BACKUP_DIR/$name"

# Keeps the newest $KEEP archives in folder $1. Counting archives (not days) means the last good backups survive
# however long the hub was off.
prune() {
    local old
    mapfile -t old < <(find "$1" -maxdepth 1 -name 'kudil-backup-*.tar.gz' -printf '%f\n' | sort -r | tail -n "+$((KEEP + 1))")
    for f in "${old[@]}"; do rm -f -- "${1:?}/$f"; done
}
prune "$BACKUP_DIR"

if [[ -n $COPY_TO ]]; then
    cp "$BACKUP_DIR/$name" "$COPY_TO/.$name.part" || fail "can't copy the archive to $COPY_TO"
    mv -f "$COPY_TO/.$name.part" "$COPY_TO/$name"
    prune "$COPY_TO"
fi

install -d -m 0755 "$(dirname "$STATUS_FILE")"   # Kudil reads the status file
date --iso-8601=seconds > "$STATUS_FILE.tmp"
chmod 0644 "$STATUS_FILE.tmp"
mv -f "$STATUS_FILE.tmp" "$STATUS_FILE"
echo "Backup written: $BACKUP_DIR/$name ($(du -h "$BACKUP_DIR/$name" | cut -f1))${COPY_TO:+, copied to $COPY_TO}"
