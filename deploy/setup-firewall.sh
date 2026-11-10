#!/usr/bin/env bash
# Firewall for the hub (#8). Run on the hub, and again whenever this file changes:
#   sudo bash setup-firewall.sh
# Safe to run again. setup-hub.sh runs it too.
#
# Kudil (8080), Cockpit (9090) and Home Assistant (8123) can be reached from the home network only. The old rules that
# opened them to anyone are removed. SSH stays as it is, and the Tailscale rules from setup-tailscale.sh (Kudil only,
# at the top of the list) are left alone.
set -euo pipefail

# FIREWALL_TEST: lets deploy/test/firewall-test.sh run this with a stand-in ufw; ufw itself still needs root.
[[ $EUID -eq 0 || -n ${FIREWALL_TEST:-} ]] || { echo "Run it with sudo: sudo bash $0" >&2; exit 1; }
LAN=${LAN:-192.168.4.0/22}   # whole home network: 192.168.4.0 - 192.168.7.255 (PC, phones and hub)
SERVICES=("8080 Kudil" "9090 Cockpit" "8123 Home Assistant")

# SSH first, so the session running this script is never cut off.
ufw allow OpenSSH >/dev/null

# 1. The home network may reach each service. Added before anything is removed, so nothing is closed to you midway.
for service in "${SERVICES[@]}"; do
    port=${service%% *}
    ufw allow from "$LAN" to any port "$port" proto tcp comment "${service#* } from the home network"
done

# 2. Remove the old rules that opened these ports to anyone, IPv4 and IPv6. ufw deletes only the exact rule named,
#    so the home-network rules above and the Tailscale rules (which name an interface) stay.
for service in "${SERVICES[@]}"; do
    port=${service%% *}
    ufw delete allow "$port/tcp" >/dev/null 2>&1 || true
    ufw delete allow "$port" >/dev/null 2>&1 || true   # in case it was once added without /tcp
done

echo
if ! ufw status | grep -q '^Status: active'; then
    echo "Note: the firewall is off. Turn it on with: sudo ufw enable"
fi
ufw status numbered
echo
echo "Done: Kudil, Cockpit and Home Assistant are open to the home network ($LAN) only."
echo "Check from a phone on home Wi-Fi: http://192.168.5.251:8080 (Kudil) and http://192.168.5.251:8123 (Home Assistant)."
echo "Away from home, Kudil still opens through Tailscale: http://kudil:8080"
