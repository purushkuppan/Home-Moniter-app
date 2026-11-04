#!/usr/bin/env bash
# One-time setup on home-hub. Run from the repo folder on the hub:
#   sudo bash deploy/setup-hub.sh
# Safe to run again. It does not remove existing firewall rules.
set -euo pipefail

LAN=192.168.5.0/24
HERE="$(cd "$(dirname "$0")" && pwd)"

# Service user (already created on our hub; created here if missing)
id homehub >/dev/null 2>&1 || useradd --system --home /opt/homehub --shell /usr/sbin/nologin homehub

# Folders
install -d -o homehub -g homehub -m 0755 /opt/homehub /opt/homehub/releases /opt/homehub/data
install -d -o root -g root -m 0755 /opt/homehub/bin
install -d -o root -g root -m 0700 /etc/homehub
[[ -f /etc/homehub/homehub.env ]] || install -o root -g root -m 0600 /dev/null /etc/homehub/homehub.env

# Deploy script, owned by root so the runner cannot change it
install -o root -g root -m 0755 "$HERE/install-release.sh" /opt/homehub/bin/install-release.sh

# systemd service
install -o root -g root -m 0644 "$HERE/homehub.service" /etc/systemd/system/homehub.service
systemctl daemon-reload
systemctl enable homehub

# User for the GitHub Actions runner, allowed to run only the deploy script as root
id ghrunner >/dev/null 2>&1 || useradd --create-home --shell /bin/bash ghrunner
echo "ghrunner ALL=(root) NOPASSWD: /opt/homehub/bin/install-release.sh" > /etc/sudoers.d/homehub-deploy
chmod 0440 /etc/sudoers.d/homehub-deploy
visudo -cf /etc/sudoers.d/homehub-deploy

# Firewall: dashboard and Cockpit only from the home network
ufw allow from "$LAN" to any port 8080 proto tcp
ufw allow from "$LAN" to any port 9090 proto tcp

echo
echo "Done. Next:"
echo " 1. Put secrets in /etc/homehub/homehub.env (sudo nano /etc/homehub/homehub.env)"
echo " 2. Remove the old open rules for 8080 and 9090: sudo ufw status numbered, then sudo ufw delete <number>"
echo " 3. Install the GitHub runner as ghrunner (see deploy/README.md)"
