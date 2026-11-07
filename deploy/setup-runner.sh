#!/usr/bin/env bash
# Installs the GitHub Actions runner on home-hub, so pushing a version tag deploys automatically.
# Run on the hub after setup-hub.sh (which creates the ghrunner user and its sudo rule):
#   sudo bash setup-runner.sh
# It asks for a registration token: GitHub repo > Settings > Actions > Runners > New self-hosted runner,
# copy the value after --token (valid for one hour). The token is not stored or shown.
# Safe to run again: an already registered runner is kept and only its service is (re)started.
#
# The runner (MIT license) connects out to GitHub; no router ports are opened. It runs as ghrunner,
# which may run only /opt/homehub/bin/install-release.sh as root.
# Keep the repository PRIVATE: on a public repository, strangers' pull requests could run code here.
set -euo pipefail

REPO_URL=https://github.com/purushkuppan/Home-Moniter-app
RUNNER_USER=ghrunner
RUNNER_NAME=home-hub
RUNNER_LABELS=home-hub
VERSION=2.338.2
SHA_X64=baebf204307bac6193480852732f0aba2ae97b6c5597c244f4d5558390147c40
SHA_ARM64=670adb2f268fe21dcbb58b3db0e0aa9d5b3bddfe2c651481336e561ff86de945

[[ $EUID -eq 0 ]] || { echo "Run with sudo: sudo bash $0" >&2; exit 1; }
id "$RUNNER_USER" >/dev/null 2>&1 || { echo "User $RUNNER_USER is missing: run setup-hub.sh first." >&2; exit 1; }
[[ -f /etc/sudoers.d/homehub-deploy ]] || { echo "Sudo rule missing: run setup-hub.sh first." >&2; exit 1; }

case "$(uname -m)" in
  x86_64)  ARCH=x64;   SHA=$SHA_X64 ;;
  aarch64) ARCH=arm64; SHA=$SHA_ARM64 ;;
  *) echo "Unsupported CPU: $(uname -m)" >&2; exit 1 ;;
esac

HOME_DIR="$(getent passwd "$RUNNER_USER" | cut -d: -f6)"
DIR="$HOME_DIR/actions-runner"
as_runner() { runuser -u "$RUNNER_USER" -- "$@"; }

if [[ ! -x "$DIR/config.sh" ]]; then
  TARBALL="actions-runner-linux-$ARCH-$VERSION.tar.gz"
  as_runner mkdir -p "$DIR"
  echo "Downloading runner $VERSION ($ARCH)..."
  as_runner curl -fsSL -o "$DIR/$TARBALL" "https://github.com/actions/runner/releases/download/v$VERSION/$TARBALL"
  echo "$SHA  $DIR/$TARBALL" | sha256sum -c -
  as_runner tar -xzf "$DIR/$TARBALL" -C "$DIR"
  rm -f "$DIR/$TARBALL"
fi

# Libraries the runner needs (.NET: ICU, OpenSSL and so on), from Ubuntu's own packages.
(cd "$DIR" && ./bin/installdependencies.sh >/dev/null) || echo "Note: installdependencies.sh reported a problem; continuing."

if [[ ! -f "$DIR/.runner" ]]; then
  read -rsp "Registration token (from the GitHub page, not shown): " TOKEN
  echo
  [[ -n "$TOKEN" ]] || { echo "No token given." >&2; exit 1; }
  (cd "$DIR" && as_runner ./config.sh --url "$REPO_URL" --token "$TOKEN" \
      --name "$RUNNER_NAME" --labels "$RUNNER_LABELS" --work _work --unattended --replace)
  unset TOKEN
else
  echo "Runner already registered; keeping it."
fi

cd "$DIR"
[[ -f .service ]] || ./svc.sh install "$RUNNER_USER"   # svc.sh writes .service once the unit is installed
./svc.sh start
./svc.sh status | head -n 5

echo
echo "Done. On GitHub (Settings > Actions > Runners) the runner '$RUNNER_NAME' should show as Idle"
echo "with the labels self-hosted, Linux, X64, $RUNNER_LABELS."
