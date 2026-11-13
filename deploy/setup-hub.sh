#!/usr/bin/env bash
# One-time setup on home-hub. Run from the repo folder on the hub:
#   sudo bash deploy/setup-hub.sh
# Safe to run again. The firewall step removes rules that opened 8080, 9090 or 8123 to anyone (setup-firewall.sh).
set -euo pipefail

LAN=192.168.4.0/22   # whole home network: 192.168.4.0 - 192.168.7.255 (PC, phones and hub)
HERE="$(cd "$(dirname "$0")" && pwd)"

# Service user (already created on our hub; created here if missing)
id homehub >/dev/null 2>&1 || useradd --system --home /opt/homehub --shell /usr/sbin/nologin homehub

# Folders
install -d -o homehub -g homehub -m 0755 /opt/homehub /opt/homehub/releases /opt/homehub/data
install -d -o root -g root -m 0755 /opt/homehub/bin
install -d -o root -g root -m 0700 /etc/homehub
[[ -f /etc/homehub/homehub.env ]] || install -o root -g root -m 0600 /dev/null /etc/homehub/homehub.env
# systemd credentials (LoadCredential= in homehub.service). The service won't start if a listed file is
# missing, so create an empty one; fill it in afterwards (see deploy/README.md).
install -d -o root -g root -m 0700 /etc/homehub/credentials
[[ -f /etc/homehub/credentials/homeassistant.token ]] || install -o root -g root -m 0600 /dev/null /etc/homehub/credentials/homeassistant.token
[[ -f /etc/homehub/credentials/homehub.control.pin ]] || install -o root -g root -m 0600 /dev/null /etc/homehub/credentials/homehub.control.pin
for ntfy in homehub.ntfy.topic homehub.ntfy.token; do   # filled in by setup-ntfy.sh
    [[ -f /etc/homehub/credentials/$ntfy ]] || install -o root -g root -m 0600 /dev/null "/etc/homehub/credentials/$ntfy"
done
# The dead-man switch's ping URL (#102), filled in by hand (deploy/README.md, Dead-man switch)
[[ -f /etc/homehub/credentials/homehub.deadman.ping-url ]] || install -o root -g root -m 0600 /dev/null /etc/homehub/credentials/homehub.deadman.ping-url

# Deploy script, owned by root so the runner cannot change it
install -o root -g root -m 0755 "$HERE/install-release.sh" /opt/homehub/bin/install-release.sh

# systemd service
install -o root -g root -m 0644 "$HERE/homehub.service" /etc/systemd/system/homehub.service
systemctl daemon-reload
systemctl enable homehub

# Nightly backup at 03:00 to /var/backups/homehub (see backup.sh). sqlite3 makes safe copies of live databases.
command -v sqlite3 >/dev/null || apt-get install -y sqlite3
install -o root -g root -m 0755 "$HERE/backup.sh" /opt/homehub/bin/backup.sh
install -d -o root -g root -m 0700 /var/backups/homehub
install -d -o root -g root -m 0755 /var/lib/homehub-backup
[[ -f /etc/homehub/backup.env ]] || install -o root -g root -m 0600 /dev/null /etc/homehub/backup.env
install -o root -g root -m 0644 "$HERE/homehub-backup.service" /etc/systemd/system/homehub-backup.service
install -o root -g root -m 0644 "$HERE/homehub-backup.timer" /etc/systemd/system/homehub-backup.timer
systemctl daemon-reload
systemctl enable --now homehub-backup.timer

# User for the GitHub Actions runner, allowed to run only the deploy script as root
id ghrunner >/dev/null 2>&1 || useradd --create-home --shell /bin/bash ghrunner
echo "ghrunner ALL=(root) NOPASSWD: /opt/homehub/bin/install-release.sh" > /etc/sudoers.d/homehub-deploy
chmod 0440 /etc/sudoers.d/homehub-deploy
visudo -cf /etc/sudoers.d/homehub-deploy

# Firewall: Kudil, Cockpit and Home Assistant only from the home network (removes rules that opened them to anyone)
LAN="$LAN" bash "$HERE/setup-firewall.sh"

echo
echo "Done. Next:"
echo " 1. Put the Home Assistant token in /etc/homehub/credentials/homeassistant.token (see deploy/README.md)"
echo "    and any other secrets in /etc/homehub/homehub.env (sudo nano /etc/homehub/homehub.env)"
echo " 2. Make a first backup now: sudo systemctl start homehub-backup, then ls -l /var/backups/homehub"
echo " 3. Install the GitHub runner for automatic deploys: sudo bash setup-runner.sh (see deploy/README.md)"
