# 11. API docs with springdoc-openapi

Date: 2026-11-13 · Status: accepted

## Context
The dashboard, the coming web app (R3) and native app (R7), and Claude Code sessions all use Kudil's `/api/**`
endpoints. Today the only description of them is the controllers' source code, and trying one means writing a curl
command. Some endpoints need the household PIN in the `X-HomeHub-Pin` header; most don't.

The hub has 4 GB of RAM shared with Mosquitto, Home Assistant and other services, and Kudil's heap is capped at 384 MB
(CLAUDE.md rule 5), so a docs library must be small and must not build anything heavy at startup.

## Decision
- Use **springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui`, the 3.x line for Spring Boot 4). It reads the
  Spring MVC controllers and serves an OpenAPI 3.1 spec at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`.
  Apache-2.0; it bundles Swagger UI (Apache-2.0). No service, no cost.
- Only `/api/**` is documented (`springdoc.paths-to-match`), not actuator, the dashboard or `/error`.
- API info: title "Kudil API", version from the build (the same value as `/api/version`).
- The PIN is declared once as an API-key security scheme (`pin`, header `X-HomeHub-Pin`) and applied only to the
  endpoints that check it: `POST /api/controls/unlock`, `POST /api/controls/{entityId}`, `POST /api/alerts/test` and
  `POST /api/devices/{id}`. Swagger UI's Authorize button sends it; the header is not also listed as a parameter.
  No example PIN anywhere.
- Each controller has a short `@Tag`, each endpoint an `@Operation` summary. `GET /api/events/stream` is described as
  `text/event-stream`; Swagger UI can't play a stream, and its description says so.
- **On by default** in dev and on the hub, because Kudil is reachable only from the home network and through
  Tailscale (ADR 10). Off switch: `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false`; both
  pages then answer 404.

## Consequences
- One always-current description of the API, generated from the code, and a way to try endpoints from a browser.
- **Memory:** the spec is built on its first request, not at startup. Measured locally (`-Xmx384m`, heap after a full
  GC, spec requested once): about 22 MB without springdoc, about 27 MB with it (+5 MB), about 24 MB with it switched
  off. Startup grows by about 0.7 s. The jar grows from 37 to 44 MB: springdoc pulls in swagger-core, Jackson 2 (next to
  Boot's Jackson 3) and Hibernate Validator, which springdoc needs to read validation annotations.
- **Exposure until per-person logins (#16):** anyone who can open the dashboard can also read the API description.
  That reveals nothing the dashboard's own JavaScript doesn't, and the PIN-protected endpoints still need the PIN.
  When #16 adds logins (and possibly a public URL), revisit: put the docs behind the login, or switch them off in
  `application-prod.properties`.
- Annotations (`@Tag`, `@Operation`, `@SecurityRequirement`) must be kept up to date as endpoints are added; a test
  checks that every `/api/**` path is listed, has a summary, and that only the PIN-protected endpoints require the PIN.
