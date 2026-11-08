# Deploying HomeHub to home-hub

## How a release reaches the hub (automatic)
1. Merge a pull request into `main`. CI checks the hub scripts and builds and tests every PR. Nothing else to do.
2. GitHub Actions (`.github/workflows/release.yml`) picks the next version from the newest `v*` tag: the next
   **minor** version when a merged commit is a `feat:` (v0.4.1 → v0.5.0), otherwise the next **patch** version
   (v0.5.0 → v0.5.1). It builds and tests the jar in the cloud with that version stamped in (the dashboard shows
   it), then creates the tag and a GitHub Release. A version tag pushed by hand
   (`git tag v0.6.0 && git push origin v0.6.0`) still makes a release too.
3. Several merges in quick succession are deployed one at a time; the newest one waiting includes the others.
4. The self-hosted runner on `home-hub` downloads the jar and runs
   `/opt/homehub/bin/install-release.sh`, which:
   - installs it to `/opt/homehub/releases/<version>`
   - points `/opt/homehub/current` at it
   - restarts the service and checks `/actuator/health`
   - if the check fails, switches back to the previous release and removes the broken one
5. A tag that is **not newer** than the version already running is released on GitHub but not deployed.
   Pushing old tags is therefore harmless.

Follow it under **Actions** on GitHub (or `gh run watch`).

The runner connects out to GitHub, so no router ports are opened. It runs as the user `ghrunner`, which may
run only `install-release.sh` as root. That script reads the jar with `ghrunner`'s own rights and accepts
only jar files, so it can't be used to copy root-only files. Only merges to `main` and version tags pushed
to the repository trigger a deploy, never pull requests.

**Keep the repository private.** On a public repository, strangers' pull requests could run code on a
self-hosted runner.

## One-time setup on the hub
The repository is private, so copy the `deploy` folder from your PC instead of cloning:
```powershell
scp -r deploy purush@192.168.5.251:~/homehub-deploy
```
Then on the hub:
```bash
cd ~/homehub-deploy
sudo bash setup-hub.sh      # service, folders, credentials, sudo rule, firewall
sudo bash setup-runner.sh   # GitHub runner for automatic deploys
```
Re-run `setup-hub.sh` after any change to `install-release.sh` or `homehub.service` in git. The hub uses its
own copy in `/opt/homehub/bin`, which the runner can't modify.

### The GitHub runner (`setup-runner.sh`)
- Downloads the runner (MIT license, free for self-hosted use) at a pinned version and checks its SHA-256.
- Asks for a **registration token**, valid for one hour. It's typed hidden and is not stored. Easiest, on your PC:
  ```powershell
  gh api -X POST repos/purushkuppan/Home-Moniter-app/actions/runners/registration-token --jq .token
  ```
  It prints 29 letters and digits. Alternatively use repo **Settings → Actions → Runners → New self-hosted
  runner** and copy the value after `--token`. Stray spaces or a whole pasted `config.sh` line are accepted;
  anything else is refused with a hint before GitHub is contacted.
- Registers the runner as `home-hub` with the label `home-hub` and installs it as a systemd service
  (`actions.runner.*`). It uses about 100 MB of memory and updates itself.
- On GitHub the runner should then show as **Idle**.

Remove it: `cd /home/ghrunner/actions-runner && sudo ./svc.sh stop && sudo ./svc.sh uninstall`, then
delete the runner on the GitHub page.

## Deploying from your PC (fallback, without a tag)
`deploy.ps1` in the repo root builds and tests the jar on Windows, copies it to the hub and runs the
same `install-release.sh` (health check and rollback included):
```powershell
.\deploy.ps1 -Version v0.2.0                       # default hub: purush@192.168.5.251
.\deploy.ps1 -Version v0.2.1 -Hub purush@home-hub
```
It asks for your sudo password on the hub. Installing a version that already exists replaces that release.

## Secrets
Never in git, issues, pull requests or chat.

**Home Assistant token**: a systemd credential (`LoadCredential=` in `homehub.service`). systemd passes
it to the service privately and Spring reads it as `homeassistant.token`. Create a long-lived access token
in Home Assistant (Profile → Security), then on the hub:
```bash
sudo sh -c 'umask 077; cat > /etc/homehub/credentials/homeassistant.token'
# paste the token, press Enter, then Ctrl+D
sudo systemctl restart homehub
```
The file must exist or the service won't start; `setup-hub.sh` creates it empty.

**Control PIN** (device controls on the dashboard, at least 4 characters). While the file is empty,
controls are view-only:
```bash
sudo sh -c 'umask 077; cat > /etc/homehub/credentials/homehub.control.pin'
# type the PIN, press Enter, then Ctrl+D
sudo systemctl restart homehub
```

**Other settings** that should stay off git can go in `/etc/homehub/homehub.env` (root-only, mode 600)
as environment variables, one `NAME=value` per line.

After changing `homehub.service` in git, re-run `sudo bash deploy/setup-hub.sh` on the hub to install it.

## Useful commands
```bash
systemctl status homehub          # is it running?
journalctl -u homehub -f          # live logs
ls -l /opt/homehub/current        # which release is live
sudo ln -sfn /opt/homehub/releases/v0.1.0 /opt/homehub/current && sudo systemctl restart homehub   # manual rollback
```
