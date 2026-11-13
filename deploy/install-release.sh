#!/usr/bin/env bash
# Installs a HomeHub release on the hub, restarts the service, and rolls back if the
# health check or the alerts check fails. Runs as root: the GitHub runner (user ghrunner) and
# deploy.ps1 call it through sudo.
# Usage: install-release.sh /path/to/homehub.jar v0.1.0
#
# After the app answers, POST /api/selfcheck proves that alerts still work (#124): a test event is
# stored and reaches the live stream, a pretend leak raises a critical alert (nothing is sent), and
# Home Assistant is connected. When either check fails, the previous release is started again and
# $BASE/data/deploy-rolled-back says why; that release sends the family one phone alert about it.
set -euo pipefail

JAR="${1:?jar path required}"
VERSION="${2:?version required}"
BASE=/opt/homehub
APP=http://127.0.0.1:8080
# INSTALL_RELEASE_TEST: lets deploy/test/install-release-test.sh use a temporary folder. sudo drops
# the environment, so the runner can't set these.
if [[ -n ${INSTALL_RELEASE_TEST:-} ]]; then
  BASE=${HOMEHUB_BASE:?}
fi
HEALTH_URL=$APP/actuator/health
SELFCHECK_URL=$APP/api/selfcheck
SELFCHECK_TRIES=30   # 2 s apart: Home Assistant's connection can take a few seconds after a restart

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
rm -f "$BASE/data/deploy-rolled-back"   # an older failure is no news once a new release goes in
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

# Sets CHECK_FAILED to the names of the failed checks ("rules, home assistant") when it fails.
CHECK_FAILED=""
alerts_work() {
  local body code
  body="$(mktemp)"
  for _ in $(seq 1 "$SELFCHECK_TRIES"); do
    code="$(curl -sS -X POST -o "$body" -w '%{http_code}' "$SELFCHECK_URL" 2>/dev/null || true)"
    if [[ $code == 200 ]] && grep -q '"ok":true' "$body"; then
      rm -f "$body"
      return 0
    fi
    sleep 2
  done
  CHECK_FAILED="$(grep -oE '"name":"[a-z ]+","ok":false' "$body" | sed -E 's/"name":"([a-z ]+)".*/\1/' | paste -sd ',' - | sed 's/,/, /g' || true)"
  [[ -n $CHECK_FAILED ]] || CHECK_FAILED="no answer (HTTP ${code:-none})"
  echo "Alerts check failed: $CHECK_FAILED" >&2
  cat "$body" >&2 || true
  echo >&2
  rm -f "$body"
  return 1
}

# Starts the previous release again and leaves a note, from which that release sends one phone alert.
roll_back() {
  local reason="$1"
  journalctl -u homehub -n 50 --no-pager >&2 || true
  printf '%s %s\n' "$VERSION" "$reason" > "$BASE/data/deploy-rolled-back"
  chown homehub:homehub "$BASE/data/deploy-rolled-back" 2>/dev/null || true
  if [[ -n "$PREV" && -d "$PREV" && "$PREV" != "$REL" ]]; then
    echo "Rolling back to $PREV" >&2
    ln -sfn "$PREV" "$BASE/current"
    systemctl restart homehub
    rm -rf "$REL"   # don't leave the broken release behind
  fi
  exit 1
}

if ! healthy; then
  echo "Health check failed for $VERSION." >&2
  roll_back "Health check failed: the app didn't start."
fi
if ! alerts_work; then
  roll_back "Alerts check failed: $CHECK_FAILED."
fi
echo "HomeHub $VERSION is up, and alerts work."
# Keep the five newest releases, and never the one that is running.
find "$BASE/releases" -mindepth 1 -maxdepth 1 -type d -name 'v*' -printf '%T@ %p\n' \
  | sort -rn | tail -n +6 | cut -d' ' -f2- | grep -vxF "$REL" \
  | xargs -r -d '\n' rm -rf -- || true   # grep finds nothing when there are 5 releases or fewer
exit 0

