# HomeHub

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

Any of these can be overridden on the command line, for example
`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--homehub.connectors.fake.enabled=false --homehub.connectors.homeassistant.enabled=true"`.

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
