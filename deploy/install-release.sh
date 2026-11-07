#!/usr/bin/env bash
# Installs a HomeHub release on the hub, restarts the service, and rolls back if the
# health check fails. Runs as root: the GitHub runner (user ghrunner) and deploy.ps1 call it
# through sudo.
# Usage: install-release.sh /path/to/homehub.jar v0.1.0
set -euo pipefail

JAR="${1:?jar path required}"
VERSION="${2:?version required}"
BASE=/opt/homehub
HEALTH_URL=http://127.0.0.1:8080/actuator/health

if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Refusing version '$VERSION': expected vMAJOR.MINOR.PATCH" >&2
  exit 2
fi

# Read the jar with the rights of whoever called sudo, never as root. Otherwise this script could
# be used to copy root-only files (for example the Home Assistant token) into a readable folder.
STAGED="$(mktemp)"
trap 'rm -f "$STAGED"' EXIT
if [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != root ]]; then
  if ! runuser -u "$SUDO_USER" -- cat -- "$JAR" > "$STAGED"; then
    echo "Cannot read $JAR as $SUDO_USER" >&2
    exit 2
  fi
elif ! cat -- "$JAR" > "$STAGED"; then
  echo "Cannot read $JAR" >&2
  exit 2
fi
# A jar is a zip file: it starts with "PK\x03\x04".
if [[ "$(head -c 4 "$STAGED" | od -An -tx1 | tr -d ' \n')" != "504b0304" ]]; then
  echo "Refusing $JAR: not a jar file" >&2
  exit 2
fi

REL="$BASE/releases/$VERSION"
PREV="$(readlink -f "$BASE/current" 2>/dev/null || true)"

install -d -o homehub -g homehub -m 0755 "$REL"
install -o homehub -g homehub -m 0644 "$STAGED" "$REL/homehub.jar"
ln -sfn "$REL" "$BASE/current"
systemctl restart homehub

healthy() {
  for _ in $(seq 1 45); do
    if curl -fsS "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

if healthy; then
  echo "HomeHub $VERSION is up."
  # Keep the five newest releases, and never the one that is running.
  find "$BASE/releases" -mindepth 1 -maxdepth 1 -type d -name 'v*' -printf '%T@ %p\n' \
    | sort -rn | tail -n +6 | cut -d' ' -f2- | grep -vxF "$REL" \
    | xargs -r -d '\n' rm -rf -- || true   # grep finds nothing when there are 5 releases or fewer
  exit 0
fi

echo "Health check failed for $VERSION." >&2
journalctl -u homehub -n 50 --no-pager >&2 || true
if [[ -n "$PREV" && -d "$PREV" && "$PREV" != "$REL" ]]; then
  echo "Rolling back to $PREV" >&2
  ln -sfn "$PREV" "$BASE/current"
  systemctl restart homehub
  rm -rf "$REL"   # don't leave the broken release behind
fi
exit 1
