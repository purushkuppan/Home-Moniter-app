# 5. Alerts on the dashboard and on phones through Home Assistant notify

Date: 2026-11-06 · Status: accepted, superseded in part by [ADR 12](0012-direct-device-connections.md)

> Alerts go out through a `Notifier` interface; Home Assistant notify is kept only for iPhones; see [ADR 12](0012-direct-device-connections.md). The rules part (Kudil decides when to alert) still applies.

## Context
HomeHub records what happens, but someone has to open the dashboard to notice a fridge door left open, a fridge
getting warm or a finished wash. The household wants a phone notification for these. Rules: open source only, no
paid services, small memory footprint. Home Assistant already runs on the hub, and its free Companion app
receives push notifications through Home Assistant's own push relay, even away from home.

## Decision
- HomeHub decides when to alert (`AlertRules`, inside the app). Rules come from settings: per-door open time,
  per-reading temperature limit plus a duration, and machines whose end of cycle is reported.
- Each problem is reported once. When it ends, an "all clear" follows with the same notification tag, so it
  replaces the first notification on the phone.
- Alerts are stored as events (`alert`, `alertCleared`, `notice`), so they appear in the activity list and are
  pushed to open dashboards like any other event. `GET /api/alerts` lists problems still going on, for a banner.
- Phones are reached through Home Assistant's `notify.mobile_app_*` actions (`homehub.alerts.send-to`), over the
  connection and token HomeHub already has. Tapping a notification opens `homehub.alerts.link` (the dashboard).
- After a restart the rules start from the newest stored readings; a problem already reported is not repeated.

## Consequences
- No new dependencies or services; one small background thread, negligible memory.
- Push delivery depends on Home Assistant and its free push relay (rate-limited per phone, far above our needs).
  If sending fails, the alert is still stored and shown on the dashboard.
- Rules live in HomeHub rather than as Home Assistant automations, so they are tested with the rest of the app
  and use the same device names as the dashboard. People who prefer to tune alerts in Home Assistant can leave
  `send-to` empty.
- A washer or dryer stopped by hand mid-cycle is also reported as "finished"; the appliance doesn't say why it
  stopped.
- The dashboard link only opens on the home network; remote access is a separate decision.
