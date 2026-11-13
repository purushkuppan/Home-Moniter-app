#!/usr/bin/env bash
# ntfy on the hub, for Kudil's alerts to Android phones (#154, ADR 12). Run on the hub, and again whenever this file
# changes:
#   sudo bash setup-ntfy.sh
# Safe to run again: an installed version, the topic and Kudil's token are kept.
#
# - Installs ntfy (Apache-2.0/GPL-2.0, its own process; nothing is linked into Kudil) at a pinned version from its
#   GitHub release, checked against the release's checksums.txt.
# - Runs it as the "ntfy" service on port 2586, with login required by default ("deny-all") and no web app.
# - Kudil may only publish (a write-only token); phones may only read (anonymous, read-only on one random topic).
#   Nobody else can publish alerts that look like Kudil's.
# - The topic and the token become systemd credentials for Kudil (homehub.ntfy.topic, homehub.ntfy.token), so neither
#   is ever in git. Kudil is restarted once if they are new.
# - The firewall opens 2586 to the home network and to Tailscale devices only.
set -euo pipefail

# NTFY_SETUP_TEST: lets deploy/test/ntfy-setup-test.sh run this with stand-in commands and folders.
[[ $EUID -eq 0 || -n ${NTFY_SETUP_TEST:-} ]] || { echo "Run it with sudo: sudo bash $0" >&2; exit 1; }

# Pinned: moved forward on purpose (ADR 12), after reading the release notes. Change VERSION only.
VERSION=2.11.0
PORT=2586
LAN=${LAN:-192.168.4.0/22}   # whole home network: 192.168.4.0 - 192.168.7.255 (PC, phones and hub)
CONFIG_DIR=${NTFY_CONFIG_DIR:-/etc/ntfy}
DATA_DIR=${NTFY_DATA_DIR:-/var/lib/ntfy}
CACHE_DIR=${NTFY_CACHE_DIR:-/var/cache/ntfy}
CREDENTIALS=${HOMEHUB_CREDENTIALS:-/etc/homehub/credentials}
UNIT_DIR=${SYSTEMD_DIR:-/etc/systemd/system}
WAIT_SECONDS=${NTFY_WAIT_SECONDS:-30}
POLL_SECONDS=${NTFY_POLL_SECONDS:-1}
PUBLISHER=kudil

fail() { echo "Stopped: $*" >&2; exit 1; }
as_ntfy() { runuser -u ntfy -- "$@"; }
# Random hex from the kernel. od reads exactly what it needs, so no pipe is cut short (that fails under pipefail).
random_hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

# Kudil must already read the two credentials (homehub.service from setup-hub.sh, re-run after this change).
[[ -d $CREDENTIALS ]] || fail "$CREDENTIALS is missing: run setup-hub.sh first"
grep -q 'homehub.ntfy.token' "$UNIT_DIR/homehub.service" 2>/dev/null \
    || fail "homehub.service doesn't load the ntfy credentials yet: run 'sudo bash setup-hub.sh' first, then this again"

# 1. ntfy itself, the pinned version.
case $(uname -m) in
    x86_64) ARCH=amd64 ;;
    aarch64) ARCH=arm64 ;;
    *) fail "no ntfy package for $(uname -m)" ;;
esac
installed=$(dpkg-query -W -f '${Version}' ntfy 2>/dev/null || true)
if [[ $installed == "$VERSION" ]]; then
    echo "1/5 ntfy $VERSION is already installed"
else
    DEB="ntfy_${VERSION}_linux_${ARCH}.deb"
    URL="https://github.com/binwiederhier/ntfy/releases/download/v$VERSION"
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    echo "1/5 Downloading ntfy $VERSION ($ARCH)..."
    curl -fsSL -o "$tmp/$DEB" "$URL/$DEB"
    curl -fsSL -o "$tmp/checksums.txt" "$URL/checksums.txt"
    grep -E "^[0-9a-f]{64}  $DEB\$" "$tmp/checksums.txt" > "$tmp/expected" || fail "$DEB is not in the release's checksums.txt"
    (cd "$tmp" && sha256sum -c expected) >/dev/null || fail "$DEB doesn't match its checksum; nothing was installed"
    echo "    SHA-256 $(cut -d ' ' -f 1 "$tmp/expected") matches the release's checksums.txt"
    dpkg -i "$tmp/$DEB" >/dev/null
fi

# 2. Its settings. Written in full each time, so a change here reaches the hub.
id ntfy >/dev/null 2>&1 || useradd --system --home-dir "$DATA_DIR" --shell /usr/sbin/nologin ntfy
install -d -o ntfy -g ntfy -m 0750 "$DATA_DIR" "$CACHE_DIR"
install -d -m 0755 "$CONFIG_DIR"
cat > "$CONFIG_DIR/server.yml" <<EOF
# Written by Kudil's deploy/setup-ntfy.sh; changes here are overwritten when it runs again.
base-url: "http://kudil:$PORT"
listen-http: ":$PORT"
cache-file: "$CACHE_DIR/cache.db"
cache-duration: "12h"
auth-file: "$DATA_DIR/user.db"
auth-default-access: "deny-all"
enable-signup: false
enable-login: false
web-root: "disable"
EOF
chmod 0644 "$CONFIG_DIR/server.yml"
# The packaged unit runs ntfy as root on some versions: run it as "ntfy", with little memory and no extra rights.
install -d -m 0755 "$UNIT_DIR/ntfy.service.d"
cat > "$UNIT_DIR/ntfy.service.d/kudil.conf" <<EOF
[Service]
User=ntfy
Group=ntfy
MemoryMax=96M
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=$DATA_DIR $CACHE_DIR
EOF
systemctl daemon-reload
systemctl enable ntfy >/dev/null 2>&1
systemctl restart ntfy
deadline=$((SECONDS + WAIT_SECONDS))
until [[ $(curl -fs --max-time 3 "http://127.0.0.1:$PORT/v1/health" || true) == *'"healthy":true'* ]]; do
    if ((SECONDS >= deadline)); then
        journalctl -u ntfy -n 20 --no-pager >&2 || true
        fail "ntfy didn't answer on port $PORT within $WAIT_SECONDS seconds"
    fi
    sleep "$POLL_SECONDS"
done
echo "2/5 ntfy runs on port $PORT (login required, no web app)"

# 3. Who may do what: Kudil publishes, phones read, on one topic.
topic_file="$CREDENTIALS/homehub.ntfy.topic"
token_file="$CREDENTIALS/homehub.ntfy.token"
[[ -f $topic_file ]] || install -m 0600 /dev/null "$topic_file"
[[ -f $token_file ]] || install -m 0600 /dev/null "$token_file"
changed=no
topic=$(tr -d '[:space:]' < "$topic_file")
if [[ ! $topic =~ ^kudil-[a-z0-9]{16}$ ]]; then
    # Hard to guess, though it isn't the lock: anonymous users may only read it, and only from home or Tailscale.
    topic="kudil-$(random_hex 8)"
    [[ $topic =~ ^kudil-[a-z0-9]{16}$ ]] || fail "couldn't make a topic name"
    printf '%s' "$topic" > "$topic_file"
    changed=yes
fi
users=$(as_ntfy ntfy user list 2>&1) || fail "ntfy couldn't list its users"
if ! grep -q "^user $PUBLISHER " <<< "$users"; then
    # The password is never used (Kudil uses a token), so it is random and not kept.
    NTFY_PASSWORD="$(random_hex 24)" as_ntfy ntfy user add "$PUBLISHER" >/dev/null
fi
as_ntfy ntfy access "$PUBLISHER" "$topic" write-only >/dev/null
as_ntfy ntfy access everyone "$topic" read-only >/dev/null
token=$(tr -d '[:space:]' < "$token_file")
if [[ ! $token =~ ^tk_[A-Za-z0-9]+$ ]]; then
    made=$(as_ntfy ntfy token add "$PUBLISHER" 2>&1) || fail "ntfy didn't make a token for $PUBLISHER"
    token=$(grep -oE 'tk_[A-Za-z0-9]+' <<< "$made" | head -n 1 || true)
    unset made
    [[ -n $token ]] || fail "ntfy didn't make a token for $PUBLISHER"
    printf '%s' "$token" > "$token_file"
    changed=yes
fi
unset token
echo "3/5 Kudil may publish to the topic, phones may read it (topic and token are in $CREDENTIALS)"

# 4. Firewall: the home network and Tailscale devices. The Tailscale rule goes above setup-tailscale.sh's
#    "nothing but Kudil", which also lists ntfy, so running either script again keeps it reachable.
ufw allow from "$LAN" to any port "$PORT" proto tcp comment 'ntfy from the home network' >/dev/null
if ufw status | grep -q 'tailscale0'; then
    ufw delete allow in on tailscale0 to any port "$PORT" proto tcp >/dev/null 2>&1 || true
    ufw prepend allow in on tailscale0 to any port "$PORT" proto tcp comment 'ntfy over Tailscale' >/dev/null
fi
echo "4/5 Port $PORT is open to the home network ($LAN) and Tailscale devices only"

# 5. Kudil picks up new credentials on a restart.
if [[ $changed == yes ]]; then
    systemctl restart homehub
    echo "5/5 Kudil restarted with the new topic and token"
else
    echo "5/5 Kudil already has the topic and token"
fi

echo
echo "Done. On each Android phone, in the ntfy app (F-Droid or Google Play):"
echo "  + > topic: $topic"
echo "      'Use another server': http://kudil:$PORT (works at home and through Tailscale)"
echo "  then in the topic's settings: Instant delivery on, and let urgent notifications override Do Not Disturb."
echo "Then remove that phone from homehub.alerts.send-to, and check with Kudil: Settings > Alerts > Send test."
