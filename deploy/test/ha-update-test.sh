#!/usr/bin/env bash
# Tests deploy/update-home-assistant.sh with stand-in docker, systemctl and curl that only record what they are asked
# to do. No root, no Docker needed.
#   bash deploy/test/ha-update-test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../update-home-assistant.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

failures=0
check() {   # check "description" command...
    if "${@:2}"; then echo "ok   - $1"; else echo "FAIL - $1"; failures=$((failures + 1)); fi
}
fails() { ! "$@"; }

mkdir "$T/bin"
# docker: each container is a file in $FAKE/containers holding its image; every call is written to $FAKE/calls.
cat > "$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$FAKE/calls"
c="$FAKE/containers"
case $1 in
    inspect)
        [[ -f $c/$2 ]] || exit 1
        image=$(cat "$c/$2")
        case $* in
            *io.hass.version*) if [[ $image == *:stable ]]; then echo "$FAKE_CURRENT"; else echo "${image##*:}"; fi ;;
            *NetworkMode*) echo "$FAKE_SETTINGS" ;;
            *Config.Env*) printf 'TZ=America/Chicago\nPATH=/usr/bin\n' ;;
        esac ;;
    pull) [[ -z ${FAKE_PULL_FAIL:-} ]] ;;
    stop|update|start|logs) [[ -f $c/${!#} ]] ;;
    rename) [[ -f $c/$2 ]] && mv "$c/$2" "$c/$3" ;;
    rm) [[ -f $c/${!#} ]] && rm "$c/${!#}" ;;
    run) name=$(sed -n 's/.*--name \([^ ]*\).*/\1/p' <<< "$*"); echo "${!#}" > "$c/$name" ;;
esac
EOF
cat > "$T/bin/systemctl" <<'EOF'
#!/usr/bin/env bash
echo "systemctl $*" >> "$FAKE/calls"
[[ -z ${FAKE_BACKUP_FAIL:-} ]] || exit 1
[[ -n ${FAKE_BACKUP_SILENT:-} ]] || touch "$BACKUP_STATUS"
EOF
cat > "$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$FAKE/calls"
[[ -n ${FAKE_HEALTHY:-} ]]
EOF
chmod +x "$T/bin/"*

run() {   # run <version> [NAME=value...]: a fresh hub with Home Assistant 2026.11.0 from the "stable" tag
    local version=$1; shift
    rm -rf "$T/fake" && mkdir -p "$T/fake/containers"
    echo "ghcr.io/home-assistant/home-assistant:stable" > "$T/fake/containers/homeassistant"
    : > "$T/fake/calls"
    touch -d '2 hours ago' "$T/fake/last-success"
    env PATH="$T/bin:$PATH" FAKE="$T/fake" FAKE_CURRENT=2026.11.0 HA_UPDATE_TEST=1 HA_CONFIG=/opt/homeassistant \
        FAKE_SETTINGS='host unless-stopped false ["/opt/homeassistant:/config"] 0' \
        BACKUP_STATUS="$T/fake/last-success" HA_WAIT_SECONDS=0 HA_POLL_SECONDS=0 FAKE_HEALTHY=1 \
        "$@" bash "$SCRIPT" "$version" > "$T/log" 2>&1
}
called() { grep -q -F -- "$1" "$T/fake/calls"; }
order() { test "$(grep -n -F -- "$1" "$T/fake/calls" | head -n 1 | cut -d: -f1)" -lt \
               "$(grep -n -F -- "$2" "$T/fake/calls" | head -n 1 | cut -d: -f1)"; }
container() { cat "$T/fake/containers/$1" 2>/dev/null; }
untouched() { ! called "docker stop" && ! called "docker run" && test "$(container homeassistant)" = \
    "ghcr.io/home-assistant/home-assistant:stable"; }

# 1. An update that works.
check "updates to a newer version" run 2026.12.1
check "backs up first" order "systemctl start homehub-backup.service" "docker pull"
check "downloads before stopping anything" order "docker pull ghcr.io/home-assistant/home-assistant:2026.12.1" "docker stop"
check "starts the new version with the same settings" grep -q -F -x \
    "docker run -d --name homeassistant --restart=unless-stopped --network=host -e TZ=America/Chicago -v /opt/homeassistant:/config ghcr.io/home-assistant/home-assistant:2026.12.1" \
    "$T/fake/calls"
check "the old container won't start on a reboot while set aside" called "docker update --restart=no homeassistant-previous"
check "the container now runs the fixed version" test "$(container homeassistant)" = "ghcr.io/home-assistant/home-assistant:2026.12.1"
check "the old container is removed once the new one answers" test ! -e "$T/fake/containers/homeassistant-previous"
check "waits for Home Assistant to answer" called "curl -fsS -o /dev/null --max-time 5 http://127.0.0.1:8123/manifest.json"

# 2. The new version doesn't answer: back to the old one.
check "a version that doesn't start fails" fails run 2026.12.1 FAKE_HEALTHY=
check "...and goes back to the old container" test "$(container homeassistant)" = "ghcr.io/home-assistant/home-assistant:stable"
check "...which starts on a reboot again" called "docker update --restart=unless-stopped homeassistant"
check "...and runs" called "docker start homeassistant"
check "...and the broken one is gone" test ! -e "$T/fake/containers/homeassistant-previous"
check "...and says how to restore if needed" grep -q "Restoring" "$T/log"

# 3. Nothing changes when something isn't right.
check "a version is required" fails run ""
check "...in Home Assistant's form" fails run latest
check "...and nothing changed" untouched
check "the same version does nothing" run 2026.11.0
check "...and says so" grep -q "already on 2026.11.0" "$T/log"
check "...and changed nothing" untouched
check "an older version is refused" fails run 2026.10.4
check "...and nothing changed" untouched
check "a container with other settings is left to update by hand" \
    fails run 2026.12.1 FAKE_SETTINGS='host unless-stopped true ["/opt/homeassistant:/config"] 0'
check "...and nothing changed" untouched
check "a failed backup stops it" fails run 2026.12.1 FAKE_BACKUP_FAIL=1
check "...before anything is downloaded" fails called "docker pull"
check "...and nothing changed" untouched
check "a backup that records no success stops it" fails run 2026.12.1 FAKE_BACKUP_SILENT=1
check "...and nothing changed" untouched
check "a failed download stops it" fails run 2026.12.1 FAKE_PULL_FAIL=1
check "...and nothing changed" untouched

if [[ $EUID -ne 0 ]]; then
    check "refuses to run without sudo" fails env PATH="$T/bin:$PATH" bash "$SCRIPT" 2026.12.1 2>/dev/null
fi

echo
if ((failures)); then echo "$failures check(s) failed"; exit 1; fi
echo "All Home Assistant update checks passed"
