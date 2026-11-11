#!/usr/bin/env bash
# Automatic updates on the hub (#33). Run once on the hub, and again whenever this file changes:
#   sudo bash setup-updates.sh
# Safe to run again.
#
# Ubuntu's security updates already install on their own (unattended-upgrades). This adds:
#  - Tailscale's updates (its own package repository, which unattended-upgrades skips by default)
#  - removing old kernels and packages nothing needs any more, so the disk doesn't fill up
#  - a restart at 04:00 when an update needs one (a new kernel), after the 03:00 backup; Kudil, Home Assistant and
#    Tailscale start again on their own
# Home Assistant is not a system package: update it once a month with update-home-assistant.sh.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Run it with sudo: sudo bash $0" >&2; exit 1; }
REBOOT_TIME=${REBOOT_TIME:-04:00}
[[ $REBOOT_TIME =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || { echo "REBOOT_TIME must look like 04:00" >&2; exit 1; }

dpkg -s unattended-upgrades >/dev/null 2>&1 || apt-get install -y unattended-upgrades

# Turn on the daily package list refresh and unattended upgrade (Ubuntu's default, written here in case it was changed).
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

# Our additions in a file of their own; Ubuntu's 50unattended-upgrades stays as the package ships it.
cat > /etc/apt/apt.conf.d/52kudil-unattended-upgrades <<EOF
// Kudil hub: written by deploy/setup-updates.sh. Ubuntu's own security updates come from 50unattended-upgrades.
Unattended-Upgrade::Origins-Pattern {
    "origin=Tailscale";
};
Unattended-Upgrade::Remove-Unused-Kernel-Packages "true";
Unattended-Upgrade::Remove-New-Unused-Dependencies "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-WithUsers "true";
Unattended-Upgrade::Automatic-Reboot-Time "$REBOOT_TIME";
EOF

systemctl enable --now apt-daily.timer apt-daily-upgrade.timer >/dev/null

# Everything that must come back after a restart.
for unit in homehub.service docker.service tailscaled.service homehub-backup.timer; do
    if systemctl list-unit-files "$unit" >/dev/null 2>&1 && [[ $(systemctl is-enabled "$unit" 2>/dev/null) != enabled ]]; then
        echo "Warning: $unit does not start on its own after a restart: sudo systemctl enable $unit"
    fi
done
if command -v docker >/dev/null && docker inspect homeassistant >/dev/null 2>&1; then
    policy=$(docker inspect homeassistant --format '{{.HostConfig.RestartPolicy.Name}}')
    [[ $policy == unless-stopped || $policy == always ]] \
        || echo "Warning: the Home Assistant container does not start after a restart (restart policy '$policy')"
fi

echo
echo "Sources unattended-upgrades will update from:"
unattended-upgrade --dry-run --debug 2>&1 | grep -iE "^Allowed origins" || true
echo
echo "Done. Security updates and Tailscale install every day by themselves (apt-daily-upgrade.timer, around 06:00)."
echo "When one needs a restart, the hub restarts at $REBOOT_TIME. Check what ran: cat /var/log/unattended-upgrades/unattended-upgrades.log"
