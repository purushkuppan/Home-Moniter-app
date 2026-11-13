# 12. Kudil talks to devices directly; Home Assistant only delivers iPhone alerts

Date: 2026-11-11 · Status: accepted · Supersedes in part: [ADR 4](0004-device-control-through-home-assistant.md),
[ADR 5](0005-alerts-through-home-assistant-notify.md)

Decided Nov 11, 2026. Replaces the Nov 10 proposal that kept Home Assistant as a hidden engine. Revisit before selling
(R6). The design doc calls this "ADR 11"; that number went to the API docs (ADR 11) first, so it is ADR 12 here.

## Context
Home Assistant is today Kudil's only path to devices and to phone alerts. If it breaks, changes or goes away, Kudil
goes blind. A product can't ask buyers to set up Home Assistant first. Samsung's Dec 15 API paywall showed that vendor
clouds can change the rules at any time, whatever engine sits in the middle. Home Assistant itself is Apache-2.0 and
owned by a non-profit, so the risk is control and testing, not licence.

## Decision
1. Kudil owns every device connection, rule, alert decision, event log and screen. Development and testing use Kudil
   alone.
2. Each protocol uses a standalone open-source server or a vendor's local API, behind `DeviceConnector`: Z-Wave through
   the Z-Wave JS server (MIT, WebSocket); Shelly through its local HTTP/MQTT; Reolink through its local HTTP API and
   ONVIF events; Zigbee through Zigbee2MQTT (GPL, separate process); leak sensors through rtl_433; Matter through the
   Open Home Foundation Matter Server (Apache-2.0), on our own fabric.
3. Alerts go out through a `Notifier` interface. Android phones get alerts from Kudil: ntfy now (high priority,
   overrides Do Not Disturb), then our own sideloaded Kudil Android app that also speaks critical alerts on the alarm
   volume. No Play Store fee is needed for our own phones.
4. Home Assistant stays on the hub for one job: delivering alerts to iPhones through its Companion app, which can send
   iOS critical alerts that ring through silent mode. Kudil calls `notify.mobile_app_*`; Home Assistant has no device
   integrations and no automations.
5. Before selling, revisit iPhone alerts: our own iOS app with Apple's critical-alert entitlement needs the paid Apple
   Developer account.
6. Engine versions (Z-Wave JS, Matter server, Zigbee2MQTT, Home Assistant) are pinned and moved forward only after
   contract tests pass.

## Consequences
- Done test: with the Home Assistant container stopped, Kudil still logs events, runs rules, controls devices and
  alerts Android phones.
- If Home Assistant is down, iPhone users still get the alert on the dashboard and as a normal ntfy push, without
  ring-through-silent.
- More Java connectors to write and test: Z-Wave JS client, Shelly, Reolink, later Matter. Kudil's own Add-device
  screen replaces Home Assistant's setup flows.
- Home Assistant needs only a token that can call notify; device tokens and the admin onboarding token are no longer
  needed.
- Samsung appliances stop on Dec 15 either way; energy-monitoring plugs and a fridge temperature sensor replace them
  (#132). Blink and other cloud-only brands are dropped unless we write a connector later.
- Less memory used on the 4 GB hub once Home Assistant runs only notify.
- Until the direct connectors land (#134, #156, #157, #158) and the `Notifier` interface exists (#154), today's code
  still reads devices and sends every alert through Home Assistant. New code follows this ADR; existing code moves
  over issue by issue.

## Options considered

| Option | Effort | Robustness | Device coverage |
|---|---|---|---|
| Everything through Home Assistant | Lowest | Every monthly release can change what Kudil reads | Widest |
| Home Assistant as hidden engine (Nov 10 proposal) | Medium | Core sensors survive a Home Assistant problem; still depends on it for most devices | Widest |
| **Direct for all devices, Home Assistant only for iPhone alerts (chosen)** | Medium to high, spread over R2–R3 | Kudil works without Home Assistant; testing covers the real path | The brands we support, plus Matter |
| Fully direct, no Home Assistant at all | Same, plus a native iOS app | Best | Same; iPhone alerts can't ring through silent until the iOS app |
