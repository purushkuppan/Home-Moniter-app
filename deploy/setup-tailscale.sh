#!/usr/bin/env bash
# Remote access to Kudil through Tailscale (ADR 10). Run once on the hub:
#   sudo bash setup-tailscale.sh
# Safe to run again.
#
# Joins the hub to your Tailscale network (tailnet) as "kudil". Only your own signed-in devices can reach it;
# nothing is opened on the router or to the internet. The firewall lets Tailscale devices reach Kudil (8080) only.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Run it with sudo: sudo bash $0" >&2; exit 1; }
HOSTNAME_ON_TAILNET=kudil   # the app's name, so the address is http://kudil:8080
KUDIL_PORT=8080

if ! command -v tailscale >/dev/null; then
    # Tailscale's official package repository for this Ubuntu release, with its signing key (BSD-3 client).
    # shellcheck source=/dev/null
    . /etc/os-release
    install -d -m 0755 /usr/share/keyrings
    curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/${VERSION_CODENAME}.noarmor.gpg" \
        -o /usr/share/keyrings/tailscale-archive-keyring.gpg
    curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/${VERSION_CODENAME}.tailscale-keyring.list" \
        -o /etc/apt/sources.list.d/tailscale.list
    apt-get update
    apt-get install -y tailscale
fi
systemctl enable --now tailscaled

# Join the tailnet. The first time, this prints a link: open it on your phone or PC and sign in.
# --ssh=false: SSH stays as it is (LAN only); Tailscale doesn't take it over.
# --netfilter-mode=nodivert: by default Tailscale puts its own "accept everything from the tailnet" rule in front of
#   the firewall, so ufw's rules below would never be reached and SSH, Home Assistant and Cockpit would be open to every
#   tailnet device. With nodivert, Tailscale traffic goes through ufw like any other.
tailscale up --hostname="$HOSTNAME_ON_TAILNET" --ssh=false --netfilter-mode=nodivert

# Tailscale's own connection port, so devices at home connect to the hub directly instead of through a relay.
# (Tailscale opened it itself before nodivert. The router doesn't forward it, so it's reachable from home only.)
ufw allow 41641/udp comment 'Tailscale direct connections'

# Kudil from your Tailscale devices, and nothing else (Home Assistant, Cockpit, SSH stay home-network only).
# ufw stops at the first matching rule, and older rules may allow ports such as 22 or 8123 from anywhere, so these two
# go at the very top. Removed first, so running the script again puts them back on top instead of skipping them.
ufw delete allow in on tailscale0 to any port "$KUDIL_PORT" proto tcp >/dev/null 2>&1 || true
ufw delete deny in on tailscale0 >/dev/null 2>&1 || true
ufw prepend deny in on tailscale0 comment 'Tailscale: nothing but Kudil'
ufw prepend allow in on tailscale0 to any port "$KUDIL_PORT" proto tcp comment 'Kudil over Tailscale'

ip4=$(tailscale ip -4 | head -n 1)
echo
echo "Done. The hub is on your tailnet as $HOSTNAME_ON_TAILNET ($ip4)."
echo "Next:"
echo " 1. Install the Tailscale app on your phone and laptop and sign in with the same account."
echo " 2. Open http://$HOSTNAME_ON_TAILNET:$KUDIL_PORT (or http://$ip4:$KUDIL_PORT) from anywhere."
echo " 3. In the Tailscale admin console (login.tailscale.com/admin/machines), open $HOSTNAME_ON_TAILNET's menu and"
echo "    choose 'Disable key expiry', so the hub doesn't drop off the tailnet every 180 days."
