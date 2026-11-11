#!/usr/bin/env bash
# Updates Home Assistant to a version you choose, once a month (#33). Run on the hub:
#   sudo bash update-home-assistant.sh 2026.12.1
# Read the release notes first (see "Updates" in deploy/README.md).
#
# 1. Makes a fresh backup (the nightly backup, now). If it fails, nothing changes.
# 2. Downloads the new version. If that fails, nothing changes.
# 3. Starts the new version with the same settings, keeping the old container aside.
# 4. Waits for Home Assistant to answer. If it doesn't, it goes back to the old container.
# The container then runs a fixed version tag instead of "stable", so it changes only when you run this again.
set -euo pipefail

# HA_UPDATE_TEST: lets deploy/test/ha-update-test.sh run this with stand-in commands.
[[ $EUID -eq 0 || -n ${HA_UPDATE_TEST:-} ]] || { echo "Run it with sudo: sudo bash $0 <version>" >&2; exit 1; }

VERSION=${1:-}
NAME=homeassistant
IMAGE=ghcr.io/home-assistant/home-assistant
CONFIG=${HA_CONFIG:-/opt/homeassistant}
HEALTH_URL=${HA_HEALTH_URL:-http://127.0.0.1:8123/manifest.json}
WAIT_SECONDS=${HA_WAIT_SECONDS:-600}   # the first start after an update can take a few minutes
POLL_SECONDS=${HA_POLL_SECONDS:-5}
BACKUP_STATUS=${BACKUP_STATUS:-/var/lib/homehub-backup/last-success}

fail() { echo "Stopped: $*" >&2; exit 1; }

[[ $VERSION =~ ^20[0-9]{2}\.[0-9]{1,2}\.[0-9]{1,3}$ ]] \
    || fail "give the version to install, e.g.: sudo bash $0 2026.12.1 (see https://github.com/home-assistant/core/releases)"
docker inspect "$NAME" >/dev/null 2>&1 || fail "there is no '$NAME' container"

current=$(docker inspect "$NAME" --format '{{index .Config.Labels "io.hass.version"}}')
[[ $current != "$VERSION" ]] || { echo "Home Assistant is already on $VERSION."; exit 0; }
newest=$(printf '%s\n%s\n' "$current" "$VERSION" | sort -V | tail -n 1)
[[ $newest == "$VERSION" ]] \
    || fail "$VERSION is older than the running $current. Home Assistant can't go back a version without its backup."

# Only a container set up the way this script recreates it (deploy/README.md), so no setting is silently lost.
settings=$(docker inspect "$NAME" --format \
    '{{.HostConfig.NetworkMode}} {{.HostConfig.RestartPolicy.Name}} {{.HostConfig.Privileged}} {{json .HostConfig.Binds}} {{len .HostConfig.Devices}}')
[[ $settings == "host unless-stopped false [\"$CONFIG:/config\"] 0" ]] \
    || fail "the container has settings this script doesn't know ($settings); update it by hand"
tz=$(docker inspect "$NAME" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^TZ=//p')

echo "Home Assistant $current -> $VERSION"

echo "1/4 Backup first"
before=$(stat -c %Y "$BACKUP_STATUS" 2>/dev/null || echo 0)
systemctl start homehub-backup.service || fail "the backup failed (journalctl -u homehub-backup); nothing was changed"
after=$(stat -c %Y "$BACKUP_STATUS" 2>/dev/null || echo 0)
((after > before)) || fail "the backup didn't record a success; nothing was changed"

echo "2/4 Downloading $IMAGE:$VERSION"
docker pull "$IMAGE:$VERSION" || fail "can't download $VERSION; nothing was changed"

echo "3/4 Starting $VERSION (the old container is kept as $NAME-previous)"
docker rm -f "$NAME-previous" >/dev/null 2>&1 || true
docker stop "$NAME" >/dev/null
docker rename "$NAME" "$NAME-previous"
docker update --restart=no "$NAME-previous" >/dev/null   # so a restart of the hub doesn't start both

rollback() {
    echo "Home Assistant $VERSION didn't answer within $WAIT_SECONDS seconds. Its last log lines:" >&2
    docker logs --tail 30 "$NAME" >&2 || true
    echo "Going back to $current." >&2
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker rename "$NAME-previous" "$NAME"
    docker update --restart=unless-stopped "$NAME" >/dev/null
    docker start "$NAME" >/dev/null
    echo "Back on $current. If it misbehaves (the new version may already have changed its database), restore" >&2
    echo "Home Assistant from today's backup: see 'Restoring' in deploy/README.md." >&2
    exit 1
}

docker run -d --name "$NAME" --restart=unless-stopped --network=host \
    -e TZ="${tz:-UTC}" -v "$CONFIG:/config" "$IMAGE:$VERSION" >/dev/null || rollback

echo "4/4 Waiting for Home Assistant to answer (up to $WAIT_SECONDS seconds)"
deadline=$((SECONDS + WAIT_SECONDS))
until curl -fsS -o /dev/null --max-time 5 "$HEALTH_URL"; do
    ((SECONDS < deadline)) || rollback
    sleep "$POLL_SECONDS"
done

docker rm "$NAME-previous" >/dev/null
echo
echo "Done: Home Assistant $VERSION is running. Kudil reconnects on its own within a minute."
echo "Check: open a door and see it in Kudil; open Home Assistant (http://192.168.5.251:8123) > Settings > Repairs."
echo "The old image stays for now. To free about 3.5 GB later: sudo docker image prune -a"
