# 7. Offline and connection alerts

Date: 2026-11-06 · Status: accepted (extends ADR 5)

## Context
A stale dashboard looked exactly like a quiet house. Appliances sometimes lose SmartThings, and the hub's Wi-Fi
has dropped once while still "connected". Kudil stores a reading only when it changes, so a fridge holding steady
at 44 °F sends nothing for hours: silence alone can't mean "offline".

## Decision
- **Device offline:** a watched device (`homehub.alerts.offline.[device]`, the part before "/") is offline when
  **all** of its readings are `unavailable` in Home Assistant for the limit (Refrigerator: 30 minutes). One missing
  sensor isn't enough. Any reading coming back ends it with "back online. It was offline for …".
  Washer and dryer are not watched by default, because some machines go offline when switched off.
- **Home Assistant unreachable:** the connector publishes a `HomeAssistantStatus` change when both polling and the
  live WebSocket fail, and when either works again. After `connection-lost-after` (10 minutes) the dashboard shows a
  banner. Phones are reached *through* Home Assistant, so they get only the "Kudil lost Home Assistant for …. Back
  now." message, sent 30 seconds after the reconnect to give Home Assistant time to load its notify actions.
- Short blips (back before the limit) are silent, and nothing is cleared that was never reported.

## Consequences
- No new dependencies; a few more fields in the existing rules, negligible memory.
- **Not covered:** the hub itself losing power or network. Nothing on the hub can send then. That would need an
  outside heartbeat (a self-hosted or free check service that alerts when the hub stops calling in); a separate
  decision if wanted.
- After a restart during a Home Assistant outage, the outage is timed from the restart.
