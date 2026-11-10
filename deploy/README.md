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
sudo bash setup-hub.sh      # service, folders, credentials, sudo rule, firewall, nightly backup
sudo bash setup-runner.sh   # GitHub runner for automatic deploys
sudo bash setup-tailscale.sh   # optional: open Kudil away from home (see Remote access)
```
Re-run `setup-hub.sh` after any change to `install-release.sh`, `backup.sh` or the systemd units in git. The hub uses its
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

## Backups
Every night at 03:00 `homehub-backup.timer` runs `/opt/homehub/bin/backup.sh`, which writes one archive:
`/var/backups/homehub/kudil-backup-YYYY-MM-DD.tar.gz`. It contains:
- `kudil/homehub.db`: all events, copied with SQLite's online backup (safe while Kudil runs, includes the
  `-wal` file) and checked with `PRAGMA integrity_check`
- `homeassistant/`: Home Assistant's configuration (`.storage`, YAML files, `secrets.yaml`) and a checked copy of
  its history database; logs and caches are left out

The newest 14 archives are kept (`KEEP`); a failed backup never removes older ones. If the hub was off at 03:00,
the backup runs soon after it starts. Each archive is a few MB.

The archives hold Home Assistant's `secrets.yaml` and your home's history, so they are root-only (folder `0700`,
files `0600`). Keep any copy just as private. Kudil's own token and PIN in `/etc/homehub/credentials` are **not**
backed up; after a rebuild, create a new Home Assistant token and set the PIN again (see *Secrets*).

**Also keep the archives off the hub's disk**: a disk failure takes `/var/backups` with it. With a USB disk
mounted (for example at `/media/backup`), put this line in `/etc/homehub/backup.env` (`sudo nano /etc/homehub/backup.env`):
```
COPY_TO=/media/backup
```
If that folder is missing when the backup runs, the backup fails (rather than filling the hub's disk).

Useful commands:
```bash
sudo systemctl start homehub-backup                 # back up now
journalctl -u homehub-backup -n 20                  # how the last backups went
systemctl list-timers homehub-backup                # when the next one runs
sudo ls -l /var/backups/homehub                     # the archives
cat /var/lib/homehub-backup/last-success            # time of the last good backup
```

### Restoring
Unpack the archive you want:
```bash
sudo mkdir /root/restore
sudo tar -C /root/restore -xzf /var/backups/homehub/kudil-backup-2026-11-09.tar.gz
```
Kudil's events. Stop the service, and remove the `-wal` and `-shm` files first: a leftover `-wal` belongs to the
old database and would damage the restored one.
```bash
sudo systemctl stop homehub
sudo rm -f /opt/homehub/data/homehub.db-wal /opt/homehub/data/homehub.db-shm
sudo install -o homehub -g homehub -m 0644 /root/restore/kudil/homehub.db /opt/homehub/data/homehub.db
sudo systemctl start homehub
```
Home Assistant:
```bash
sudo docker stop homeassistant
sudo rm -f /opt/homeassistant/home-assistant_v2.db-wal /opt/homeassistant/home-assistant_v2.db-shm
sudo cp -a /root/restore/homeassistant/. /opt/homeassistant/
sudo docker start homeassistant
```
Then `sudo rm -rf /root/restore`.

## Remote access (Tailscale)
Kudil has no login yet, so it is never opened to the internet. Away from home it is reached through a private
Tailscale network ([ADR 10](../docs/adr/0010-remote-access-through-tailscale.md)): only your own signed-in devices can
reach it, and nothing changes on the router.

One-time setup on the hub (prints a sign-in link the first time):
```bash
cd ~/homehub-deploy && sudo bash setup-tailscale.sh
```
Then:
1. In the Tailscale admin console (**Machines**), open `kudil`'s **⋯** menu → **Disable key expiry**.
2. Install the **Tailscale** app on each phone or laptop and sign in with the same account.
3. Open **http://kudil:8080** (or `http://<the hub's 100.x.y.z address>:8080`) from anywhere.

Only port 8080 is open to Tailscale devices (two rules at the top of `sudo ufw status numbered`: allow 8080 and deny
the rest on `tailscale0`); Home Assistant, Cockpit
and SSH stay home-network only. Protect the Tailscale account with two-factor authentication: whoever can add a device
to it can open Kudil.

Useful commands: `tailscale status` (devices and addresses), `tailscale ip -4`, `sudo tailscale down` / `up`.

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
