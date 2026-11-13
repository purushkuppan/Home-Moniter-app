# Runbook: rebuild the hub and restore it from a backup

From a blank disk to a running Kudil hub with last night's data, and the restore drill that proves it works (#123).
Follow it step by step, top to bottom. If a step doesn't work as written, finish the rebuild however you can, then fix
this page so the next person doesn't have to improvise.

The hub today: the laptop `home-hub` (4 GB RAM), Ubuntu Server 26.04, home address `192.168.5.251`, user `purush`.
On it run Kudil (the `homehub` service, Java), Home Assistant and Z-Wave JS UI (Docker), Tailscale, the firewall,
automatic updates, the nightly backup and the self-hosted GitHub runner. The scripts used below live in `deploy/`
and are explained in [deploy/README.md](../deploy/README.md).

**Target: under 2 hours** from "the hub is gone" to "Kudil shows the home's current state again" (measured in the
drill below).

## What the backup holds, and what it doesn't
Each night `backup.sh` writes `/var/backups/homehub/kudil-backup-YYYY-MM-DD.tar.gz` (and a copy on the USB disk when
`COPY_TO` is set):

| In the archive | Not in the archive (recreate it) |
|---|---|
| `kudil/homehub.db`: every event, the device list and its alert choices | Ubuntu itself, its users and settings |
| `homeassistant/`: Home Assistant's settings (`.storage`, YAML, `secrets.yaml`, `.HA_VERSION`) and its history database | `/etc/homehub/credentials/`: Kudil's Home Assistant token and the PIN |
| `zwave-js-ui/`: Z-Wave JS UI's settings **with the Z-Wave security keys**, its users, node list and caches | `/etc/homehub/homehub.env`, `/etc/homehub/backup.env` |
| | The GitHub runner's registration, the hub's Tailscale identity |

The archive holds keys and the home's history: keep every copy root-only or in a private folder, and delete working
copies when you are done.

## Secrets you will need
Names only. **The master copy of each lives in the password manager.** Never paste a value into git, an issue, a pull
request, a chat or a log.

| Secret | Needed in step | Where it ends up |
|---|---|---|
| Ubuntu login password for `purush` (also the `sudo` password) | 1 | the hub |
| Home Assistant owner login | 6 | (only to sign in; the account itself is restored from the archive) |
| Z-Wave security keys S0, S2 Unauthenticated, S2 Authenticated, S2 Access Control, Long Range S2 Authenticated and Access Control | 5 | inside the archive's `zwave-js-ui/settings.json`, restored with it. The password manager copy is for an archive that is lost or damaged: enter them in Z-Wave JS UI (**Settings → Z-Wave → Security keys**) before it starts the network |
| Z-Wave JS UI `admin` password | 5 | restored with the archive (Z-Wave JS UI's users) |
| Home Assistant long-lived access token for Kudil (`homeassistant.token`) | 7 | `/etc/homehub/credentials/homeassistant.token` |
| Household PIN for device controls (`homehub.control.pin`) | 7 | `/etc/homehub/credentials/homehub.control.pin` |
| Tailscale account login (with two-factor authentication) | 9 | the hub joins the tailnet; nothing is stored by hand |
| GitHub account login (for `gh` on the PC) | 7, 12 | the PC |
| GitHub runner registration token | 12 | nowhere: made fresh with `gh`, valid one hour, typed hidden, not stored |
| Samsung account login (SmartThings) | 6, only if Home Assistant asks to sign in again | Home Assistant |
| Off-site copy: storage provider login and the restic repository password | not yet (see *Off-site copy*) | planned |

## Before you start
- The laptop (or any 64-bit PC with at least 4 GB RAM), its charger, a USB stick with the Ubuntu Server 26.04 installer.
- The Z-Wave USB stick. Its own memory holds the Z-Wave network; the archive holds its keys. Together they bring every
  Z-Wave device back without pairing again.
- The newest backup archive, from the USB backup disk (or, once it exists, the off-site copy).
- The PC with this repository checked out, `gh` signed in, and the password manager.
- Note the start time; it goes in the drill log.

## 1. Ubuntu Server
1. Install Ubuntu Server 26.04 from the USB stick: whole disk, hostname **`home-hub`**, user **`purush`**, tick
   **Install OpenSSH server**. No snaps are needed.
2. In the router, give the laptop's MAC address the fixed address **`192.168.5.251`** (DHCP reservation), so phones,
   the PC and the docs keep working. Restart the laptop's network (or the laptop) to pick it up.
3. From the PC: `ssh purush@192.168.5.251`, then:
   ```bash
   sudo timedatectl set-timezone America/Chicago   # the home's time zone, as Home Assistant has it; Kudil's daily times use it
   sudo apt update && sudo apt full-upgrade -y
   sudo apt install -y openjdk-25-jre-headless sqlite3 curl ufw docker.io
   java -version                                    # must say 25
   sudo systemctl enable --now docker
   ```
   Optional: `sudo apt install -y cockpit` (the web console on port 9090 that the firewall opens to the home network).
4. A laptop must keep running with the lid closed: in `/etc/systemd/logind.conf` set `HandleLidSwitch=ignore` and
   `HandleLidSwitchExternalPower=ignore`, then `sudo systemctl restart systemd-logind`.

## 2. Copy the scripts and the archive to the hub
From the PC, in the repository folder (on `main`, up to date):
```powershell
scp -r deploy purush@192.168.5.251:~/homehub-deploy
scp E:\kudil-backup-2026-11-12.tar.gz purush@192.168.5.251:~/     # the newest archive; E: is the USB backup disk
```
(Or plug the USB backup disk into the hub and copy it there: `sudo mount /dev/sdb1 /mnt`.)

## 3. Check the archive
```bash
sudo bash ~/homehub-deploy/restore-check.sh ~/kudil-backup-2026-11-12.tar.gz
```
It only reads: both databases pass SQLite's integrity check, it prints the number of events, the newest event and the
latest reading of every device, and checks Home Assistant's and Z-Wave JS UI's settings are there (without printing
them). **Keep this output**: step 13 compares against it. If it fails, try the archive from the night before.

Then unpack it where the next steps expect it:
```bash
sudo install -d -m 0700 /root/restore
sudo tar -C /root/restore -xzf ~/kudil-backup-2026-11-12.tar.gz
sudo cat /root/restore/homeassistant/.HA_VERSION     # the Home Assistant version that wrote the backup
```

## 4. Kudil's service, folders, firewall and nightly backup
```bash
cd ~/homehub-deploy && sudo bash setup-hub.sh
sudo ufw status | head -n 1     # if it says inactive: sudo ufw enable (SSH is already allowed)
```
`setup-hub.sh` creates the `homehub` and `ghrunner` users, `/opt/homehub`, the empty credential files, the deploy
sudo rule, the firewall rules (`setup-firewall.sh`) and the 03:00 backup timer. Kudil itself isn't installed yet.

Put the events back before Kudil's first start:
```bash
sudo install -o homehub -g homehub -m 0644 /root/restore/kudil/homehub.db /opt/homehub/data/homehub.db
```

## 5. Z-Wave JS UI (with the Z-Wave stick)
Skip this step on a hub without a Z-Wave stick.
1. Plug in the stick and find its stable name: `ls /dev/serial/by-id/` (for example
   `usb-Zooz_800_Z-Wave_Stick_...-if00`).
2. Restore its folder, then start it exactly the way `setup-zwave.sh` expects (the web UI and the Z-Wave server on the
   hub itself only, the stick as `/dev/zwave`, which is the port in the restored settings). For the version, take the
   newest from [the releases page](https://github.com/zwave-js/zwave-js-ui/releases) and write it in the drill log:
   ```bash
   sudo install -d -m 0700 /opt/zwave-js-ui
   sudo cp -a /root/restore/zwave-js-ui/. /opt/zwave-js-ui/
   STICK=/dev/serial/by-id/usb-...-if00          # from step 1
   sudo docker run -d --name zwave-js-ui --restart=unless-stopped \
       -e TZ="$(timedatectl show -p Timezone --value)" --device "$STICK:/dev/zwave" \
       -v /opt/zwave-js-ui:/usr/src/app/store \
       -p 127.0.0.1:3000:3000 -p 127.0.0.1:8091:8091 zwavejs/zwave-js-ui:<version>
   cd ~/homehub-deploy && sudo bash setup-zwave.sh   # checks the lock-down; must end with "Done."
   ```
3. Open the UI through SSH from the PC (`ssh -L 8091:127.0.0.1:8091 purush@192.168.5.251`, then
   http://localhost:8091), sign in as `admin` (password manager). **Control Panel** lists the nodes with their names,
   and they come alive as each device reports (battery devices can take until their next wake-up).

If the archive's `zwave-js-ui/` is lost: start with an empty `/opt/zwave-js-ui`, turn on **Auth**, enter the keys from
the password manager under **Settings → Z-Wave** (serial port `/dev/zwave`) and save. The stick's memory still has the
devices.

## 6. Home Assistant
Restore its folder first, so it starts with the home's settings instead of a fresh onboarding. Use the version from
`.HA_VERSION` (step 3): Home Assistant can't go back a version, and the monthly update can follow later.
```bash
sudo install -d -m 0755 /opt/homeassistant
sudo cp -a /root/restore/homeassistant/. /opt/homeassistant/
sudo docker run -d --name homeassistant --restart=unless-stopped --network=host \
    -e TZ="$(timedatectl show -p Timezone --value)" -v /opt/homeassistant:/config \
    ghcr.io/home-assistant/home-assistant:<version from .HA_VERSION>
```
These are the settings `update-home-assistant.sh` expects. After a few minutes open http://192.168.5.251:8123 and sign
in with the owner login. Check **Settings → Devices & services** (SmartThings, Mobile App, Met.no, Sun and Z-Wave
without errors; if SmartThings asks to sign in again, use the Samsung account) and **Settings → Repairs**.

## 7. Kudil
1. **Token**: in Home Assistant, **Profile → Security → Long-lived access tokens**, delete the old Kudil token and
   create a new one. Save it in the password manager, then on the hub:
   ```bash
   sudo sh -c 'umask 077; cat > /etc/homehub/credentials/homeassistant.token'
   # paste the token, press Enter, then Ctrl+D
   sudo sh -c 'umask 077; cat > /etc/homehub/credentials/homehub.control.pin'
   # type the household PIN, press Enter, then Ctrl+D
   ```
   If the old hub had settings in `/etc/homehub/homehub.env`, put them back now (`sudo nano /etc/homehub/homehub.env`).
2. **Install the newest release**. On the PC:
   ```powershell
   gh release list --repo purushkuppan/Home-Moniter-app --limit 1          # the newest version, vX.Y.Z
   gh release download vX.Y.Z --repo purushkuppan/Home-Moniter-app --pattern homehub.jar
   scp homehub.jar purush@192.168.5.251:/tmp/homehub.jar
   ```
   On the hub:
   ```bash
   sudo /opt/homehub/bin/install-release.sh /tmp/homehub.jar vX.Y.Z   # starts Kudil and checks its health
   rm /tmp/homehub.jar
   ```
   (Once the runner is back, step 12, re-running the newest **Release and deploy** run on GitHub does the same.)
3. Open http://192.168.5.251:8080: the device cards show the latest readings from step 3, then live changes.

## 8. Firewall
`setup-hub.sh` already ran `setup-firewall.sh`. Check: `sudo ufw status numbered` shows SSH, Kudil (8080), Cockpit
(9090) and Home Assistant (8123) from `192.168.4.0/22`. The Tailscale rules come with step 9.

## 9. Tailscale (Kudil away from home)
1. In the Tailscale admin console (**Machines**), remove the old `kudil` machine, or the new one becomes `kudil-1`.
2. On the hub: `cd ~/homehub-deploy && sudo bash setup-tailscale.sh`, and open the sign-in link it prints (Tailscale
   login).
3. In the admin console: `kudil` → **⋯** → **Disable key expiry**.
4. On a phone on mobile data with Tailscale on: http://kudil:8080 opens Kudil.

## 10. Automatic updates
```bash
cd ~/homehub-deploy && sudo bash setup-updates.sh
```
It must not warn that `homehub`, `docker`, `tailscaled` or the backup timer don't start on their own.

## 11. Nightly backup
The timer is on since step 4. If the USB backup disk is used, mount it at boot (an `/etc/fstab` line with `nofail`, for
example at `/media/backup`) and put `COPY_TO=/media/backup` in `/etc/homehub/backup.env`. Then make one now and check it:
```bash
sudo systemctl start homehub-backup && journalctl -u homehub-backup -n 5
sudo bash ~/homehub-deploy/restore-check.sh "/var/backups/homehub/kudil-backup-$(date +%F).tar.gz"
```
This also clears Kudil's "backup missing" alert.

## 12. GitHub runner (automatic deploys)
On the PC, make a registration token (it prints 29 letters and digits, valid one hour):
```powershell
gh api -X POST repos/purushkuppan/Home-Moniter-app/actions/runners/registration-token --jq .token
```
On the hub, `cd ~/homehub-deploy && sudo bash setup-runner.sh` and paste it when asked (hidden). It registers as
`home-hub` and replaces the old hub's runner. On GitHub (**Settings → Actions → Runners**) it shows **Idle**. The
repository must stay **private**.

## 13. Final checks
- [ ] `systemctl status homehub` is active; the dashboard shows the version just installed.
- [ ] Event count and each device's latest reading match the `restore-check.sh` output from step 3 (newer readings
      since then are fine).
- [ ] Open the fridge door (or the back door): it shows in Kudil within seconds.
- [ ] A test alert reaches the phone (for example leave the fridge door open for over a minute).
- [ ] Z-Wave devices report (back door, kitchen leak sensor, indoor smart plug).
- [ ] http://kudil:8080 works over Tailscale; the firewall list is as in step 8.
- [ ] `sudo rm -rf /root/restore ~/kudil-backup-*.tar.gz` (copies of the keys and the history).
- [ ] Note the end time and how long it took in the drill log below; fix this page where you had to improvise.

## Restore drill (every three months)
A backup that was never restored is a hope, not a backup. Every three months (January, April, July, October), restore
last night's archive onto **another machine** (the OmniBook), start Kudil against it, and check it holds the home's
data. The live hub keeps running and nothing on it changes.

What you need on the drill machine: a Linux shell (Ubuntu, or Ubuntu in WSL on Windows) with
`sudo apt install -y openjdk-25-jre-headless sqlite3`, and `gh` signed in (or the jar copied from the PC).

1. Note the start time.
2. Get last night's archive from the off-hub copy: today the USB backup disk; once it exists, the off-site copy
   (`restic restore`, see below). Put it in a private folder:
   ```bash
   mkdir -m 700 ~/kudil-drill && cp /media/backup/kudil-backup-2026-11-12.tar.gz ~/kudil-drill/ && cd ~/kudil-drill
   ```
3. Check it, and keep the output:
   ```bash
   bash restore-check.sh kudil-backup-2026-11-12.tar.gz | tee check.txt   # restore-check.sh from deploy/
   tar -xzf kudil-backup-2026-11-12.tar.gz
   ```
4. Start Kudil against the restored database, with no connectors and no phone alerts (the default profile sends
   nothing; Home Assistant stays off):
   ```bash
   gh release download --repo purushkuppan/Home-Moniter-app --pattern homehub.jar   # the newest release
   java -Xmx384m -jar homehub.jar \
       --spring.datasource.url="jdbc:sqlite:$HOME/kudil-drill/kudil/homehub.db" \
       --homehub.connectors.fake.enabled=false
   ```
5. In a second terminal, compare with `check.txt`:
   ```bash
   sqlite3 ~/kudil-drill/kudil/homehub.db "SELECT count(*) FROM events;"   # same as "events:" in check.txt
   curl -s http://localhost:8080/api/events/latest                          # same device, type, value, time per reading
   ```
   Open http://localhost:8080: the device cards show the same latest states. (A few minutes after it starts, Kudil
   removes events older than its keep time, like the hub does each night at 03:30; the count can then drop by those.
   Each device's latest reading is always kept.)
6. Home Assistant: `restore-check.sh` already checked its database and settings. Also check the integrations are in
   the archive (names only, no secrets):
   ```bash
   grep -o '"domain": *"[a-z_]*"' homeassistant/.storage/core.config_entries | sort -u   # smartthings, mobile_app, zwave_js, met, sun ...
   ```
   Don't start a second Home Assistant with these settings on the home network: it would run the same automations
   and talk to the same SmartThings account and phones as the live one.
7. Stop Kudil (Ctrl+C), note the end time, and remove the copy: `rm -rf ~/kudil-drill`.
8. Write a line in the log below (a `docs:` pull request). Anything you had to improvise becomes a fix to this page
   or an issue.

Time to recover = from step 2 (archive fetched) to step 5 (Kudil shows the right state). **Target: under 2 hours.**
A full rebuild (steps 1 to 13 above) is logged the same way.

### Drill log
| Date | Archive (night of) | From (USB disk / off-site) | Machine | Events match | Latest states match | Time to recover | Problems, fixes | By |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

## Off-site copy (planned, not set up yet)
Today the archives stay on the hub and, with `COPY_TO`, on a USB disk in the same house: a fire or a theft takes them
all. The plan (#123): an **encrypted** copy outside the house with **restic** (BSD-2-Clause) to free cloud storage
through **rclone** (MIT), run after the nightly backup.

**Open decision (owner):** which free storage provider. Each archive is a few MB, so any free tier with a few GB is
enough; pick one with an rclone backend and an account protected by two-factor authentication. Then it needs:
- the **restic repository password**: only in the password manager and in a root-only file on the hub. Without it the
  off-site copy can't be read, by anyone, including us;
- the provider's login (password manager) and rclone's access token for it, in a root-only rclone config on the hub;
- a script and a timer (with a test, like the other deploy scripts), and the drill above switched to restore from the
  off-site copy (`restic restore latest --target ~/kudil-drill`).

Until then, the drill uses the USB disk, and this issue's "done" (a full restore from the off-hub copy) waits for it.
