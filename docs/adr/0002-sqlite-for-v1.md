# 2. SQLite for v1

Date: 2026-11-03 · Status: accepted · Updated 2026-11-08: Flyway migrations and WAL mode (#47)

## Context
One home produces a few thousand events a day, and the hub has 4 GB RAM.

## Decision
Use SQLite (single file in `/opt/homehub/data`) for v1. Use plain SQL and schema migrations
so the move to PostgreSQL for the multi-home product is a configuration change plus migration.

- **Migrations with Flyway** (`flyway-core`, Apache-2.0, through Spring Boot's auto-configuration). Every schema
  change is a versioned SQL file in `src/main/resources/db/migration` (`V1__events.sql`, `V2__...`); the app never
  creates or changes tables in Java code. Flyway runs them at startup and records them in `flyway_schema_history`.
  Databases made before Flyway (the live `homehub.db`) are baselined at version 1 (`baseline-on-migrate`), so V1
  is not run on them.
- **Portable SQL where it's cheap.** V1 is the existing SQLite table as it was (`INTEGER PRIMARY KEY
  AUTOINCREMENT`). New migrations use types that PostgreSQL also has (`TEXT`, `INTEGER`, `BIGINT`, ...), no
  SQLite-only features.
- **WAL journal mode** (`journal_mode=WAL`, `synchronous=NORMAL`), set as driver properties on every connection in
  `application.properties`. Readers (dashboard) and the writer (events) no longer block each other, and
  `NORMAL` is safe with WAL: a power cut can lose the last moments of events, not corrupt the file.

## Consequences
Almost no memory or operations overhead now. One writer at a time (pool size 1), which is
enough for one home. Revisit when the product beta starts.

- The switch to PostgreSQL = a new datasource plus the same migrations. Only V1 needs a PostgreSQL version
  (`BIGSERIAL` id), e.g. in a vendor folder (`db/migration/{vendor}`), with the data copied across once.
- Flyway adds a small jar and a few hundred milliseconds at startup; no extra service.
- Next to `homehub.db` there are now `homehub.db-wal` and `homehub.db-shm`. They belong to the database: they
  must be owned by the `homehub` user, and backups must use `sqlite3 homehub.db ".backup ..."` or
  `VACUUM INTO`, never a plain copy of `homehub.db` alone.
- A released migration is never edited; fixes go into a new version.
