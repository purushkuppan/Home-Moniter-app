# HomeHub – instructions for AI agents

Kudil: Family Home Monitor is the product name; HomeHub is the internal code name used in code, packages, the `homehub` service and `homehub.db`.

HomeHub is a home monitoring app: connectors poll smart devices, events are stored in SQLite,
and a live dashboard shows them with alerts. It runs on a 4 GB always-on laptop (`home-hub`,
Ubuntu Server 26.04) and may become a product later.

## Stack
- Java 25 (Eclipse Temurin / OpenJDK), Spring Boot 4.1, Maven wrapper (`./mvnw`)
- SQLite via JDBC (`spring-boot-starter-jdbc`, `sqlite-jdbc`), single connection pool
- Static dashboard in `src/main/resources/static`
- Connectors implement `DeviceConnector` and are scheduled by `ConnectorScheduler`

## Commands
- Release: every merge to `main` is released and deployed automatically (ADR 9). GitHub Actions picks the next
  version (`feat:` → next minor, otherwise next patch), builds it, tags it, and the self-hosted runner on
  `home-hub` deploys it with a health check and rollback. A pushed `vX.Y.Z` tag also releases.
  Fallback: `.\deploy.ps1 -Version vX.Y.Z`.
  The repository must stay **private** while a self-hosted runner is attached.
- Build and test: `./mvnw -B verify` (Windows: `mvnw.cmd -B verify`)
- Run locally: `./mvnw spring-boot:run` → http://localhost:8080
- Production profile: `--spring.profiles.active=prod` (data in `/opt/homehub/data`)

## Rules
1. Every change starts from a GitHub issue. Branch name: `<issue-number>-short-name`.
   Open a pull request that says `Fixes #<issue>`, and fill in the PR template.
2. Add or update tests for every behavior change. `./mvnw -B verify` must pass before a PR.
3. Never commit secrets (tokens, client secrets, passwords). Read them from environment
   variables or files outside the repo. Do not print them in logs.
4. Open source only, no paid services. Do not add GPL or AGPL libraries to the app;
   prefer Apache-2.0, MIT, BSD or EPL. Mention any new dependency and its license in the PR.
5. Keep memory small: the hub has 4 GB RAM shared with Mosquitto and other services
   (JVM heap is capped at 384 MB). No heavy frameworks or extra databases without an ADR.
6. Record architecture decisions in `docs/adr/` (short: context, decision, consequences).
7. Small, focused PRs. Commit messages in the form `feat:`, `fix:`, `chore:`, `docs:`, `test:`.
8. Do not change `deploy/` scripts or the systemd unit without saying so clearly in the PR,
   because they run on the live hub with root privileges.
9. User-facing text (dashboard, notifications, messages) says **Kudil**. Code identifiers stay HomeHub
   (packages, classes, properties, the `X-HomeHub-Pin` header, the `homehub` service and `homehub.db`).
   Write that text by `docs/writing-guide.md`: plain words, one vocabulary, what happened, how long, what to do.

## Definition of done
Tests pass in CI, the PR is reviewed and merged, the release is deployed to `home-hub`,
it has run at home without issues, and the issue is closed.
