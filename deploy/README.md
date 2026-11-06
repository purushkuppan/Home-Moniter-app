# Deploying HomeHub to home-hub

## How a release reaches the hub
1. Merge pull requests into `main` (CI builds and tests every PR).
2. Tag a version: `git tag v0.1.0 && git push origin v0.1.0`
3. GitHub Actions builds the jar in the cloud and creates a GitHub Release.
4. The self-hosted runner on `home-hub` downloads the jar and runs
   `/opt/homehub/bin/install-release.sh`, which installs it to `/opt/homehub/releases/<version>`,
   points `/opt/homehub/current` at it, restarts the service and checks
   `/actuator/health`. If the check fails, it switches back to the previous release.

The runner connects out to GitHub, so no router ports are opened.

## One-time setup on the hub
```bash
git clone https://github.com/purushkuppan/Home-Moniter-app.git ~/Home-Moniter-app
cd ~/Home-Moniter-app
sudo bash deploy/setup-hub.sh
```

### Install the GitHub Actions runner (free for self-hosted)
1. On GitHub: repo **Settings → Actions → Runners → New self-hosted runner → Linux x64**.
   Keep that page open; it shows the download commands and a one-time token.
2. On the hub:
   ```bash
   sudo -iu ghrunner
   mkdir actions-runner && cd actions-runner
   # paste the download and extract commands from the GitHub page, then:
   ./config.sh --url https://github.com/purushkuppan/Home-Moniter-app --token <TOKEN> --labels home-hub --unattended
   exit
   cd /home/ghrunner/actions-runner && sudo ./svc.sh install ghrunner && sudo ./svc.sh start
   ```
3. The runner should show as **Idle** on the GitHub page with labels `self-hosted, linux, home-hub`.

Keep the repository private: self-hosted runners on public repositories can run code from strangers' pull requests.

## Deploying from your PC (without a tag)
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
