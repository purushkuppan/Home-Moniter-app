# 6. Automatic deploys with a self-hosted GitHub runner on the hub

Date: 2026-11-06 · Status: accepted · Changed by: [ADR 9](0009-deploy-on-every-merge.md) (a merge to `main` deploys, no tag needed)

## Context
Releases went out with `deploy.ps1` from the PC. That depends on whoever runs it being on the right,
up-to-date branch, and it needs the hub's sudo password every time. CI already builds every PR on GitHub.
The hub is on the home network behind the router, so GitHub can't call it (no webhook), and opening a router
port would expose it to the internet.

## Decision
- Pushing a `vX.Y.Z` tag runs `release.yml`. GitHub's cloud runner builds and tests the jar and publishes a
  GitHub Release. Then a **self-hosted runner on the hub** (label `home-hub`) installs it with
  `install-release.sh`: health check, automatic rollback, broken release removed.
- The runner connects out to GitHub, so no inbound ports are needed. It runs as the unprivileged user
  `ghrunner`, whose only root right is `sudo /opt/homehub/bin/install-release.sh`. The script is owned by root,
  reads the jar with the caller's rights and accepts only jar files.
- Tags not newer than the running version are released but not deployed, so old tags can be pushed safely.
- CI runs ShellCheck on the hub scripts, so a broken script fails the PR, not the deploy.
- `deploy.ps1` stays as the manual fallback.

## Consequences
- Merge, tag, done: no sudo password, and a release always comes from a tagged commit with a GitHub Release.
- The repository must stay **private**. A self-hosted runner on a public repository could run strangers' code.
  Only tag pushes (owner only) reach the hub runner, never pull requests.
- About 100 MB more memory on the hub; the runner updates itself.
- Anyone who can push tags to the repository can deploy to the hub. Protect the GitHub account (2FA).
