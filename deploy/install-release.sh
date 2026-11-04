#!/usr/bin/env bash
# Installs a HomeHub release on the hub, restarts the service, and rolls back if the
# health check fails. Runs as root (the deploy runner calls it through a sudoers rule).
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
if [[ ! -f "$JAR" ]]; then
  echo "Jar not found: $JAR" >&2
  exit 2
fi

REL="$BASE/releases/$VERSION"
PREV="$(readlink -f "$BASE/current" 2>/dev/null || true)"

install -d -o homehub -g homehub -m 0755 "$REL"
install -o homehub -g homehub -m 0644 "$JAR" "$REL/homehub.jar"
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
  # Keep the five newest releases.
  ls -1dt "$BASE"/releases/v* | tail -n +6 | xargs -r rm -rf
  exit 0
fi

echo "Health check failed for $VERSION." >&2
journalctl -u homehub -n 50 --no-pager >&2 || true
if [[ -n "$PREV" && -d "$PREV" && "$PREV" != "$REL" ]]; then
  echo "Rolling back to $PREV" >&2
  ln -sfn "$PREV" "$BASE/current"
  systemctl restart homehub
fi
exit 1
