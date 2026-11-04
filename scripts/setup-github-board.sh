#!/usr/bin/env bash
# Creates labels, release milestones (R1-R7), starter issues and a "HomeHub" project board.
# Safe to run more than once: existing labels, milestones, issues and the project are reused.
#
# Needs the GitHub CLI, logged in with project access:
#   gh auth login
#   gh auth refresh -s project
# Run from Git Bash (Windows) or any bash shell:
#   bash scripts/setup-github-board.sh
set -euo pipefail

OWNER=purushkuppan
REPO="$OWNER/Home-Moniter-app"

# ---------- labels ----------
label() { gh label create "$1" --repo "$REPO" --color "$2" --description "$3" --force >/dev/null; echo "label: $1"; }
label bug        D73A4A "Something is not working"
label connector  1D76DB "Device or cloud connector"
label alert      D93F0B "Alerts and notifications"
label infra      5319E7 "Build, deploy, hub setup"
label security   B60205 "Security and privacy"
label api        0E8A16 "Backend API"
label ui         FBCA04 "Dashboard or app"
label ai         C5DEF5 "AI and insights"
label tech-debt  BFD4F2 "Cleanup and improvements"
label business   F9D0C4 "Product, legal, licensing"
label manual     EDEDED "Done by hand, not code"

# ---------- milestones ----------
milestone() {
  local title="$1" due="$2" desc="$3"
  if gh api "repos/$REPO/milestones?state=all&per_page=100" --jq '.[].title' | grep -Fxq "$title"; then
    echo "milestone exists: $title"
  else
    if [[ -n "$due" ]]; then
      gh api "repos/$REPO/milestones" -f title="$title" -f description="$desc" -f due_on="${due}T23:59:59Z" >/dev/null
    else
      gh api "repos/$REPO/milestones" -f title="$title" -f description="$desc" >/dev/null
    fi
    echo "milestone: $title"
  fi
}
M1="R1 Foundation and alerts"
M2="R2 Sensors, panel and cameras"
M3="R3 Web app"
M4="R4 Control and modes"
M5="R5 Home Brief AI"
M6="R6 Product beta"
M7="R7 Native app and launch"
milestone "$M1" 2026-11-15 "Hub, CI/CD, SmartThings OAuth, appliance alerts on both phones"
milestone "$M2" 2026-12-20 "Zigbee sensors, Qolsys panel (read-only), cameras, latest-value API"
milestone "$M3" 2027-01-31 "Web app (PWA): Home, Timeline, Devices, live view, login"
milestone "$M4" 2027-02-14 "Commands, modes, rules, web push"
milestone "$M5" 2027-02-28 "Daily Home Brief, insights, go/no-go decision"
milestone "$M6" 2027-05-31 "Multi-home, gateway image, beta homes (only if go)"
milestone "$M7" ""         "Native app and paid plan (after revenue)"

# ---------- project board ----------
PROJECT=$(gh project list --owner "$OWNER" --format json --jq '.projects[] | select(.title=="HomeHub") | .number' | head -n1)
if [[ -z "$PROJECT" ]]; then
  PROJECT=$(gh project create --owner "$OWNER" --title "HomeHub" --format json --jq '.number')
  gh project link "$PROJECT" --owner "$OWNER" --repo "$REPO" >/dev/null || true
  echo "project created: #$PROJECT"
else
  echo "project exists: #$PROJECT"
fi

# ---------- issues ----------
# issue "title" "label1,label2" "milestone" <<'EOF' ...body... EOF
issue() {
  local title="$1" labels="$2" ms="$3" body url
  body="$(cat)"
  url=$(gh issue list --repo "$REPO" --state all --limit 300 --json title,url \
        --jq ".[] | select(.title==\"$title\") | .url" | head -n1)
  if [[ -z "$url" ]]; then
    if [[ -n "$ms" ]]; then
      url=$(gh issue create --repo "$REPO" --title "$title" --label "$labels" --milestone "$ms" --body "$body")
    else
      url=$(gh issue create --repo "$REPO" --title "$title" --label "$labels" --body "$body")
    fi
    echo "issue: $title"
  else
    echo "issue exists: $title"
  fi
  gh project item-add "$PROJECT" --owner "$OWNER" --url "$url" >/dev/null 2>&1 || true
}

# R1
issue "SmartThings OAuth with refresh tokens" "connector,security" "$M1" <<'EOF'
Personal access tokens expire after 24 hours. Use an OAuth-In app created with the SmartThings CLI.
- [ ] Create the OAuth-In app (scopes `r:devices:*`, `x:devices:*`, `r:locations:*`); client ID and secret in the password manager only
- [ ] One-time browser authorization, exchange the code for access + refresh tokens
- [ ] Connector refreshes before expiry and saves the new refresh token (file or SQLite readable only by `homehub`)
- [ ] Alert when a refresh fails
- [ ] Fallback if OAuth is impossible: dev-only connector with a daily token

Done when: the hub reads SmartThings for 7 days with no manual token change.
EOF
issue "Alerts: washer or dryer done, fridge door left open, freezer too warm" "alert" "$M1" <<'EOF'
- [ ] Washer or dryer cycle finished
- [ ] Fridge door open longer than 5 minutes
- [ ] Freezer warmer than a threshold (and fridge above 40°F) for 30 minutes
- [ ] Rules covered by tests with recorded sample events

Done when: a week of laundry and fridge alerts arrives with no misses or duplicates.
EOF
issue "Send alerts to both phones (ntfy)" "alert" "$M1" <<'EOF'
Free push notifications to Android and iPhone until the web app has its own push.
- [ ] ntfy topic with a hard-to-guess name (or self-hosted ntfy later)
- [ ] Alert messages short and clear, with the device and time

Done when: both phones receive the alerts above.
EOF
issue "Deploy to the hub: prod profile and systemd service" "infra" "$M1" <<'EOF'
- [ ] Run `sudo bash deploy/setup-hub.sh` on home-hub
- [ ] Secrets in `/etc/homehub/homehub.env`
- [ ] First manual install of the jar with `install-release.sh`
- [ ] Service survives a reboot

Done when: HomeHub runs as the `homehub` user from `/opt/homehub/current` with data in `/opt/homehub/data`.
EOF
issue "CI: build and test every pull request" "infra" "$M1" <<'EOF'
`.github/workflows/ci.yml` runs `./mvnw -B verify` on GitHub's runners.
- [ ] First green run on a pull request
- [ ] Dependabot enabled (`.github/dependabot.yml`)

Done when: every PR shows a passing check before merge.
EOF
issue "CD: self-hosted runner on home-hub deploys version tags" "infra" "$M1" <<'EOF'
- [ ] Install the runner as `ghrunner` with label `home-hub` (see `deploy/README.md`)
- [ ] Push tag `v0.1.0` and watch build, release and deploy succeed
- [ ] Test rollback with a broken build on a test tag

Done when: pushing a tag deploys to the hub and a failed health check rolls back automatically.
EOF
issue "Router: reserve 192.168.5.251 for home-hub" "infra,manual" "$M1" <<'EOF'
DHCP reservation for MAC 50:bb:b5:47:c3:d2 → 192.168.5.251.

Done when: the hub keeps its address after a router restart.
EOF
issue "Firewall: allow dashboard and Cockpit only from the home network" "security,infra" "$M1" <<'EOF'
`deploy/setup-hub.sh` adds rules for 192.168.5.0/24 on ports 8080 and 9090.
- [ ] Remove the old open rules (`sudo ufw status numbered`, `sudo ufw delete <n>`)

Done when: `ufw status` shows 8080 and 9090 open only to 192.168.5.0/24, and SSH still works.
EOF
issue "Remove duplicate fridge-door events" "bug,connector" "$M1" <<'EOF'
The same door change can be logged twice. Merge duplicates (same device, attribute, value and close timestamps).

Done when: a test with repeated readings produces one event.
EOF

# R2
issue "Qolsys IQ Hub connector (read-only)" "connector" "$M2" <<'EOF'
- [ ] Ask Ranger for the installer code or IQ Remote pairing; confirm it doesn't affect monitoring
- [ ] Check how open local access is (MQTT as an IQ Remote, as the community integrations do)
- [ ] Read arm state, front door lock, porch light dimmer, Honeywell T6 Pro thermostats (temperature, humidity, setpoints, mode)

Done when: panel devices appear as events in the log.
EOF
issue "Zigbee sensors through Mosquitto and Zigbee2MQTT" "connector" "$M2" <<'EOF'
- [ ] Zigbee dongle on home-hub, Zigbee2MQTT running as a separate program (GPL, not linked into our code)
- [ ] Leak sensor and smart plug paired
- [ ] MQTT subscriber in HomeHub turns messages into events

Done when: a leak test raises an alert.
EOF
issue "Camera connector (Reolink Home Hub, RTSP/ONVIF)" "connector" "$M2" <<'EOF'
After the cameras are bought (Black Friday).
- [ ] Motion and person events with snapshots
- [ ] Live view stream address for the web app

Done when: a person walking past a camera shows up as an event with a snapshot.
EOF
issue "Shark robot vacuum connector (optional)" "connector" "$M2" <<'EOF'
Unofficial cloud API only; keep it optional so the core never depends on it.

Done when: cleaning, docked and error states appear as events, or we decide to drop it.
EOF
issue "API: latest value per device" "api" "$M2" <<'EOF'
Endpoint returning the current state of every device (for the Home screen), not just the event list.

Done when: the dashboard loads current states from one call.
EOF
issue "Device offline and low-battery events" "connector,alert" "$M2" <<'EOF'
Done when: a device that stops reporting, or reports low battery, creates a warning event.
EOF

# R3
issue "Login for the dashboard and API" "security,ui" "$M3" <<'EOF'
Family accounts before the dashboard is used from phones over Tailscale.

Done when: no endpoint except health works without login.
EOF
issue "Temperature unit option (°F / °C)" "ui" "$M3" <<'EOF'
Done when: the user can switch units and all temperatures follow.
EOF
issue "Delete old events automatically" "tech-debt" "$M3" <<'EOF'
Keep normal events for a set number of days (for example 90), warnings and above longer.

Done when: a daily job removes old events and the database size stays stable.
EOF
issue "Web app (PWA): Home, Timeline, Devices" "ui" "$M3" <<'EOF'
Installable on Android and iPhone home screens; one codebase.

Done when: the family uses it instead of the vendor apps.
EOF

# R4, R5
issue "Modes (Home, Away, Night) and a simple rule editor" "alert" "$M4" <<'EOF'
Done when: rules can depend on the mode and be changed without a code release.
EOF
issue "Daily Home Brief" "ai" "$M5" <<'EOF'
Evening summary written by a local model on the OmniBook (free), from the day's events.

Done when: the brief arrives every evening and the family finds it useful.
EOF

# Backlog / product
issue "CI: fail the build on GPL/AGPL dependencies" "infra,business" "" <<'EOF'
Add an open-source license check to CI so a GPL/AGPL library can't slip into the app.

Done when: CI lists all dependency licenses and fails on GPL/AGPL.
EOF
issue "Before selling: employment agreement, vendor terms, licenses" "business" "$M6" <<'EOF'
- [ ] Fidelity employment agreement (side projects, IP)
- [ ] SmartThings, Qolsys and Shark developer terms
- [ ] License review of all libraries and bundled programs
EOF

echo
echo "Done. Open the board: gh project view $PROJECT --owner $OWNER --web"
echo "Tip: in the project, switch the layout to Board to see To do / In progress / Done columns."
