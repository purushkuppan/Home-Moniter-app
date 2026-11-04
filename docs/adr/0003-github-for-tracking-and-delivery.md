# 3. GitHub for tracking, CI and deployment

Date: 2026-11-03 · Status: accepted

## Context
We want a real development process (tasks, reviews, automatic deployment) with free tools
only, without running heavy software on the 4 GB hub.

## Decision
GitHub Issues, Milestones (releases R1–R7) and a GitHub Project board for tracking.
GitHub Actions on GitHub's runners for build and test. A self-hosted runner on `home-hub`
only installs releases when a version tag is pushed. Dependabot for dependency updates.

## Consequences
Free for a private repository within GitHub's free build minutes. Every change links to an
issue. The hub never compiles code. The repository must stay private while it has a
self-hosted runner.
