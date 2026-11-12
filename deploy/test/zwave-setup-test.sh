#!/usr/bin/env bash
# Tests deploy/setup-zwave.sh with stand-in docker, curl and timeout that only record what they are asked to do.
# No root, no Docker needed.
#   bash deploy/test/zwave-setup-test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../setup-zwave.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

failures=0
check() {   # check "description" command...
    if "${@:2}"; then echo "ok   - $1"; else echo "FAIL - $1"; failures=$((failures + 1)); fi
}
fails() { ! "$@"; }

STICK=/dev/serial/by-id/usb-Zooz_800_Z-Wave_Stick_533D004242-if00
mkdir "$T/bin"
# docker: each container is a folder in $FAKE/containers with its image and ports; every call goes to $FAKE/calls.
cat > "$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$FAKE/calls"
c="$FAKE/containers"
case $1 in
    inspect)
        [[ -d $c/$2 ]] || exit 1
        case $* in
            *NetworkMode*) echo "$FAKE_SETTINGS" ;;
            *PortBindings*) cat "$c/$2/ports" ;;
            *Config.Image*) cat "$c/$2/image" ;;
            *PathOnHost*) echo "$FAKE_STICK:/dev/zwave" ;;
            *Config.Env*) printf 'TZ=America/Chicago\nNODE_ENV=production\n' ;;
        esac ;;
    stop|update|start|logs) [[ -d $c/${!#} ]] ;;
    rename) [[ -d $c/$2 ]] && mv "$c/$2" "$c/$3" ;;
    rm) [[ -d $c/${!#} ]] && rm -r "${c:?}/${!#}" ;;
    run)
        [[ -z ${FAKE_RUN_FAIL:-} ]] || exit 125
        name=$(sed -n 's/.*--name \([^ ]*\).*/\1/p' <<< "$*")
        mkdir "$c/$name" && echo "${!#}" > "$c/$name/image"
        echo "3000/tcp=127.0.0.1 8091/tcp=127.0.0.1 " > "$c/$name/ports" ;;
esac
EOF
# curl: the web UI on 8091 answers when FAKE_UI_UP is set. Like the real Z-Wave JS server, port 3000 never answers
# an HTTP request (#148): curl to it times out (exit 28) even while the server runs.
cat > "$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$FAKE/calls"
case $* in
    *127.0.0.1:3000*) exit 28 ;;
    *127.0.0.1:8091*) [[ -n ${FAKE_UI_UP:-} ]] ;;
    *) exit 7 ;;
esac
EOF
# timeout: stands in for "timeout 3 bash -c '</dev/tcp/127.0.0.1/3000'", the TCP connect to the Z-Wave server, which
# succeeds when FAKE_SERVER_UP is set.
cat > "$T/bin/timeout" <<'EOF'
#!/usr/bin/env bash
echo "timeout $*" >> "$FAKE/calls"
[[ $* == *"/dev/tcp/127.0.0.1/3000"* && -n ${FAKE_SERVER_UP:-} ]]
EOF
chmod +x "$T/bin/"*

run() {   # run [NAME=value...]: a hub whose zwave-js-ui publishes 8091 on every address
    rm -rf "$T/fake" "$T/store" && mkdir -p "$T/fake/containers/zwave-js-ui" "$T/store"
    echo "zwavejs/zwave-js-ui:latest" > "$T/fake/containers/zwave-js-ui/image"
    echo "3000/tcp=127.0.0.1 8091/tcp= " > "$T/fake/containers/zwave-js-ui/ports"
    # Z-Wave JS UI's settings: the login flag ($auth) next to the security keys, which must never be printed.
    printf '{"gateway":{"type":0,"authEnabled":%s},"zwave":{"securityKeys":{"S2_Authenticated":"TEST-KEY-1234"}}}\n' \
        "$auth" > "$T/store/settings.json" && chmod 0755 "$T/store"
    : > "$T/fake/calls"
    case $hub in   # a test's own change to that hub
        locked-down) echo "3000/tcp=127.0.0.1 8091/tcp=127.0.0.1 " > "$T/fake/containers/zwave-js-ui/ports" ;;
        no-container) rm -r "$T/fake/containers/zwave-js-ui" ;;
    esac
    env PATH="$T/bin:$PATH" FAKE="$T/fake" ZWAVE_SETUP_TEST=1 ZWAVE_DIR="$T/store" FAKE_STICK="$STICK" \
        FAKE_SETTINGS="bridge unless-stopped false [\"$T/store:/usr/src/app/store\"] 1" \
        ZWAVE_WAIT_SECONDS=0 ZWAVE_POLL_SECONDS=0 FAKE_UI_UP=1 FAKE_SERVER_UP=1 "$@" bash "$SCRIPT" > "$T/log" 2>&1
}
hub=open   # what run() starts from: open (8091 on every address), locked-down, or no-container
auth=true  # whether Z-Wave JS UI's login is on in that hub's settings
called() { grep -q -F -- "$1" "$T/fake/calls"; }
ports() { cat "$T/fake/containers/zwave-js-ui/ports" 2>/dev/null; }
untouched() { ! called "docker stop" && ! called "docker run" && test "$(ports)" = "3000/tcp=127.0.0.1 8091/tcp= "; }

# 1. Locking down a container that is open to the network.
check "locks it down" run
check "recreates it with both ports on the hub only, the same image, stick, folder and time zone" grep -q -F -x \
    "docker run -d --name zwave-js-ui --restart=unless-stopped --pull=never -e TZ=America/Chicago --device $STICK:/dev/zwave -v $T/store:/usr/src/app/store -p 127.0.0.1:3000:3000 -p 127.0.0.1:8091:8091 zwavejs/zwave-js-ui:latest" \
    "$T/fake/calls"
check "gives it time to save its caches" called "docker stop -t 30 zwave-js-ui"
check "the old container won't start on a reboot while set aside" called "docker update --restart=no zwave-js-ui-previous"
check "the old container is removed once the new one answers" test ! -e "$T/fake/containers/zwave-js-ui-previous"
check "the keys folder is root only" test "$(stat -c %a "$T/store")" = 700
check "says how to reach the UI through SSH" grep -q "ssh -L 8091:127.0.0.1:8091" "$T/log"
check "waits for the web UI" called "curl -s -o /dev/null --max-time 5 http://127.0.0.1:8091/"
check "...and connects to the Z-Wave server instead of asking it over HTTP" called "timeout 3 bash -c </dev/tcp/127.0.0.1/3000"
check "...never with curl, which can't get an answer from it" fails called "127.0.0.1:3000/"
check "the login is on" grep -q "asks for a login" "$T/log"
check "nothing from the settings file is printed" fails grep -q "TEST-KEY" "$T/log"

# 2. Running it again changes nothing.
hub=locked-down
check "a locked-down container is left running" run
check "...not recreated" fails called "docker run"
check "...not stopped" fails called "docker stop"
hub=open

# 3. No login yet: done, but says what to do and exits 2.
auth=false
set +e; run FAKE_UI_UP=1 FAKE_SERVER_UP=1; code=$?; set -e   # everything up, only the login is off
check "without a login it exits 2" test "$code" = 2
check "...and says how to turn it on" grep -q "turn on 'Auth'" "$T/log"
check "...and to change the default password" grep -q "change the password" "$T/log"
check "...but the lockdown itself is done" test "$(ports)" = "3000/tcp=127.0.0.1 8091/tcp=127.0.0.1 "
check "...and nothing from the settings file is printed" fails grep -q "TEST-KEY" "$T/log"
auth=true

# 4. The new container doesn't answer: back to the old one.
check "a Z-Wave server that takes no connections rolls back" fails run FAKE_SERVER_UP=
check "...to the old container" test "$(ports)" = "3000/tcp=127.0.0.1 8091/tcp= "
check "a web UI that doesn't answer fails" fails run FAKE_UI_UP=
check "...and the old container runs again" test "$(ports)" = "3000/tcp=127.0.0.1 8091/tcp= "
check "...and starts on a reboot again" called "docker update --restart=unless-stopped zwave-js-ui"
check "...and is started" called "docker start zwave-js-ui"
check "...and the broken one is gone" test ! -e "$T/fake/containers/zwave-js-ui-previous"
check "a container that can't be created goes back too" fails run FAKE_RUN_FAIL=1
check "...and the old container runs again" test "$(ports)" = "3000/tcp=127.0.0.1 8091/tcp= "

# 5. Nothing changes when the container isn't the one this script knows.
check "other settings are left to change by hand" \
    fails run FAKE_SETTINGS="host unless-stopped false [\"x:/usr/src/app/store\"] 1"
check "...and nothing changed" untouched
check "a different stick mapping is left alone" fails run FAKE_STICK=/dev/ttyUSB0
check "...and nothing changed" untouched
hub=no-container
check "no container: stops" fails run
check "...and says so" grep -q "there is no 'zwave-js-ui' container" "$T/log"
hub=open

if [[ $EUID -ne 0 ]]; then
    check "refuses to run without sudo" fails env PATH="$T/bin:$PATH" bash "$SCRIPT" 2>/dev/null
fi

echo
if ((failures)); then echo "$failures check(s) failed"; exit 1; fi
echo "All Z-Wave setup checks passed"
