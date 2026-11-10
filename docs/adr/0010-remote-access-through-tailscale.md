# 10. Remote access through Tailscale

Date: 2026-11-09 · Status: accepted

## Context
The household wants to open Kudil away from home (office, mobile data) on a phone and a personal laptop. Kudil has
no login yet (#16): anyone who reaches the page sees the home's activity, and controls are protected only by a PIN.
So Kudil must not be reachable from the internet. A router port forward or a public tunnel would do exactly that.

Options considered:
- **Port forward to Kudil:** public; ruled out until #16 (logins and HTTPS).
- **WireGuard on the hub:** fully open source, but needs a router port forward, a dynamic-DNS name, and is often
  blocked on office networks.
- **Cloudflare Tunnel with Access:** a login in front, but needs a domain (paid) and puts a third party in the path of
  every page.
- **Tailscale:** WireGuard-based private network. No router changes, works behind carrier-grade NAT, apps for phones
  and laptops. Clients are open source (BSD-3); the coordination service is Tailscale's free personal plan.

## Decision
- The hub joins a Tailscale network (tailnet) as `kudil`, the app's name; the laptop itself stays `home-hub`.
  `deploy/setup-tailscale.sh` installs Tailscale from its official apt repository. The household's phones and
  laptops join the same tailnet.
- The firewall opens **only Kudil (8080)** on the `tailscale0` interface. Both rules (allow 8080, deny the rest) sit at the
  top of the firewall, because older rules allow some ports (SSH, Home Assistant) from anywhere. Home Assistant, Cockpit and SSH stay
  LAN-only. Nothing is opened on the router.
- Kudil is reached at `http://kudil:8080` from any device on the tailnet. Traffic is end-to-end encrypted
  (WireGuard), so plain HTTP inside the tailnet is acceptable for now.
- Key expiry is disabled for the hub only, so it doesn't drop off the tailnet every 180 days.

## Consequences
- Kudil stays private: reachable from the LAN and from signed-in tailnet devices, never from the internet.
- An outside account (Tailscale) controls who joins the tailnet: protect it with 2FA. The free plan covers up to
  100 devices and 3 users; if that changes, the self-hosted, open-source coordination server Headscale can replace it
  without changing the hub's setup much.
- About 30 MB of memory on the hub for `tailscaled`.
- Each family member's device needs the Tailscale app. A public URL with logins comes with #16, not before.
