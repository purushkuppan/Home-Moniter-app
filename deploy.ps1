<#
.SYNOPSIS
  Builds HomeHub, copies it to the hub and installs it as a release.

.DESCRIPTION
  1. Runs the tests and builds the jar (mvnw.cmd clean package). Stops if anything fails.
  2. Copies the jar to the hub:  scp -> /tmp/homehub.jar
  3. Over ssh -t (asks for your sudo password on the hub) runs the hub's own release installer,
     /opt/homehub/bin/install-release.sh. It installs the jar to /opt/homehub/releases/<version>,
     points /opt/homehub/current at it, restarts the homehub service, waits for /actuator/health
     and rolls back to the previous release if the health check fails.
     This is the same installer the GitHub release workflow uses.
  4. Prints the service status.

  No secrets live in this script. SSH uses your normal login (password or SSH key), and the
  Home Assistant token stays on the hub as a systemd credential.

.PARAMETER Version
  Release version in the form vMAJOR.MINOR.PATCH, for example v0.2.0. Installing a version that
  already exists on the hub replaces that release.

.PARAMETER Hub
  The hub to deploy to (user@host).

.EXAMPLE
  .\deploy.ps1 -Version v0.2.0
.EXAMPLE
  .\deploy.ps1 -Version v0.2.1 -Hub purush@home-hub
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v\d+\.\d+\.\d+$')]
    [string]$Version,

    # The hub to deploy to (user@host).
    [string]$Hub = 'purush@192.168.5.251'
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Prefer Windows' built-in OpenSSH: its interactive prompts (like sudo) work best in Windows terminals.
$sshDir = Join-Path $env:SystemRoot 'System32\OpenSSH'
$ssh = if (Test-Path "$sshDir\ssh.exe") { "$sshDir\ssh.exe" } else { 'ssh' }
$scp = if (Test-Path "$sshDir\scp.exe") { "$sshDir\scp.exe" } else { 'scp' }

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

Step 'Running tests and building the jar'
& .\mvnw.cmd -q clean package "-Drevision=$($Version.TrimStart('v'))"   # the jar knows its release (shown on the dashboard)
if ($LASTEXITCODE -ne 0) { throw "Build or tests failed (exit code $LASTEXITCODE). Nothing was deployed." }

$jar = Get-ChildItem target\homehub-*.jar | Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
if (-not $jar) { throw 'No jar found in target\ after the build.' }
Write-Host ("Built {0} ({1:N1} MB)" -f $jar.Name, ($jar.Length / 1MB))

Step "Copying the jar to $Hub"
& $scp $jar.FullName "${Hub}:/tmp/homehub.jar"
if ($LASTEXITCODE -ne 0) { throw "Copy to $Hub failed (exit code $LASTEXITCODE)." }

Step "Installing release $Version (enter your sudo password on the hub if asked)"
# install-release.sh restarts the service and health-checks it; on failure it rolls back and exits non-zero.
& $ssh -t $Hub "sudo /opt/homehub/bin/install-release.sh /tmp/homehub.jar $Version; status=`$?; rm -f /tmp/homehub.jar; exit `$status"
$installExit = $LASTEXITCODE

Step 'Service status'
& $ssh $Hub 'systemctl status homehub --no-pager --lines=15; echo; echo "Live release: $(readlink -f /opt/homehub/current)"'

if ($installExit -eq 0) {
    Write-Host "`nDeployed $Version. The service passed its health check." -ForegroundColor Green
} else {
    Write-Host "`n$Version was NOT deployed (exit code $installExit). If it failed the health check, the hub rolled back to the previous release; see the log above or run: journalctl -u homehub -n 50" -ForegroundColor Yellow
    exit 1
}
