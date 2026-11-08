# 9. Deploy on every merge to main

Date: 2026-11-08 · Status: accepted · Changes: [ADR 6](0006-automatic-deploys-with-a-self-hosted-runner.md)

## Context
Since ADR 6 a release needed a version tag pushed after the merge. That step was easy to forget: five merged
pull requests sat on `main` while the hub still ran v0.4.1. The dashboard also couldn't show which version was
running, because every jar was built as `0.0.1-SNAPSHOT`.

## Decision
- `release.yml` also runs on every push to `main` (merged pull requests). It picks the next version from the
  newest `v*` tag: next **minor** when a merged commit starts with `feat:`, otherwise next **patch**. It creates
  the tag and the GitHub Release, then the hub's runner deploys it as before (health check, rollback, only newer
  versions). A tag pushed by hand still releases; a re-run reuses the commit's tag.
- The build stamps the version into the jar (`-Drevision`, CI-friendly `${revision}` in `pom.xml`); `/api/version`
  returns it and the dashboard shows it ("dev" for a build from source).
- Runs are serialized (`concurrency: deploy-home-hub`); of several waiting merges only the newest is deployed.

## Consequences
- Merge = live in about two minutes. No tag to remember; the screen shows what runs.
- Every merge reaches the home, so a PR must be ready to run at home when it is merged (CI green, reviewed).
  Several changes can't be batched into one release any more, except by merging them close together.
- Pull requests still never run on the hub's runner: only pushes to `main` and tags do. The repository must stay
  private, and the GitHub account protected (2FA), as in ADR 6.
- The version number follows the commit messages, so `feat:` / `fix:` / `chore:` prefixes (rule 7) matter.
