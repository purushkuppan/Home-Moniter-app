#!/usr/bin/env bash
# Tests deploy/setup-ntfy.sh with stand-in curl, dpkg, systemctl, ufw and ntfy that only record what they are asked
# to do, and temporary folders for /etc and /var. No root, no network needed.
#   bash deploy/test/ntfy-setup-test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../setup-ntfy.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

failures=0
check() {   # check "description" command...
    if "${@:2}"; then echo "ok   - $1"; else echo "FAIL - $1"; failures=$((failures + 1)); fi
}
fails() { ! "$@"; }

VERSION=$(sed -n 's/^VERSION=//p' "$SCRIPT")
case $(uname -m) in x86_64) ARCH=amd64 ;; aarch64) ARCH=arm64 ;; *) echo "skip: no ntfy package for $(uname -m)"; exit 0 ;; esac
DEB="ntfy_${VERSION}_linux_${ARCH}.deb"
TOKEN=tk_TESTTOKENabc123

mkdir "$T/bin" "$T/release"
printf 'not really a package\n' > "$T/release/$DEB"
# curl: the release's files from $FAKE_RELEASE; ntfy's health answer while FAKE_HEALTHY is set.
cat > "$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$FAKE/calls"
out=""
while (($#)); do case $1 in -o) out=$2; shift 2 ;; *) url=$1; shift ;; esac; done
case $url in
    */v1/health) [[ -n ${FAKE_HEALTHY:-} ]] && echo '{"healthy":true}' || exit 7 ;;
    https://github.com/*) cp "$FAKE_RELEASE/${url##*/}" "$out" ;;
    *) exit 7 ;;
esac
EOF
cat > "$T/bin/dpkg-query" <<'EOF'
#!/usr/bin/env bash
[[ -f $FAKE/installed ]] && cat "$FAKE/installed" || exit 1
EOF
cat > "$T/bin/dpkg" <<'EOF'
#!/usr/bin/env bash
echo "dpkg $*" >> "$FAKE/calls"
echo "$FAKE_VERSION" > "$FAKE/installed"
EOF
# ntfy: users in $FAKE/users; "token add" prints a token like the real one does.
cat > "$T/bin/ntfy" <<'EOF'
#!/usr/bin/env bash
echo "ntfy $*" >> "$FAKE/calls"
case "$1 $2" in
    "user list") [[ -f $FAKE/users ]] && cat "$FAKE/users"; echo "user * (role: anonymous, tier: none)" ;;
    "user add") [[ -n ${NTFY_PASSWORD:-} ]] || exit 1; echo "user $3 (role: user, tier: none)" >> "$FAKE/users"; echo "user $3 added" ;;
    "token add") echo "token $FAKE_TOKEN created for user $3, never expires" ;;
esac
exit 0
EOF
cat > "$T/bin/runuser" <<'EOF'
#!/usr/bin/env bash
echo "runuser $1 $2" >> "$FAKE/calls"
shift 3   # -u ntfy --
"$@"
EOF
# install: the real one, without -o/-g (only root may change owners).
cat > "$T/bin/install" <<'EOF'
#!/usr/bin/env bash
args=()
while (($#)); do case $1 in -o|-g) shift 2 ;; *) args+=("$1"); shift ;; esac; done
exec /usr/bin/install "${args[@]}"
EOF
# shellcheck disable=SC2016  # $* and $FAKE belong to the stand-ins, not to this script
for cmd in systemctl journalctl useradd; do   # stand-ins that only record their call
    printf '#!/usr/bin/env bash\necho "%s $*" >> "$FAKE/calls"\n' "$cmd" > "$T/bin/$cmd"
done
cat > "$T/bin/id" <<'EOF'
#!/usr/bin/env bash
[[ $1 == ntfy ]] && exit 0
exec /usr/bin/id "$@"
EOF
cat > "$T/bin/ufw" <<'EOF'
#!/usr/bin/env bash
echo "ufw $*" >> "$FAKE/calls"
[[ $1 == status ]] && { echo "Status: active"; [[ -n ${FAKE_TAILSCALE:-} ]] && echo "8080/tcp on tailscale0  ALLOW IN  Anywhere"; }
exit 0
EOF
chmod +x "$T/bin/"*

hub() {   # a fresh hub: Kudil's credentials folder and a homehub.service that loads the ntfy credentials
    rm -rf "$T/hub" && mkdir -p "$T/hub/fake" "$T/hub/creds" "$T/hub/systemd" "$T/hub/etc" "$T/hub/lib" "$T/hub/cache"
    echo "LoadCredential=homehub.ntfy.token:/etc/homehub/credentials/homehub.ntfy.token" > "$T/hub/systemd/homehub.service"
    (cd "$T/release" && sha256sum "$DEB") > "$T/release/checksums.txt"
}
run() {   # run [NAME=value...]
    : > "$T/hub/fake/calls"
    env PATH="$T/bin:$PATH" FAKE="$T/hub/fake" FAKE_RELEASE="$T/release" FAKE_VERSION="$VERSION" FAKE_TOKEN="$TOKEN" \
        FAKE_HEALTHY=1 NTFY_SETUP_TEST=1 HOMEHUB_CREDENTIALS="$T/hub/creds" SYSTEMD_DIR="$T/hub/systemd" \
        NTFY_CONFIG_DIR="$T/hub/etc" NTFY_DATA_DIR="$T/hub/lib" NTFY_CACHE_DIR="$T/hub/cache" \
        NTFY_WAIT_SECONDS=0 NTFY_POLL_SECONDS=0 "$@" bash "$SCRIPT" > "$T/log" 2>&1
}
called() { grep -q -F -- "$1" "$T/hub/fake/calls"; }

# A new hub with Tailscale.
hub
check "a first run works" run FAKE_TAILSCALE=1
check "downloads the pinned version from its GitHub release" called "curl -fsSL -o"
check "...and installs it" called "dpkg -i"
check "checks the package against the release's checksums" grep -q "matches the release's checksums.txt" "$T/log"
check "login is required by default" grep -q '^auth-default-access: "deny-all"$' "$T/hub/etc/server.yml"
check "no sign-up and no web app" grep -q '^web-root: "disable"$' "$T/hub/etc/server.yml"
check "ntfy runs as its own user, with a memory cap" grep -q '^User=ntfy$' "$T/hub/systemd/ntfy.service.d/kudil.conf"
check "ntfy is (re)started" called "systemctl restart ntfy"
topic=$(cat "$T/hub/creds/homehub.ntfy.topic")
check "a random topic becomes Kudil's credential" grep -qE '^kudil-[a-f0-9]{16}$' <<< "$topic"
check "the token becomes Kudil's credential" test "$(cat "$T/hub/creds/homehub.ntfy.token")" = "$TOKEN"
check "...readable by root only" test "$(stat -c %a "$T/hub/creds/homehub.ntfy.token")" = 600
check "...and is never printed" fails grep -q "$TOKEN" "$T/log"
check "Kudil may only publish" called "ntfy access kudil $topic write-only"
check "phones may only read" called "ntfy access everyone $topic read-only"
check "ntfy commands run as the ntfy user" called "runuser -u ntfy"
check "open to the home network" called "ufw allow from 192.168.4.0/22 to any port 2586 proto tcp"
check "open to Tailscale devices, above 'nothing but Kudil'" called "ufw prepend allow in on tailscale0 to any port 2586"
check "Kudil restarts to read the new credentials" called "systemctl restart homehub"
check "the phones' topic is printed" grep -q "topic: $topic" "$T/log"

# The same hub again: nothing new.
check "a second run works" run FAKE_TAILSCALE=1
check "the installed version isn't downloaded again" fails called "dpkg -i"
check "the topic stays" test "$(cat "$T/hub/creds/homehub.ntfy.topic")" = "$topic"
check "no second user" fails called "ntfy user add"
check "no new token" fails called "ntfy token add"
check "Kudil isn't restarted" fails called "systemctl restart homehub"

# Without Tailscale: only the home network.
hub
check "runs without Tailscale" run
check "no Tailscale rule then" fails called "tailscale0 to any port"

# A package that doesn't match its checksum is never installed.
hub
echo "0000000000000000000000000000000000000000000000000000000000000000  $DEB" > "$T/release/checksums.txt"
check "a wrong checksum stops it" fails run
check "...before anything is installed" fails called "dpkg -i"
check "...and says why" grep -q "doesn't match its checksum" "$T/log"

# Kudil's service must load the credentials first.
hub
: > "$T/hub/systemd/homehub.service"
check "an old homehub.service stops it" fails run
check "...asking for setup-hub.sh" grep -q "run 'sudo bash setup-hub.sh' first" "$T/log"
check "...before anything is downloaded" fails called "curl"

# ntfy that doesn't come up.
hub
check "ntfy not answering stops it" fails run FAKE_HEALTHY=
check "...with its log" called "journalctl -u ntfy"
check "...and no token is made" fails called "ntfy token add"

echo
if ((failures)); then echo "$failures failed"; exit 1; fi
echo "All ntfy setup checks passed"
