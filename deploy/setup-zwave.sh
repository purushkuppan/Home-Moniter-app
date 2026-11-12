#!/usr/bin/env bash
# Locks down Z-Wave JS UI on the hub (#144). Run on the hub, and again whenever this file changes:
#   sudo bash setup-zwave.sh
# Safe to run again: a container that is already locked down is left running.
#
# - Its web UI (8091) answers on the hub itself only, like the Z-Wave server for Home Assistant (3000) already does.
#   Docker-published ports bypass the firewall, so "0.0.0.0:8091" was open to every Wi-Fi and Tailscale device.
#   To pair a device, open the UI through SSH from the PC (printed at the end).
# - Its folder, which holds the Z-Wave security keys, can be read by root only.
# The container is recreated with the same image, stick, folder and time zone. If it doesn't come back within two
# minutes, the old one is started again. Z-Wave devices are unavailable for a few seconds, which Kudil ignores.
set -euo pipefail

# ZWAVE_SETUP_TEST: lets deploy/test/zwave-setup-test.sh run this with stand-in commands.
[[ $EUID -eq 0 || -n ${ZWAVE_SETUP_TEST:-} ]] || { echo "Run it with sudo: sudo bash $0" >&2; exit 1; }

NAME=zwave-js-ui
STORE=${ZWAVE_DIR:-/opt/zwave-js-ui}
WAIT_SECONDS=${ZWAVE_WAIT_SECONDS:-120}
POLL_SECONDS=${ZWAVE_POLL_SECONDS:-3}
HUB=${HUB_ADDRESS:-192.168.5.251}

fail() { echo "Stopped: $*" >&2; exit 1; }

docker inspect "$NAME" >/dev/null 2>&1 || fail "there is no '$NAME' container"
[[ -f $STORE/settings.json ]] || fail "no settings.json in $STORE"

# 1. The keys: root only. The container runs as root, so it still reads and writes them.
chmod 0700 "$STORE"
echo "1/3 $STORE can be read by root only"

# 2. The ports. Only a container set up the way this script recreates it, so no setting is silently lost.
settings=$(docker inspect "$NAME" --format \
    '{{.HostConfig.NetworkMode}} {{.HostConfig.RestartPolicy.Name}} {{.HostConfig.Privileged}} {{json .HostConfig.Binds}} {{len .HostConfig.Devices}}')
[[ $settings == "bridge unless-stopped false [\"$STORE:/usr/src/app/store\"] 1" ]] \
    || fail "the container has settings this script doesn't know ($settings); change it by hand"
ports=$(docker inspect "$NAME" --format '{{range $p, $b := .HostConfig.PortBindings}}{{$p}}={{range $b}}{{.HostIp}}{{end}} {{end}}')
case "$ports" in
    "3000/tcp=127.0.0.1 8091/tcp=127.0.0.1 ")
        echo "2/3 Already open to the hub only (3000 and 8091 on 127.0.0.1)"
        recreated=no ;;
    "3000/tcp=127.0.0.1 8091/tcp= "|"3000/tcp=127.0.0.1 8091/tcp=0.0.0.0 ")
        recreated=yes ;;
    *) fail "unexpected port settings ($ports); change them by hand" ;;
esac

if [[ $recreated == yes ]]; then
    image=$(docker inspect "$NAME" --format '{{.Config.Image}}')
    stick=$(docker inspect "$NAME" --format '{{range .HostConfig.Devices}}{{.PathOnHost}}:{{.PathInContainer}}{{end}}')
    tz=$(docker inspect "$NAME" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^TZ=//p')
    [[ $stick == /dev/serial/by-id/*:/dev/zwave ]] || fail "unexpected stick mapping ($stick); change it by hand"

    echo "2/3 Recreating $NAME with 8091 open to the hub only (the old container is kept as $NAME-previous)"
    docker rm -f "$NAME-previous" >/dev/null 2>&1 || true
    docker stop -t 30 "$NAME" >/dev/null   # time to save its caches
    docker rename "$NAME" "$NAME-previous"
    docker update --restart=no "$NAME-previous" >/dev/null   # so a restart of the hub doesn't start both

    rollback() {
        echo "$NAME didn't come back within $WAIT_SECONDS seconds. Its last log lines:" >&2
        docker logs --tail 30 "$NAME" >&2 || true
        echo "Starting the old container again." >&2
        docker rm -f "$NAME" >/dev/null 2>&1 || true
        docker rename "$NAME-previous" "$NAME"
        docker update --restart=unless-stopped "$NAME" >/dev/null
        docker start "$NAME" >/dev/null
        echo "Back as before (8091 still open to the network). Nothing else was changed." >&2
        exit 1
    }

    # --pull=never: the same image that ran before, never a newer one by surprise.
    docker run -d --name "$NAME" --restart=unless-stopped --pull=never \
        -e TZ="${tz:-UTC}" --device "$stick" -v "$STORE:/usr/src/app/store" \
        -p 127.0.0.1:3000:3000 -p 127.0.0.1:8091:8091 "$image" >/dev/null || rollback

    deadline=$((SECONDS + WAIT_SECONDS))
    until curl -s -o /dev/null --max-time 5 http://127.0.0.1:8091/ && curl -s -o /dev/null --max-time 5 http://127.0.0.1:3000/; do
        ((SECONDS < deadline)) || rollback
        sleep "$POLL_SECONDS"
    done
    docker rm "$NAME-previous" >/dev/null
    echo "    Back up. Home Assistant reconnects to the Z-Wave server on its own."
fi

# 3. The login. Asking for the settings without one must fail; the answer itself is thrown away unread.
code=$(curl -s -o /dev/null --max-time 5 -w '%{http_code}' http://127.0.0.1:8091/api/settings || true)
echo
echo "To open Z-Wave JS UI (for pairing), from the PC:"
echo "    ssh -L 8091:127.0.0.1:8091 purush@$HUB"
echo "then http://localhost:8091 in the PC's browser, while that SSH window stays open."
if [[ $code == 200 ]]; then
    echo
    echo "3/3 Z-Wave JS UI has no login yet. Through the SSH tunnel above: Settings > General > turn on 'Auth' > Save."
    echo "    Then sign in as admin / zwave and change the password at once (top right > Change password)."
    echo "    Run this script again afterwards to check."
    exit 2
fi
echo "3/3 Z-Wave JS UI asks for a login"
echo
echo "Done."
