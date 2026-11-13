# Writing guide for screens and notifications

How Kudil talks to the family: on the dashboard, in phone notifications and in spoken alerts. Everyone in the house
reads these, from the kids to the grandparents, often in a hurry or half asleep. Check new text against this page.

## The basics
- **Plain words.** Write what a neighbour would say: "The back door is open", not "contact sensor state changed".
- **Sentence case** for everything: titles, buttons, chips. "Add device", not "Add Device".
- **Present tense, active voice.** "Kudil lost Home Assistant", "The washer finished".
- **Kudil, never HomeHub** in anything people see (CLAUDE.md rule 9). HomeHub stays in code, properties, the
  `X-HomeHub-Pin` header, the `homehub` service and `homehub.db`.
- **No entity IDs, protocol or vendor jargon.** Not `binary_sensor.back_door_contact`, not "Z-Wave node 7", not
  "MQTT", not "integration". Name the device as the family named it ("Back door", "Fridge").
- **Short.** One idea per sentence. If a sentence needs a comma and an "and", it is probably two sentences.
- **No blame, no alarm words.** "Couldn't reach the fridge" rather than "Error: request failed". Save "now" and
  "Get everyone out" for real emergencies.

## One vocabulary
Use these words, and only these, for device states:

| State | Say | Never |
|---|---|---|
| Door, window, garage | Open / Closed | opened state, on/off, contact |
| Lock | Locked / Unlocked / Jammed | secured, unsecured |
| Switch, plug, light | On / Off | enabled, active |
| Washer, dryer | Running / Paused / Finished | run, job state, cycle complete |
| Leak sensor | Wet / Dry | moisture detected |
| A device that stopped answering | Offline | unavailable, unreachable, dead |
| One reading with no value right now | Not reporting | unavailable, null, NaN |
| Battery | Low / Fine | critical, depleted |

"Unavailable" is Home Assistant's word; Kudil translates it before anything reaches a screen.

## Notifications
Every alert answers three questions, in this order:

1. **What happened, and where.** "Water leak in the kitchen", "Fridge door has been open".
2. **How long or how much.** "for 12 minutes", "47 °F (above 45 °F for 30 minutes)", "battery 15%".
3. **What to do.** One action first: "Turn off the water at the main valve, then dry the floor."

Example: "Freezer is too warm: 12 °F (above 5 °F for 25 minutes)" with the next step "Check that the freezer door is
shut and the fan runs."

- The **title** is "Kudil" today. If a title ever carries the message, keep it under 40 characters so the lock screen
  doesn't cut it.
- The **next step** lives in one place, `NextSteps` (#161), one per alert kind. It goes on its own line under the
  message on phones, after the message when spoken, and under the alert on the dashboard. Don't repeat it inside the
  message.
- An **all clear** says what is fine again and how long the problem lasted: "Fridge door closed. It was open for
  3 min." No next step.
- Plain **news** (a finished wash, the daily brief) is short and friendly, with no exclamation marks.

## Severity and how loud a phone gets
The level decides the phone's interruption level. Never raise a level to get attention: if people learn that Kudil
cries wolf, they mute it, and then they miss the leak (Apple's notification guidelines say the same).

| Kudil level | Used for | iPhone | Android |
|---|---|---|---|
| Notice | finished wash, low battery, freeze or rain notice, an open door by day | passive (no sound) | normal |
| Warning (problem) | an open door at night, a warm fridge, an offline device, a missing backup | time sensitive (through Focus) | normal |
| Critical | water leak, smoke, CO, severe weather warnings | critical (rings through silent) | alarm stream, spoken, through Do Not Disturb |

Which kinds are critical is a setting (`homehub.alerts.critical.kinds`), not a wording choice.

## Times and numbers
- **Times** are shown in the home's local time. Within the hour say "12 min ago"; after that the time itself, "at 8:05
  PM", or "on Sun 8:05 PM" (#112). "3 hours ago" makes old look recent. When the home's time zone differs from the
  phone's, name the zone: "7:42 PM IST" (#120).
- **Durations** in words: "less than a minute", "12 minutes", "1 hour 5 minutes". A short opening in an all clear may
  be exact: "45 s", "1 min 50 s".
- **Numbers always carry their unit**, with a space before it: "47 °F", "15%", "1.2 kWh", "850 W".
- **Rounding** (#112): percentages and readings of 10 or more as whole numbers, smaller readings to one decimal,
  energy to one decimal (kWh). Temperatures follow the °F/°C switch on the dashboard.
- A number that may be old says so: "Updated 8:05 PM", or "Not reporting" when there is no value.

## Buttons and confirmations
- Buttons say what happens: "I'm on it", "Unlock", "Send test alert". Not "OK" or "Submit".
- Ask before risky actions only (unlocking, turning off the water), and say what will happen, naming the device
  (#114).
- After an action, confirm briefly: "Done." Show "Sending…" while waiting.

## Review of today's text (Nov 13, 2026)
Checked every alert message in `AlertRules`, `BackupAlerts`, `SmartThingsWatchdog`, `WeatherAlerts`,
`WarningService` and the brief, and the dashboard's status card, appliance cards and controls.

| Where | Follows the guide? | Note |
|---|---|---|
| Open door, too warm, leak, smoke, CO, lock, battery, offline | Yes | what, how long or how much; next step from `NextSteps` (#161, PR #173) |
| Home Assistant lost / back | Yes | |
| Backup missing / working again | Yes | |
| Weather freeze and rain notices, official warnings | Yes | advice moves to `NextSteps` in #161 |
| SmartThings silent (watchdog) | Mostly | names "SmartThings" and "Home Assistant (Settings › Devices & services)": fine for now, since only the household admin acts on it; goes away with ADR 12 |
| Dashboard states | Yes | "Offline" for a device, "Not reporting" for one reading; "unavailable" is never shown |
| Dashboard and phones | Yes | say Kudil; HomeHub appears only in code and the PIN header |

Follow-ups: the family check at every release (#119) is the real test of whether the words work, and the Tamil
translation (#120) should keep these rules.
