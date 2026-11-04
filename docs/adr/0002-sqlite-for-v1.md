# 2. SQLite for v1

Date: 2026-11-03 · Status: accepted

## Context
One home produces a few thousand events a day, and the hub has 4 GB RAM.

## Decision
Use SQLite (single file in `/opt/homehub/data`) for v1. Use plain SQL and schema migrations
so the move to PostgreSQL for the multi-home product is a configuration change plus migration.

## Consequences
Almost no memory or operations overhead now. One writer at a time (pool size 1), which is
enough for one home. Revisit when the product beta starts.
