# 4. Device control through Home Assistant, protected by a household PIN

Date: 2026-11-05 · Status: accepted, superseded in part by [ADR 12](0012-direct-device-connections.md)

> Device control moves to direct connections behind `DeviceConnector`; see [ADR 12](0012-direct-device-connections.md). The PIN rules here still apply.

## Context
Until now HomeHub only watched devices. The household wants to change them from the dashboard too
(fridge and freezer temperature, Power Cool/Freeze, ice maker, washer and dryer start/pause/stop and
cycle options). The dashboard has no login, and anyone on the home Wi-Fi can open it. Home Assistant
already talks to the appliances and has a long-lived token on the hub with full rights.

## Decision
- Send commands through Home Assistant's REST services (`number.set_value`, `switch.turn_on/off`,
  `select.select_option`, `button.press`). No direct appliance or cloud APIs.
- Only entities matching the allowlist `homehub.controls.entities`, in those four domains, can be changed.
  The server checks every value against Home Assistant's own limits (range and step, option list) before
  sending it.
- Changing anything needs a household PIN (`homehub.control.pin`, a secret: systemd credential on the hub).
  Without a PIN of at least 4 characters, controls are view-only. Five wrong PINs lock all controls for
  5 minutes. The PIN travels in a custom header (`X-HomeHub-Pin`), which browsers never add to cross-site
  requests, so other web pages can't send commands. The browser keeps the PIN in memory only and forgets
  it after 10 idle minutes.
- Every successful command is stored as an event (type `command`) and shown in the activity list.

## Consequences
- No new dependencies or services; memory impact is negligible.
- A shared PIN shows *that* something was changed, not *who* changed it. Per-person logins can replace
  the PIN later without changing the command API.
- Anyone who learns the PIN and is on the home network can change devices; choose a PIN that isn't
  obvious and change it if it leaks.
- Some commands depend on the appliance: Samsung washers and dryers only accept a remote start while
  "Remote Control" is switched on at the machine. The dashboard says so and shows Home Assistant's error.
- HomeHub's Home Assistant token can do more than the allowlist permits; the allowlist is enforced by
  HomeHub, so keep the token on the hub only.
