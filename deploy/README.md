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
sudo bash setup-updates.sh     # automatic security updates, restart at 04:00 when needed (see Updates)
sudo bash setup-zwave.sh       # with a Z-Wave stick: Z-Wave JS UI on the hub only, keys root-only (see Z-Wave)
sudo bash setup-ntfy.sh        # alerts to Android phones through ntfy (see ntfy), after setup-hub.sh
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
- `zwave-js-ui/` (on a hub with a Z-Wave stick): Z-Wave JS UI's settings with the **Z-Wave security keys**, its
  node list and caches; logs and web sessions are left out. With these, a new install takes over the stick's network
  without re-pairing any device.

The newest 14 archives are kept (`KEEP`); a failed backup never removes older ones. If the hub was off at 03:00,
the backup runs soon after it starts. Each archive is a few MB.

The archives hold Home Assistant's `secrets.yaml`, the Z-Wave security keys and your home's history, so they are root-only (folder `0700`,
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
Z-Wave JS UI (the stick must be plugged in; its own memory holds the network, the folder holds the keys):
```bash
sudo docker stop -t 30 zwave-js-ui
sudo cp -a /root/restore/zwave-js-ui/. /opt/zwave-js-ui/
sudo chmod 0700 /opt/zwave-js-ui
sudo docker start zwave-js-ui
```
Then `sudo rm -rf /root/restore`.

### Rebuilding the hub
[docs/runbook-rebuild-hub.md](../docs/runbook-rebuild-hub.md) takes a new or wiped laptop to a running hub
(Ubuntu, Kudil, Home Assistant, Z-Wave JS UI, Tailscale, firewall, updates, backups, the GitHub runner) and then
restores the data from an archive. It also has the restore drill, every three months.

Before restoring from an archive, check it. `restore-check.sh` only reads: it unpacks the archive into a private
temporary folder (removed afterwards), checks both databases, prints the number of events and each device's latest
reading, and checks that Home Assistant's and Z-Wave JS UI's settings are there, without printing anything from them:
```bash
sudo bash ~/homehub-deploy/restore-check.sh /var/backups/homehub/kudil-backup-2026-11-09.tar.gz
```
It ends with `The archive is good` or stops and says what is wrong. It needs `sqlite3` (installed by `setup-hub.sh`).

## Z-Wave (Z-Wave JS UI)
Z-Wave JS UI runs in its own container (`zwave-js-ui`, MIT) with its data and the Z-Wave security keys in
`/opt/zwave-js-ui`. Home Assistant talks to it on `127.0.0.1:3000`. `setup-zwave.sh` locks it down (#144):
- its web UI (8091) answers on the hub itself only. Docker-published ports bypass the firewall, so `0.0.0.0:8091`
  would be open to every Wi-Fi and Tailscale device;
- `/opt/zwave-js-ui` can be read by root only;
- it checks that the UI asks for a login.
```bash
cd ~/homehub-deploy && sudo bash setup-zwave.sh
```
It recreates the container with the same image, stick, folder and time zone, and starts the old one again if the new
one doesn't come back within two minutes. Z-Wave devices are unavailable for a few seconds; Kudil ignores that.

**Opening the UI** (to pair or remove a device), from the PC:
```powershell
ssh -L 8091:127.0.0.1:8091 purush@192.168.5.251
```
then open http://localhost:8091 while that window stays open. If the script says there is no login yet: in the UI,
**Settings → General → Auth** on → **Save**, sign in as `admin` / `zwave`, and change the password at once.

## Updates
### Ubuntu and Tailscale: automatic
`setup-updates.sh` (run once: `cd ~/homehub-deploy && sudo bash setup-updates.sh`) lets `unattended-upgrades`
install Ubuntu's security updates and Tailscale's updates every day (around 06:00), and remove old kernels. When an
update needs a restart (a new kernel), the hub restarts at **04:00**, after the 03:00 backup. Kudil, Home Assistant and
Tailscale start again on their own. Kudil itself is updated by merging to `main` (above).
```bash
cat /var/log/unattended-upgrades/unattended-upgrades.log   # what was installed
cat /var/run/reboot-required 2>/dev/null                    # a restart is waiting for 04:00
```

### Home Assistant: once a month, by hand
Home Assistant runs a fixed version (`docker inspect homeassistant --format '{{.Config.Image}}'`), so it changes
only when you update it. A new version comes out on the first Wednesday of each month, and fixes (`.1`, `.2`) follow
in the next days. Update about a week in, to the newest fix:
1. Read the release notes on the [Home Assistant blog](https://www.home-assistant.io/blog/categories/release-notes/),
   especially **Backward-incompatible changes**, for what Kudil uses: **SmartThings**, **Mobile App**
   (notifications), **Met.no** (weather), **Sun**. Skip a version if one of them has an open problem.
2. Copy the latest scripts and run the update. The version is the number from
   [the releases page](https://github.com/home-assistant/core/releases), e.g. `2026.12.1`:
   ```powershell
   scp deploy/update-home-assistant.sh purush@192.168.5.251:~/homehub-deploy/
   ssh -t purush@192.168.5.251 "sudo bash ~/homehub-deploy/update-home-assistant.sh 2026.12.1"
   ```
   It backs up first, keeps the old container until the new one answers, and goes back to it if the new one
   doesn't start within 10 minutes. Nothing changes if the backup or the download fails.
3. Check: open the fridge door and see it in Kudil within seconds; in Home Assistant, **Settings → Repairs** is empty
   and **Settings → Devices & services** shows no errors.
4. If it went back, or something is wrong: Home Assistant may already have changed its database, so restore it from
   today's backup (Restoring, above).

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

## ntfy (alerts to Android phones)
Kudil sends Android phones their alerts through **ntfy** on the hub (#154, ADR 12); iPhones keep the Home Assistant app.
`setup-ntfy.sh` sets it up, and is safe to run again:
```bash
cd ~/homehub-deploy && sudo bash setup-hub.sh    # first, once: Kudil's service then loads the ntfy credentials
sudo bash setup-ntfy.sh
```
- Installs ntfy (Apache-2.0/GPL-2.0, its own process, nothing linked into Kudil) at the **pinned** version in the
  script, from its GitHub release, checked against that release's `checksums.txt`. To move to a newer one, read the
  release notes, change `VERSION` and run the script again.
- Runs it as the `ntfy` service on port **2586**, as the user `ntfy`, with at most 96 MB of memory. Login is required
  by default (`deny-all`), and there is no sign-up and no web app.
- Makes a random topic (`kudil-` and 16 letters and digits) and a user `kudil` that may **only publish** to it, with
  a token. Phones may **only read** that topic, without a login. Nobody else can publish alerts that look like
  Kudil's.
- Writes the topic and the token as Kudil's systemd credentials (`homehub.ntfy.topic`, `homehub.ntfy.token` in
  `/etc/homehub/credentials`), never in git, and restarts Kudil once when they are new. `homehub.ntfy.url` is in
  `application-prod.properties`.
- Opens 2586 to the home network and to Tailscale devices only (`setup-tailscale.sh` keeps it open when run again).
- Prints the topic and the phone setup. The full phone steps are in the main README (Alerts › Android phones through
  ntfy).

Check it: `systemctl status ntfy`, and `curl -s http://127.0.0.1:2586/v1/health` says `{"healthy":true}`. A new
token: empty `/etc/homehub/credentials/homehub.ntfy.token` and run the script again (the old one stays valid until
removed with `sudo runuser -u ntfy -- ntfy token remove kudil <token>`).

## Firewall
`setup-firewall.sh` (also run by `setup-hub.sh`) opens Kudil (8080), Cockpit (9090) and Home Assistant (8123) to the
home network `192.168.4.0/22` only, and removes older rules that opened them to anyone. SSH is left as it is.
```bash
cd ~/homehub-deploy && sudo bash setup-firewall.sh
```
`sudo ufw status numbered` then shows, from the top: the Tailscale rules (Kudil, ntfy, then "nothing but Kudil"), SSH, the three home-network rules and
Tailscale's own port 41641/udp. The hub's IPv6 address is a private home one, so the IPv4 rules are all the home needs.

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
