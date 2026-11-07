# HomeHub

Kudil: Family Home Monitor is the product name; HomeHub is the internal code name used in code, packages, the `homehub` service and `homehub.db`.

A small home-appliance monitor. A Spring Boot app reads smart devices through Home Assistant,
stores state changes in SQLite, and shows them on a live web dashboard at http://localhost:8080.

## Requirements

- Java 25
- No Maven install needed: use the wrapper (`mvnw.cmd` on Windows, `./mvnw` on Linux/macOS)

## Run

```powershell
.\mvnw.cmd spring-boot:run
```

Open http://localhost:8080. By default only the fake connector runs, generating sample events.

Run the tests:

```powershell
.\mvnw.cmd test
```

## Configuration

Non-secret settings live in [`src/main/resources/application.properties`](src/main/resources/application.properties):

| Property | Default | Purpose |
|---|---|---|
| `homehub.connectors.enabled` | `true` | Master switch for polling |
| `homehub.connectors.interval` | `30s` | Poll interval (`10s`, `5m`, ...) |
| `homehub.connectors.fake.enabled` | `true` | Sample-data connector |
| `homehub.connectors.homeassistant.enabled` | `false` | Home Assistant connector |
| `homehub.connectors.homeassistant.live` | `true` | Receive Home Assistant changes instantly over its WebSocket API (polling remains as a fallback) |
| `homehub.connectors.homeassistant.include` | doors, temperatures, machine/job states, locks | Home Assistant entity ids to read (comma-separated, `*` wildcard) |
| `homehub.controls.enabled` | `true` | Device controls on the dashboard (still need a PIN, see below) |
| `homehub.controls.entities` | fridge, washer and dryer controls | Home Assistant entities that may be changed (`number`, `switch`, `select`, `button` only) |
| `homehub.alerts.enabled` | `true` | Alerts on the dashboard (and phones, see below) |
| `homehub.alerts.send-to` | empty (prod: the household phone) | Home Assistant notify actions, comma-separated, e.g. `notify.mobile_app_phone` |
| `homehub.alerts.link` | `http://localhost:8080` (prod: the hub) | Page opened when a notification is tapped |
| `homehub.alerts.door-open.[device]` | Fridge `1m`, Freezer `2m` | How long a door may stay open |
| `homehub.alerts.too-warm.[device]` | Fridge `45`, Freezer `10` | Highest normal temperature (in the reading's unit) |
| `homehub.alerts.too-warm-for` | `15m` | How long a temperature must stay too high |
| `homehub.alerts.finished` | `Washer,Dryer` | Machines to report when their cycle ends |

Any of these can be overridden on the command line, for example
`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--homehub.connectors.fake.enabled=false --homehub.connectors.homeassistant.enabled=true"`.

## Alerts

HomeHub warns when something needs attention. Details and trade-offs: [ADR 5](docs/adr/0005-alerts-through-home-assistant-notify.md).

| Alert | When | Afterwards |
|---|---|---|
| Door left open | Fridge door open 1 minute, freezer door 2 minutes | "Fridge door closed. It was open for 3 min." |
| Too warm | Fridge above 45 °F or freezer above 10 °F for 15 minutes | "Fridge temperature is back to normal" |
| Cycle finished | Washer or dryer ends its cycle | – |

- **Dashboard:** a red banner shows problems still going on. Every alert is listed in the activity list
  (Alert, All clear, Notice).
- **Phone:** install the free **Home Assistant** Companion app, log in to your Home Assistant, allow notifications,
  then add its notify action (Home Assistant: Settings › Tools › Actions, search `mobile_app`) to
  `homehub.alerts.send-to`. Tapping a notification opens the dashboard (on the home network). The "all clear"
  replaces the earlier notification.
- A door that was already reported is not reported again after a restart.

## Secrets

Tokens and other secrets are **never** stored in the project. They are read from configuration
outside the repository, through this line in `application.properties`:

```properties
spring.config.import=optional:file:${user.home}/.homehub/secrets.properties,optional:configtree:${CREDENTIALS_DIRECTORY:/nonexistent}/
```

Both sources are optional: the app starts without them, and a connector that needs a missing
secret logs a warning instead of failing.

### Secret properties

| Property | Used by | Environment variable fallback |
|---|---|---|
| `homeassistant.url` | Home Assistant connector | `HOMEASSISTANT_URL` |
| `homeassistant.token` | Home Assistant connector (long-lived access token) | `HOMEASSISTANT_TOKEN` |
| `homehub.control.pin` | Device controls (at least 4 characters; without it controls are view-only) | `HOMEHUB_CONTROL_PIN` |

## Device controls

The **Controls** section of the dashboard changes devices through Home Assistant: fridge and freezer
temperature, Power Cool/Freeze, ice maker, Sabbath mode, water-filter reset, washer and dryer
start/pause/stop and washer cycle options. Details and trade-offs: [ADR 4](docs/adr/0004-device-control-through-home-assistant.md).

- **Unlock** with the household PIN (`homehub.control.pin`). It stays unlocked for 10 minutes of activity.
  Five wrong PINs lock controls for 5 minutes.
- Every change asks for confirmation and is recorded in the activity list as a **Command**.
- Only entities in `homehub.controls.entities` can be changed, within Home Assistant's own limits.
- Samsung washers and dryers only start remotely while **Remote Control** is on at the machine.

### Option 1: secrets file in your home folder (local machine)

1. Create the folder `.homehub` in your home folder:
   - Windows: `%USERPROFILE%\.homehub\` (for example `C:\Users\you\.homehub\`)
   - Linux/macOS: `~/.homehub/`
2. Copy [`secrets.properties.example`](secrets.properties.example) there as `secrets.properties`.
3. Replace the placeholders with your real values and delete any lines you don't use.

```powershell
New-Item -ItemType Directory -Force "$HOME\.homehub" | Out-Null
Copy-Item secrets.properties.example "$HOME\.homehub\secrets.properties"
notepad "$HOME\.homehub\secrets.properties"
```

On Linux/macOS, also restrict the file to your user: `chmod 600 ~/.homehub/secrets.properties`.

### Option 2: systemd credentials (server)

When the app runs as a systemd service, systemd sets `CREDENTIALS_DIRECTORY` and places one file
per credential in it. Spring reads each file as a property named after the file, so name the
credentials after the properties:

```ini
[Service]
LoadCredential=homeassistant.token:/etc/homehub/credentials/homeassistant.token
```

Each file contains only the value, with no `key=` prefix.

### Option 3: environment variables (fallback)

The environment variables in the table above still work, so existing setups keep running.

### Which value wins

If the same secret is set in more than one place, the highest one in this list is used:

1. Command-line arguments
2. Environment variables
3. systemd credentials (`CREDENTIALS_DIRECTORY`)
4. `~/.homehub/secrets.properties`

Environment variables outrank the files (standard Spring Boot order). After moving a secret into
a file, remove the old environment variable so a stale value doesn't take over.

### Keeping secrets safe

- `.gitignore` excludes `.homehub/`, `secrets.properties` and `*.secrets.properties` anywhere in
  the repo. Only `secrets.properties.example`, with placeholders, is committed.
- Tokens are never written to the logs. Log messages name the property (for example
  `homeassistant.token`), never its value, and the Home Assistant settings print the token as `******`.
- Only the `health` and `info` Actuator endpoints are exposed, so configuration values are not
  reachable over HTTP.
- If a token is ever committed by mistake, revoke it at the provider and create a new one.
  Removing it from git history alone is not enough.
