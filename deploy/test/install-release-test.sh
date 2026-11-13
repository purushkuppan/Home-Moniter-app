#!/usr/bin/env bash
# Tests deploy/install-release.sh with stand-in systemctl, curl and friends, in a temporary folder instead of
# /opt/homehub. No root needed.
#   bash deploy/test/install-release-test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../install-release.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

failures=0
check() {   # check "description" command...
    if "${@:2}"; then echo "ok   - $1"; else echo "FAIL - $1"; failures=$((failures + 1)); fi
}
fails() { ! "$@"; }

mkdir "$T/bin"
# curl: the health check answers UP unless FAKE_HEALTH=down. The self-check (POST /api/selfcheck) answers with
# FAKE_SELFCHECK: ok, rules (the rules check fails) or none (no answer); FAKE_SELFCHECK_FAILS=N fails the first N tries.
cat > "$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$FAKE/calls"
out=""; url=""
while (($#)); do case $1 in -o) out=$2; shift 2 ;; -w|-X) shift 2 ;; -*) shift ;; *) url=$1; shift ;; esac; done
case $url in
    */actuator/health) [[ ${FAKE_HEALTH:-up} == up ]] && echo '{"status":"UP"}' || exit 7 ;;
    */api/selfcheck)
        n=$(( $(cat "$FAKE/tries" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$FAKE/tries"
        mode=${FAKE_SELFCHECK:-ok}
        (( n <= ${FAKE_SELFCHECK_FAILS:-0} )) && mode=rules
        case $mode in
            ok) echo '{"ok":true,"checks":[{"name":"stored","ok":true,"detail":"x"}]}' > "$out"; printf 200 ;;
            rules) printf '%s' '{"ok":false,"checks":[{"name":"stored","ok":true,"detail":"x"},{"name":"rules","ok":false,"detail":"A pretend leak raised no alert."},{"name":"home assistant","ok":false,"detail":"Not connected yet."}]}' > "$out"; printf 503 ;;
            none) printf 000; exit 7 ;;
        esac ;;
esac
EOF
# install: the real one, without -o/-g (only root may change owners).
cat > "$T/bin/install" <<'EOF'
#!/usr/bin/env bash
args=()
while (($#)); do case $1 in -o|-g) shift 2 ;; *) args+=("$1"); shift ;; esac; done
exec /usr/bin/install "${args[@]}"
EOF
# shellcheck disable=SC2016  # $* and $FAKE belong to the stand-ins, not to this script
for cmd in systemctl journalctl chown sleep; do
    printf '#!/usr/bin/env bash\necho "%s $*" >> "$FAKE/calls"\n' "$cmd" > "$T/bin/$cmd"
done
chmod +x "$T/bin/"*

printf 'PK\003\004 a jar' > "$T/new.jar"

hub() {   # a hub running v0.1.0
    rm -rf "$T/hub" && mkdir -p "$T/hub/releases/v0.1.0" "$T/hub/data" "$T/hub/fake"
    echo old > "$T/hub/releases/v0.1.0/homehub.jar"
    ln -s "$T/hub/releases/v0.1.0" "$T/hub/current"
}
run() {   # run [NAME=value...]: installs v0.2.0
    : > "$T/hub/fake/calls"; rm -f "$T/hub/fake/tries"
    env -u SUDO_USER PATH="$T/bin:$PATH" FAKE="$T/hub/fake" INSTALL_RELEASE_TEST=1 HOMEHUB_BASE="$T/hub" "$@" \
        bash "$SCRIPT" "$T/new.jar" v0.2.0 > "$T/log" 2>&1
}
current() { basename "$(readlink -f "$T/hub/current")"; }
note() { cat "$T/hub/data/deploy-rolled-back" 2>/dev/null; }
restarts() { grep -c "systemctl restart homehub" "$T/hub/fake/calls"; }

hub
check "a good release is installed" run
check "...and is running" test "$(current)" = v0.2.0
check "...after the alerts check" grep -q -- "-X POST" "$T/hub/fake/calls"
check "...says so" grep -q "HomeHub v0.2.0 is up, and alerts work." "$T/log"
check "...and leaves no note" test ! -e "$T/hub/data/deploy-rolled-back"

hub
check "a release whose alerts check fails is refused" fails run FAKE_SELFCHECK=rules
check "...the previous release runs again" test "$(current)" = v0.1.0
check "...and is restarted" test "$(restarts)" = 2
check "...the broken one is removed" test ! -d "$T/hub/releases/v0.2.0"
check "...the note names the version and the failed checks" \
    test "$(note)" = "v0.2.0 Alerts check failed: rules, home assistant."
check "...the checks are in the deploy log" grep -q "A pretend leak raised no alert." "$T/log"
check "...after trying for a while (Home Assistant may still be connecting)" test "$(cat "$T/hub/fake/tries")" = 30

hub
check "a few failed tries before it passes are fine" run FAKE_SELFCHECK_FAILS=3
check "...it runs" test "$(current)" = v0.2.0
check "...after the 4th try" test "$(cat "$T/hub/fake/tries")" = 4

hub
check "no answer from the alerts check is refused too" fails run FAKE_SELFCHECK=none
check "...with a note saying so" test "$(note)" = "v0.2.0 Alerts check failed: no answer (HTTP 000)."
check "...and the previous release" test "$(current)" = v0.1.0

hub
check "a release that doesn't start is refused" fails run FAKE_HEALTH=down
check "...the previous release runs again" test "$(current)" = v0.1.0
check "...with a note" test "$(note)" = "v0.2.0 Health check failed: the app didn't start."
check "...and the alerts check isn't run" fails grep -q -- "-X POST" "$T/hub/fake/calls"

hub
echo "v0.1.5 Alerts check failed: rules." > "$T/hub/data/deploy-rolled-back"
check "a good release after a failed one works" run
check "...and the old note is gone before it starts, so it isn't told again" test ! -e "$T/hub/data/deploy-rolled-back"

check "a version that isn't vX.Y.Z is refused" \
    fails env PATH="$T/bin:$PATH" FAKE="$T/hub/fake" INSTALL_RELEASE_TEST=1 HOMEHUB_BASE="$T/hub" bash "$SCRIPT" "$T/new.jar" latest

echo
if ((failures)); then echo "$failures failed"; exit 1; fi
echo "All install-release checks passed"
